package com.a.anizm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume

// Sistenn family (sistenn.uns.bio, rpmvid, strp2p): the resolver page, and the older player API.

internal suspend fun AnizmProvider.sistennResolve(host: String, id: String): SistennResolved {
    if (sistennGone["$host#$id"] == true) return SistennResolved.Gone
    val origin = "https://$host"
    val r = try {
        browserGet("$origin/api/public/resolve?file1=${java.net.URLEncoder.encode(id, "UTF-8")}", clientHints + mapOf(
            "User-Agent" to ua, "Accept" to "*/*", "Referer" to "$origin/", "Accept-Language" to acceptLang,
            "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Dest" to "empty"), 8L)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { return SistennResolved.NotOffered }
    val text = r.text
    val o = try { JSONObject(text.trim()) } catch (_: Exception) { return SistennResolved.NotOffered }
    val url = o.optString("url").takeIf { it.startsWith("http") || it.startsWith("/") }?.let { Hls.resolve("$origin/", it) }
    if (r.code in 200..299 && url != null) return SistennResolved.Url(url)
    log("sistenn: $host has no video for $id (http ${r.code}: ${oneLine(text, 80)})")
    sistennGone["$host#$id"] = true
    return SistennResolved.Gone
}

internal suspend fun AnizmProvider.trySistennApi(exUrl: String, label: String, callback: (ExtractorLink) -> Unit): SistennResult {
    val m = sistennPageRe.find(exUrl) ?: return SistennResult.UNAVAILABLE
    val host = m.groupValues[1]; val id = m.groupValues[2]
    val now = System.currentTimeMillis()
    if ((sistennApiOffUntil[host] ?: 0L) > now) return SistennResult.UNAVAILABLE
    val origin = "https://$host"
    val apiHeaders = clientHints + mapOf("User-Agent" to ua, "Referer" to "$origin/", "Accept" to "*/*",
        "Accept-Language" to acceptLang,
        "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Dest" to "empty")
    fun off(why: String) { sistennApiOffUntil[host] = System.currentTimeMillis() + 60 * 60_000L; log("sistenn-api: $host not usable ($why) — WebView for 1 h") }
    // (v48: the page and its script are no longer fetched first. That imitated the old player
    // page, which these hosts have replaced with the resolver page; see sistennResolve.)
    // The page asks /info first; so do we (it also says early if the id is gone).
    try { browserGet("$origin/api/v1/info?id=$id", apiHeaders, 8L).closeQuietly() }
    catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
    // v41: window.screen, as the page reports it (the server logs it beside the OS it reads
    // from the UA). The phone's real screen in CSS pixels; 1920x1080 contradicted the Android
    // UA. The server does not pick qualities by it (a 1280x720 browser got 1080p too); the
    // page's player caps quality to its own size, which this app's player doesn't.
    val (screenW, screenH) = deviceScreenCss()
    var capacity: Pair<String, String>? = null
    var video: JSONObject? = null
    var sources: List<SistennSource> = emptyList()
    // v39: one capacity retry, not two. rpmvid and strp2p stayed full through both (device
    // log 2026-09-26), which cost 9 s per host at the end of the episode's link loading.
    for (attempt in 0 until 2) {
        val capQ = capacity?.let { "&capacityToken=${java.net.URLEncoder.encode(it.first, "UTF-8")}&capacityTokenExpire=${java.net.URLEncoder.encode(it.second, "UTF-8")}" }.orEmpty()
        val r = browserGet("$origin/api/v1/video?id=$id&w=$screenW&h=$screenH&r=$mainHost$capQ", apiHeaders, 10L)
        val body = r.text
        if (r.code !in 200..299) {
            val plainErr = sistennDecrypt(body) ?: body
            log("sistenn-api: video http ${r.code}: ${oneLine(plainErr, 160)}")
            // v48: 404 too. "Cannot GET /api/v1/video" means this host no longer has the API at all,
            // and every source on it used to ask again (three requests each) on every load.
            if (r.code in 500..599 || (r.code == 404 && sistennDecrypt(body) == null)) off("http ${r.code}")
            return SistennResult.UNAVAILABLE
        }
        val plain = sistennDecrypt(body) ?: run { off("answer not decryptable: ${body.take(40)}"); return SistennResult.UNAVAILABLE }
        val o = try { JSONObject(plain) } catch (_: Exception) { off("answer not JSON"); return SistennResult.UNAVAILABLE }
        sources = sistennSources(o, origin)
        if (sources.isNotEmpty()) { video = o; break }
        val d = o.optJSONObject("delivery") ?: JSONObject()
        val saturated = d.optString("inHouse") == "saturated"
        val capToken = d.optString("capacityToken")
        if (saturated && capToken.isNotEmpty() && attempt < 1) {
            val wait = d.optInt("retryAfter", 3).coerceIn(2, 4)
            log("sistenn-api: $host is at capacity — retrying once in ${wait}s")
            capacity = capToken to d.optString("capacityTokenExpire")
            delay(wait * 1000L)
            continue
        }
        log("sistenn-api: $id has no stream${if (saturated) " — streaming capacity is full" else ""} (keys ${o.keys().asSequence().take(12).joinToString(",")})")
        return if (saturated) SistennResult.CAPACITY else SistennResult.UNAVAILABLE
    }
    val v = video ?: return SistennResult.UNAVAILABLE
    val pk = v.optJSONObject("pk")
    val pkQuery = pk?.optString("k")?.takeIf { it.isNotEmpty() }?.let { "k=$it&kx=${pk.optLong("kx")}" }.orEmpty()
    // Our own refresh call, the way the page builds it.
    val metric = v.optJSONObject("metric")
    // v41: built for every stream (it is also the playing signal), not only for pk tokens.
    val refreshUrl = try {
        "$origin/api/v1/player?t=" + sistennEncrypt(JSONObject().apply {
            put("website", mainHost); put("playing", true); put("sessionId", sistennSessionId)
            for (k in listOf("userId", "playerId", "videoId", "country", "platform", "browser", "os")) put(k, metric?.optString(k) ?: "")
        }.toString())
    } catch (_: Exception) { null }
    log("sistenn-api: ${v.optString("title").take(60)} — sources ${sources.joinToString { it.kind }}")
    val streamHeaders = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin, "Accept" to "*/*")
    // Sources are checked in parallel: one after another, the backup search took 16 s
    // after Tiktok was already found (device log 2026-09-26, build 31), and held up the
    // episode's next wave of sources. The first working source is still awaited in full;
    // a second one only if it is ready within sistennBackupWaitMs of the start.
    // v39: two at a time, not all at once. Each check is ~5 requests and at most two sources are
    // listed; the next one starts when a running one turns out unreadable.
    val t0 = System.currentTimeMillis()
    var sourcesEmitted = 0
    coroutineScope {
        val checks = ArrayList<Pair<SistennSource, kotlinx.coroutines.Deferred<SistennChecked?>>>()
        var nextCheck = 0
        fun startNextCheck() { synchronized(checks) { checks.getOrNull(nextCheck++) }?.second?.start() }
        for (src in sources) checks += src to async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            (try { checkSistennSource(src, pkQuery, streamHeaders) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { log("sistenn-api: ${src.kind} check failed: ${e.javaClass.simpleName}"); null })
                .also { if (it == null) startNextCheck() }
        }
        repeat(2) { startNextCheck() }
        for ((src, job) in checks) {
            if (sourcesEmitted >= 2) break
            // A backup that never had to start is not started now.
            if (sourcesEmitted > 0 && !job.isActive && !job.isCompleted) continue
            val c = if (sourcesEmitted == 0) job.await()
                else withTimeoutOrNull((t0 + sistennBackupWaitMs - System.currentTimeMillis()).coerceAtLeast(1L)) { job.await() }
            if (c == null) {
                if (!job.isCompleted) log("sistenn-api: ${src.kind} still checking — not waiting for it as a backup")
                continue
            }
            val tokenState = if (c.tokenQuery.isNotEmpty() && c.tokenQuery.startsWith("k=${pk?.optString("k")}&") && refreshUrl != null)
                LocalHlsServer.TokenState(c.tokenQuery, refreshUrl, apiHeaders) else null
            val beat = refreshUrl?.let { LocalHlsServer.Heartbeat(it, apiHeaders, tokenState) }
            // v39: sizes, like Beta and Aincrad. Same episode, same length, so a bigger size
            // means more data per second of video.
            val sizeKey = "sistenn:$host:$id:${src.kind}"
            val sizes = if (estimateHlsSizes) cachedSizes(sizeKey)
                ?: (withTimeoutOrNull(5_000L) { sistennSizes(src.kind, c, streamHeaders, src.strip) } ?: emptyMap()).also { storeSizes(sizeKey, it) }
                else emptyMap()
            var emitted = 0
            // v38: a second working source is listed as an alternative ("· 2"), so the app's
            // automatic next-link fallback has another Sistenn route to try.
            val tag = if (sourcesEmitted == 0) "" else " · ${sourcesEmitted + 1}"
            for ((vv, text) in c.loaded.sortedByDescending { it.first.height }) {
                val local = localHls.register(vv.url, c.tokenQuery, streamHeaders, text, stripSegments = src.strip, token = tokenState, beat = beat) ?: continue
                callback(variantLink(vv, label, formatSize(sizes[vv.height], isEstimate = true), "$origin/", streamHeaders, url = local, tag = tag))
                emitted++
            }
            if (emitted > 0) {
                val segHost = try { java.net.URI(Hls.firstSegmentUrl(c.loaded.first().second, c.loaded.first().first.url) ?: "").host } catch (_: Exception) { null }
                log("sistenn-api: ${src.kind} → ${c.loaded.joinToString { "${it.first.height}p" }}${if (c.variantCount > c.loaded.size) " (${c.variantCount - c.loaded.size} variant(s) did not load)" else ""}, segments on ${segHost ?: "?"}, no WebView needed")
                sourcesEmitted++
            }
        }
        checks.forEach { it.second.cancel() }
    }
    if (sourcesEmitted > 0) return SistennResult.EMITTED
    log("sistenn-api: no source of $id was readable from here — falling back to the WebView")
    return SistennResult.UNAVAILABLE
}

/** One Sistenn source: its master, and the variants whose playlist loads and whose first segment answers. */
internal suspend fun AnizmProvider.checkSistennSource(src: SistennSource, pkQuery: String, streamHeaders: Map<String, String>): SistennChecked? {
    // /v4/ hosts want the token on every request (the page's own rule); cfNative carries its own.
    val needsPk = src.url.contains("/v4/") && !pkParamRe.containsMatchIn(src.url) && pkQuery.isNotEmpty()
    val masterUrl = if (needsPk) src.url + (if (src.url.contains('?')) "&" else "?") + pkQuery else src.url
    val tokenQuery = tokenQueryRe.find(masterUrl.substringAfter('?', ""))?.groupValues?.get(1).orEmpty()
    val master = try { app.get(masterUrl, headers = streamHeaders, timeout = 6L).takeIf { it.code in 200..299 }?.text }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    if (master == null || !master.trimStart().startsWith("#EXTM3U")) { log("sistenn-api: ${src.kind} master not readable, next"); return null }
    val variants = parseVariants(master, masterUrl).map { vv ->
        if (tokenQuery.isNotEmpty() && !vv.url.contains('?')) vv.copy(url = vv.url + "?" + tokenQuery) else vv
    }.ifEmpty { listOf(Variant(Qualities.Unknown.value, null, masterUrl)) } // a media playlist itself
    // Only qualities whose playlist actually loads (the /v4/ hosts stall some 1080p variants).
    val loaded = coroutineScope {
        variants.map { vv -> async {
            val t = if (vv.url == masterUrl) master else try {
                withTimeoutOrNull(5_000L) { app.get(vv.url, headers = streamHeaders, timeout = 5L).takeIf { it.code in 200..299 }?.text }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            val text = t?.takeIf { it.trimStart().startsWith("#EXTM3U") } ?: return@async null
            // v38: and whose first segment answers. A playlist can load while its segments
            // stall (cfNative, the /v4/ hosts' 1080p), which hangs the player for a minute.
            val segUrl = Hls.firstSegmentUrl(text, vv.url, tokenQuery) ?: return@async (vv to text)
            val segOk = try {
                withTimeoutOrNull(5_000L) {
                    val r = app.get(segUrl, headers = streamHeaders + mapOf("Range" to "bytes=0-0"), timeout = 5L)
                    val ok = r.code == 200 || r.code == 206
                    r.closeQuietly()
                    if (!ok) log("sistenn-api: ${src.kind} ${vv.height}p segment answered http ${r.code}")
                    ok
                } ?: false.also { log("sistenn-api: ${src.kind} ${vv.height}p segment did not answer in 5 s (${try { java.net.URI(segUrl).host } catch (_: Exception) { "?" }})") }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
            if (segOk) vv to text else null
        } }.mapNotNull { it.await() }
    }
    if (loaded.isEmpty()) { log("sistenn-api: ${src.kind} variants not readable, next"); return null }
    return SistennChecked(tokenQuery, variants.size, loaded)
}

/** Measured size per quality of a checked Sistenn source, from the playlists already in hand. */
internal suspend fun AnizmProvider.sistennSizes(kind: String, c: SistennChecked, headers: Map<String, String>, disguised: Boolean): Map<Int, Long> {
    val out = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    coroutineScope {
        for ((vv, text) in c.loaded) launch {
            val media = Hls.parseMedia(text, vv.url, c.tokenQuery) ?: return@launch
            val m = try { sampleRendition(media, headers, 8, vv.url.substringBefore('?').hashCode()) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            var bytes = m?.bytes ?: return@launch
            // v48: disguised segments (an image header in front of the media) report their size
            // with the disguise; the local server cuts it off, so it is taken off here too.
            if (disguised && !m.exact) m.sizes.keys.firstOrNull()?.let { i ->
                val head = try {
                    val r = app.get(media.segs[i].url, headers = headers + mapOf("Range" to "bytes=0-65535"), timeout = 6L)
                    if (r.code == 206 || r.code == 200) bodyBytes(r) else { r.closeQuietly(); null }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                val at = head?.let { Hls.findMediaStart(it) } ?: 0
                if (at > 0) bytes -= at.toLong() * media.segs.size
            }
            out[vv.height] = bytes
            log("size: sistenn $kind ${vv.height}p ≈ ${bytes / 1_048_576}MB (${(bytes * 8 / media.total / 1000).toLong()} kbps measured, ${(media.total / 60).toInt()} min)")
        }
    }
    return out
}

/** The stream candidates, best first: cfNative, then the site's own order (streamingConfig). */
internal fun AnizmProvider.sistennSources(o: JSONObject, origin: String): List<SistennSource> {
    fun abs(u: String): String? = u.trim().takeIf { it.isNotEmpty() }?.let {
        if (it.startsWith("//")) "https:$it" else try { java.net.URI("$origin/").resolve(it).toString() } catch (_: Exception) { null }
    }
    val cfg = try { JSONObject(o.optString("streamingConfig", "")) } catch (_: Exception) { null }
    val order = cfg?.optJSONArray("order")?.let { a -> (0 until a.length()).map { a.optString(it) } }
        ?: listOf("Tiktok", "Google", "Cloudflare", "In-House")
    val adjust = cfg?.optJSONObject("adjust")
    val raw = mapOf("Cloudflare" to o.optString("cf"), "Tiktok" to o.optString("hlsVideoTiktok"),
        "Google" to o.optString("hlsVideoGoogle"), "In-House" to o.optString("source"))
    val out = ArrayList<SistennSource>()
    // v38: the site's own order, with cfNative (Cloudflare for native players) just ahead of
    // Cloudflare. v37 tried cfNative first; its segments stalled for the app's player (device
    // log 2026-09-26: Cronet SocketTimeoutException for 70 s) while the site's own first choice,
    // Tiktok, is the source that already played in the app.
    val cfNative = abs(o.optString("cfNative"))
    var cfNativeAdded = false
    for (kind in order) {
        if (kind == "Cloudflare" && cfNative != null) { out += SistennSource("cfNative", cfNative, false); cfNativeAdded = true }
        var url = abs(raw[kind] ?: "") ?: continue
        val adj = adjust?.optJSONObject(kind)
        if (adj?.optBoolean("disabled") == true) continue
        adj?.optJSONObject("params")?.let { p -> url = withParams(url, p.keys().asSequence().associateWith { p.optString(it) }) }
        val dom = adj?.optString("domain").orEmpty()
        if (dom.isNotEmpty() && url.contains("/hls/")) url = url.replaceFirst("/hls/", "/hlsmod/$dom/")
        out += SistennSource(kind, url, kind == "Tiktok" || kind == "Google" || url.contains("/hlsmod/"))
    }
    if (cfNative != null && !cfNativeAdded) out += SistennSource("cfNative", cfNative, false)
    return out
}
