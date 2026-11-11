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
import android.net.http.SslError
import android.os.Looper
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

// Players CloudStream has no extractor for: the page is loaded in a hidden WebView and the playlist it requests is taken.

// v38: split out of processSource, which had outgrown ART's compiler limit (17494 instructions,
// "Method exceeds compiler instruction limit" in the device log) and so always ran interpreted.
internal suspend fun AnizmProvider.processExtractorSource(
    vi: VidInfo, embed: String, data: String, label: String,
    callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit,
    deferSniff: ((VidInfo, String) -> Unit)?, sniffSpent: java.util.concurrent.atomic.AtomicLong, hops: Int = 0,
): Boolean {
    val exUrl = embed.removePrefix("ex:")
    log("step4: $label -> loadExtractor $exUrl")
    val creditTail = if (settings.showCredits) videoCredits[vi.numId]?.let { AnizmParser.creditsFor(it, vi.fansub) }
        ?.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty() else ""
    var found = false
    try {
        // Collect, then re-emit with the fansub in the name — otherwise these
        // links show only the extractor name ("Voe") with no fansub attribution
        val collected = java.util.concurrent.CopyOnWriteArrayList<ExtractorLink>()
        kotlinx.coroutines.withTimeoutOrNull(15_000) {
            loadExtractor(exUrl, data, subtitleCallback) { collected.add(it) }
        }
        for (l in collected) {
            // Some built-in extractors (StreamLare, Voe, etc.) already bake a
            // quality tag onto the end of their own .name — e.g. "Voe 1080p".
            // Strip it before appending ours below, or it shows as "Voe 1080p 1080p".
            val cleanName = cleanDisplayName(l.name)
            callback(newExtractorLink(source = "${vi.fansub} - $cleanName", name = "${vi.fansub} - $cleanName$creditTail", url = l.url, type = l.type) {
                referer = l.referer; quality = l.quality; headers = l.headers; extractorData = l.extractorData })
            found = true
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { log("step4: loadExtractor failed: ${e.message}") }

    // v16: CloudStream had no extractor for this host (or it produced nothing).
    // Load the player page in a hidden WebView and take the playlist it requests.
    // v37: Sistenn-family pages (sistenn.uns.bio, rpmvid, strp2p: https://host/#id) are asked
    // directly through their own API before any WebView is loaded — one to three small
    // requests instead of a full page with ads, taps and a 10-25 s wait.
    // v48: and before that, the hosts' resolver (see sistennResolve).
    if (!found) sistennPageRe.find(exUrl)?.let { m ->
        when (val res = sistennResolve(m.groupValues[1], m.groupValues[2])) {
            is SistennResolved.Url -> {
                val next = classifyEmbedUrl(res.url)
                log("sistenn: ${m.groupValues[1]} sends ${m.groupValues[2]} on to ${res.url.substringBefore('?').take(100)} (${next?.substringBefore(':') ?: "not a player address"})")
                return if (next != null && next != embed && hops < 2)
                    processSource(vi, next, data, callback, subtitleCallback, deferSniff, sniffSpent, hops + 1)
                else false
            }
            SistennResolved.Gone -> return false
            SistennResolved.NotOffered -> {}
        }
    }
    if (!found) {
        val direct = try { trySistennApi(exUrl, cleanDisplayName(label), callback) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { log("sistenn-api: error ${e.javaClass.simpleName}: ${e.message?.take(100)}"); SistennResult.UNAVAILABLE }
        when (direct) {
            SistennResult.EMITTED -> return true
            SistennResult.CAPACITY -> return false // the page would hit the same wall
            SistennResult.UNAVAILABLE -> {}
        }
    }
    if (!found && settings.browserSniff) {
        val cached = sniffCache[exUrl]?.takeIf { System.currentTimeMillis() - it.second < sniffCacheTtlMs }?.first
        if (cached != null) log("step4: reusing sniffed playlist for $label")
        if (cached == null && deferSniff != null) {
            log("step4: $label needs the browser — deferred until the cheap sources are done")
            deferSniff(vi, embed)
            return false
        }
        val overBudget = sniffSpent.get() >= sniffBudgetPerLoadMs
        val skip = cached == null && (sniffHostOnCooldown(exUrl) || overBudget)
        if (skip) log("step4: skipping sniff for $label (${if (overBudget) "episode sniff budget spent" else "host on cooldown"})")
        val sniffed = cached ?: if (skip) null else try {
            sniffGate.withPermit {
                // v33: another source (or load) may have sniffed this page while we waited.
                sniffCache[exUrl]?.takeIf { System.currentTimeMillis() - it.second < sniffCacheTtlMs }?.first
                    ?.also { log("step4: $label was sniffed meanwhile — reusing it") }
                    // v50: only this sniff's own time counts against the episode's budget. It used to
                    // include waiting at the gate for another load's sniff, so the second of two
                    // overlapping loads skipped all of its own.
                    // The Referer stays the site's origin, not the episode page: these players are on
                    // other domains, and Chrome's default policy (strict-origin-when-cross-origin)
                    // sends a cross-site frame only the origin — as Aincrad's document.referrer shows.
                    ?: run {
                        val t0 = System.currentTimeMillis()
                        sniffHlsViaWebView(exUrl, "$mainUrl/").also { sniffSpent.addAndGet(System.currentTimeMillis() - t0) }
                    }
            }
                .also { noteSniffResult(exUrl, it != null) }
        }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { log("step4: sniff failed: ${e.message}"); noteSniffResult(exUrl, false); null }
        if (sniffed != null) found = emitSniffed(exUrl, sniffed, label, callback)
    }
    return found
}

// v38: split out of processExtractorSource (compiler instruction limit, see processSource).
internal suspend fun AnizmProvider.emitSniffed(exUrl: String, sniffed: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
    var found = false
    sniffCache[exUrl] = sniffed to System.currentTimeMillis()
    if (sniffCache.size > 60) {
        val now = System.currentTimeMillis()
        sniffCache.entries.removeAll { now - it.value.second > sniffCacheTtlMs }
    }
    // v19: the player gets its own HTTP stack, so everything the page had must
    // be spelled out on the link — UA, Referer, Origin, and any cookie the
    // stream host handed the hidden WebView.
    val pageUrl = exUrl.substringBefore('#')
    val origin = try { java.net.URI(pageUrl).let { "${it.scheme}://${it.host}" } } catch (_: Exception) { pageUrl }
    val sniffHeaders = mutableMapOf("User-Agent" to ua, "Referer" to pageUrl, "Origin" to origin).also { h ->
        // v27: whatever the page's own player sent for the stream wins (custom
        // headers included). Cookies aren't in that list, so they're added after.
        sniffedHeaders[sniffed]?.forEach { (k, v) ->
            h.keys.firstOrNull { it.equals(k, true) }?.let { h.remove(it) }
            h[k] = v
        }
        (cookiesFor(sniffed) ?: cookiesFor(pageUrl))?.let { h["Cookie"] = it }
    }.toMap()
    log("sniff: link headers = ${sniffHeaders.keys.joinToString()}${sniffedHeaders[sniffed]?.let { " (copied from the page's player)" } ?: ""}")
    val fetched = try { app.get(sniffed, headers = sniffHeaders, timeout = 6L).text }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { log("step4: sniffed playlist fetch failed: ${e.message}"); "" }
    // v33: the host may ignore this client entirely; the page's own copy is as good.
    val body = if (fetched.trimStart().startsWith("#EXTM3U")) fetched
        else sniffedPlaylistTexts[sniffed.substringBefore('?')]?.also { log("sniff: using the master the page fetched itself") } ?: fetched
    val cleanLabel = cleanDisplayName(label)
    if (body.trimStart().startsWith("#EXTM3U")) {
        var variants = parseVariants(body, sniffed)
        var tokenQuery = ""
        // v29: if the page's player asked for its variant with a query string that
        // the master's own variant lines don't carry, the page's script added it —
        // most likely an access token. Put the same query on every variant.
        sniffedVariantUrls[sniffed]?.let { pv ->
            val q = pv.substringAfter('?', "")
            val sameFile = variants.firstOrNull { it.url.substringBefore('?') == pv.substringBefore('?') }
            log("sniff: master lists ${variants.joinToString { it.url.substringAfterLast('/').take(80) }}; player used ${pv.substringAfterLast('/').take(120)}" +
                (if (sameFile == null) " (not one of the listed variants)" else ""))
            if (q.isNotEmpty() && variants.none { it.url.contains('?') }) {
                variants = variants.map { it.copy(url = it.url + "?" + q) }
                tokenQuery = q
                log("sniff: copied the player's query onto the variants: ?${q.take(120)}")
            }
        }
        sniffedSegmentUrls[sniffed]?.let { log("sniff: player's first segment was ${it.substringAfterLast('/').take(160)}") }
        log("sniff: master has ${variants.size} variant(s): ${variants.joinToString(", ") { "${it.height}p/${(it.bandwidth ?: 0) / 1000}k" }}")
        // v28: the link's referer used to be exUrl — with its #fragment. CloudStream
        // sends that as the Referer header, which no browser ever does, and the
        // stream host answered the player with 403 (v27 log). Use the player's
        // origin, exactly what the page's own player sent.
        val linkReferer = sniffHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: "$origin/"
        // v28: find a header set the stream host accepts for a variant playlist
        // AND its first segment (the v27 log: segments 403 while playlists passed).
        val secFetch = mapOf("Sec-Fetch-Dest" to "empty", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Site" to "cross-site",
            "Accept-Language" to (try { java.util.Locale.getDefault().toLanguageTag() } catch (_: Throwable) { "en-US" }) + ",en;q=0.8")
        val minimal = mapOf("User-Agent" to ua, "Referer" to linkReferer, "Origin" to origin)
        // v48: two sets, not five: what the page's player sent, and the bare minimum. Walking a
        // host through five header variations in a row is itself an unusual thing to do.
        val candidates = listOf(
            "player + browser fetch headers" to sniffHeaders + secFetch,
            "minimal" to minimal,
        )
        // v34: token links go through the local playlist server and the player fetches
        // segments itself, so this client's view of the host decides nothing; probing
        // only cost time (8s timeouts in the device logs).
        val picked = if (tokenQuery.isNotEmpty()) null
            else variants.lastOrNull()?.let { pickStreamHeaders(it.url, candidates, tokenQuery) }
        val linkHeaders = picked ?: candidates.first().second
        if (picked == null && tokenQuery.isEmpty()) log("sniff: no header set got a segment from this app's client — listing the links anyway (the player's own network stack may still get through)")
        // v34: if the page could fetch some variants but not others, the missing ones are
        // the ones the host stalls (1080p index-f2 in every log so far): don't list them.
        // v35: "hlsmod" streams (Sistenn serving a TikTok-CDN stream from its own host) disguise
        // every segment as an image; the page's player strips that, ExoPlayer can't
        // (UnrecognizedInputFormat, device log 2026-09-26). Those go through the local
        // server, which fetches each segment and cuts it down to the real media.
        val hlsmod = sniffed.contains("/hlsmod/", ignoreCase = true)
        val useProxy = tokenQuery.isNotEmpty() || hlsmod
        val captured = variants.filter { sniffedPlaylistTexts.containsKey(it.url.substringBefore('?')) }
        if (useProxy && captured.isNotEmpty() && captured.size < variants.size) {
            log("sniff: leaving out ${variants.filter { it !in captured }.joinToString { "${it.height}p" }} — the page could not fetch it")
            variants = captured
        }
        // v35: and only the qualities the page's player actually got segments for. The stream
        // hosts stall 1080p (index-f2) segments for the app as well (v34 log: Cronet
        // SocketTimeoutException) while the page only ever played f1.
        val segsSeen = sniffedSegmentSets[sniffed].orEmpty()
        val played = variants.filter { v -> variantTagRe.find(v.url)?.groupValues?.get(1)?.let { t -> segsSeen.any { it.contains("-$t.") } } == true }
        if (useProxy && played.isNotEmpty() && played.size < variants.size) {
            log("sniff: leaving out ${variants.filter { it !in played }.joinToString { "${it.height}p" }} — the page's player never loaded its segments")
            variants = played
        }
        // v32: segments need the token too. The variant playlists list them relative
        // and without it, so ExoPlayer asked for bare init/seg URLs and got Cloudflare
        // 403s (device log 2026-09-26: 35s of buffering, no error). Serve the player a
        // rewritten copy of each variant from 127.0.0.1 in which every segment is an
        // absolute URL carrying the token; the segments themselves still come
        // straight from the stream host.
        // v35: with the page's refresh call known, segments are served as redirects that carry a
        // token the local server keeps fresh (the one in the playlist expires 30 min after the sniff).
        // v41: the page's own fetch headers for its refresh / playing call.
        fun refreshHeaders(ru: String) = clientHints + mapOf("User-Agent" to ua, "Accept" to "*/*",
            "Referer" to (try { java.net.URI(ru).let { "${it.scheme}://${it.host}/" } } catch (_: Exception) { "$origin/" }),
            "Accept-Language" to acceptLang,
            "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Dest" to "empty")
        val tokenState = if (tokenQuery.isNotEmpty() && !hlsmod) sniffedRefreshUrls[exUrl]?.let { ru ->
            LocalHlsServer.TokenState(tokenQuery, ru, refreshHeaders(ru))
        } else null
        val beat = sniffedRefreshUrls[exUrl]?.let { ru -> LocalHlsServer.Heartbeat(ru, refreshHeaders(ru), tokenState) }
        if (tokenState != null) log("sniff: token will be renewed through the page's refresh call when it nears expiry")
        val proxied = if (useProxy && variants.isNotEmpty())
            variants.mapNotNull { v -> localHls.register(v.url, tokenQuery, linkHeaders, sniffedPlaylistTexts[v.url.substringBefore('?')],
                stripSegments = hlsmod, token = tokenState, beat = beat)?.let { v to it } } else emptyList()
        if (proxied.isNotEmpty()) log("sniff: serving ${proxied.size} tokenised playlist(s) via ${proxied.first().second.substringBeforeLast('/')}, " +
            "${variants.count { sniffedPlaylistTexts.containsKey(it.url.substringBefore('?')) }} from the page's own fetch")
        found = if (proxied.isNotEmpty()) {
            for ((v, local) in proxied) callback(variantLink(v, cleanLabel, "", linkReferer, linkHeaders, url = local))
            true
        } else if (variants.isNotEmpty())
            emitVariants(variants, cleanLabel, linkReferer, linkHeaders, callback, "sniff",
                "sn:${sniffed.substringBefore('?').takeLast(40)}", allowDeclaredFallback = true,
                trustPlaylists = picked == null)
        else {
            callback(newExtractorLink(source = cleanLabel, name = cleanLabel, url = sniffed, type = ExtractorLinkType.M3U8) {
                quality = Qualities.Unknown.value; referer = linkReferer; headers = linkHeaders })
            true
        }
    } else {
        // v20: this used to hand the URL to the player anyway. That is how a dead
        // link ended up in the list — ExoPlayer got a 404 and the episode looked
        // broken. If we cannot read it as a playlist ourselves, neither can the
        // player, so drop it and let the next source have its turn.
        log("sniff: ${sniffed.substringBefore('?').takeLast(50)} is not a playlist (${body.take(40).replace('\n', ' ')}) — discarding")
        sniffCache.remove(exUrl)
    }
    return found
}

internal suspend fun AnizmProvider.sniffHlsViaWebView(pageUrl: String, referer: String, budgetMs: Long = 24_000L): String? =
    suspendCancellableCoroutine { cont ->
        val handler = android.os.Handler(Looper.getMainLooper())
        handler.post {
            val ctx = try { com.lagradost.cloudstream3.CloudStreamApp.context } catch (_: Throwable) { null }
            if (ctx == null) { log("sniff: no context"); if (cont.isActive) cont.resume(null); return@post }
            var done = false
            // Anything that could plausibly be the stream request, kept so a timeout says
            // *why* it failed instead of just "timeout".
            val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
            val decoysSeen = java.util.concurrent.atomic.AtomicInteger(0)
            // The ad stack is always blocked: the ads-blocked run (with real taps) got the
            // stream, the ads-allowed run never did (v25 log).
            val adsBlocked = java.util.concurrent.atomic.AtomicInteger(0)
            // v22: capped diagnostic lines per sniff (console errors, failed requests,
            // TLS refusals), so the next log says what the page tripped over.
            val diagLines = java.util.concurrent.atomic.AtomicInteger(0)
            val infoLogged = java.util.concurrent.atomic.AtomicBoolean(false) // page asked /api/v1/info (Sistenn family)
            val videoAsked = java.util.concurrent.atomic.AtomicBoolean(false)
            fun diag(msg: String) { if (diagLines.incrementAndGet() <= 30) log(msg) }
            fun isAdHost(h: String) = adHostKeywords.any { h.contains(it, true) } || imaHostKeywords.any { h.contains(it, true) }
            var reloadedForTls = false
            var syntheticTouch = false
            // v25: set below; lets a blocked pop-under schedule a follow-up tap.
            var tapAgain: (() -> Unit)? = null
            val pageHost = try { java.net.URI(pageUrl).host ?: "" } catch (_: Exception) { "" }
            val navsBlocked = java.util.concurrent.atomic.AtomicInteger(0)
            // v27: the master playlist, once seen. The page is then left running until its
            // player asks for a variant playlist, so we can copy exactly what it sent.
            val masterRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
            // v29: the first variant the player opened, then wait for its first segment.
            val variantRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val act = currentActivity()
            val wv = WebView(act ?: ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // With ads blocked nothing but our own muted play() can make sound, so allow it.
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                if (settings.userAgentString != ua) settings.userAgentString = ua
                // v25: no automatic window.open (pop-unders); with multiple windows off,
                // a gesture-driven one would load here and is caught by shouldOverrideUrlLoading.
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
            }
            // Give the offscreen WebView a real viewport. Without this it is 0x0, so
            // IntersectionObserver never fires and a lazy player never loads its source.
            try {
                wv.measure(
                    // v35: 1920x1080, not 1280x720. The page reports its size to /api/v1/video
                    // (w=,h=) and its player caps quality at the element size, so at 720 it never
                    // tried 1080p — which the variant filter would then read as "1080p broken".
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                wv.layout(0, 0, 1920, 1080)
            } catch (_: Throwable) {}
            // v21: actually attach it to the window. A detached WebView never gets vsync, so
            // requestAnimationFrame never fires and timers are throttled — enough to stall a
            // player (and the IMA SDK) forever, which matches the v20 log exactly. It goes in
            // behind the app's own UI, nearly transparent, and can't take touches or focus.
            var attachedTo: android.view.ViewGroup? = null
            try {
                val content = act?.findViewById<android.view.ViewGroup>(android.R.id.content)
                if (content != null) {
                    wv.alpha = 0.01f
                    wv.isFocusable = false
                    wv.isFocusableInTouchMode = false
                    wv.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    wv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    // v24: our own synthetic taps (see realTap) pass; real touches don't.
                    wv.setOnTouchListener { _, _ -> !syntheticTouch }
                    content.addView(wv, 0, android.widget.FrameLayout.LayoutParams(1920, 1080))
                    attachedTo = content
                }
            } catch (e: Throwable) { log("sniff: could not attach webview: ${e.message}") }
            log("sniff: webview ${if (attachedTo != null) "attached" else "detached"}")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

            fun finish(result: String?) {
                if (done) return
                done = true
                handler.removeCallbacksAndMessages(null)
                try { attachedTo?.removeView(wv) } catch (_: Throwable) {}
                try { wv.stopLoading(); wv.loadUrl("about:blank"); wv.destroy() } catch (_: Exception) {}
                if (cont.isActive) cont.resume(result)
            }
            // v33: before closing the page, have it fetch the master and variant playlists
            // itself (see sniffedPlaylistTexts). At most ~3.6s; finishes with m either way.
            var capturing = false
            fun finishCapturing(m: String) {
                if (done || capturing) return
                capturing = true
                val q = (variantRef.get() ?: m).substringAfter('?', "")
                try { wv.evaluateJavascript(playlistCaptureJs(m, q), null) } catch (_: Throwable) { finish(m); return }
                var polls = 0
                fun store(json: String, partial: Boolean) {
                    try {
                        val o = JSONObject(json)
                        if (sniffedPlaylistTexts.size > 40) sniffedPlaylistTexts.clear()
                        for (k in o.keys()) sniffedPlaylistTexts[k] = o.getString(k)
                        log("sniff: page fetched ${o.length()} playlist(s) itself${if (partial) " (the rest did not answer in time)" else ""}: " +
                            o.keys().asSequence().joinToString { it.substringAfterLast('/') })
                    } catch (e: Exception) { log("sniff: playlist capture unreadable: ${e.message}") }
                }
                fun poll() {
                    if (done) return
                    // v34: on the last poll take whatever arrived. v33 waited for ALL playlists,
                    // and one stalled 1080p variant meant nothing was kept, not even the 720p.
                    val last = ++polls >= 12
                    val js = if (last) "JSON.stringify(window.__anzPl || {})" else "window.__anzPlDone ? JSON.stringify(window.__anzPl) : ''"
                    try {
                        wv.evaluateJavascript(js) { r ->
                            val json = jsResult(r)
                            if (json.isNotEmpty()) { store(json, partial = last); finish(m) }
                            else if (last) { log("sniff: page fetched no playlists in time"); finish(m) }
                            else handler.postDelayed({ poll() }, 300L)
                        }
                    } catch (_: Throwable) { finish(m) }
                }
                handler.postDelayed({ poll() }, 300L)
            }
            cont.invokeOnCancellation { handler.post { finish(null) } }
            handler.postDelayed({
                val tail = synchronized(seen) { seen.takeLast(8).joinToString(" | ") }
                log("sniff: timeout for ${pageUrl.substringBefore('#')}; saw ${seen.size} candidate requests${if (tail.isEmpty()) "" else ": $tail"}" +
                    (if (adsBlocked.get() > 0) "; blocked ${adsBlocked.get()} ad requests" else "") +
                    (if (navsBlocked.get() > 0) "; blocked ${navsBlocked.get()} pop-under(s)" else ""))
                // One last look at the page before it is torn down (capped at 1.5s).
                handler.postDelayed({ finish(null) }, 1_500L)
                try {
                    wv.evaluateJavascript(stateJs) { r ->
                        if (!done) log("sniff: page state: ${jsResult(r)}")
                        finish(null)
                    }
                } catch (_: Throwable) { finish(null) }
            }, budgetMs)

            fun poke(view: WebView?, tag: String) {
                if (done) return
                try {
                    view?.evaluateJavascript(playClickJs) { r ->
                        val v = jsResult(r)
                        if (done) return@evaluateJavascript
                        if (v.startsWith("src:")) {
                            val src = v.removePrefix("src:")
                            val file = src.lowercase().substringBefore('?').substringAfterLast('/')
                            if (src.startsWith("http") && decoyPlaylistNames.none { file.startsWith(it) } && masterRef.get() == null) {
                                log("sniff: player holds ${src.substringBefore('?').takeLast(60)}")
                                finish(src)
                                return@evaluateJavascript
                            }
                        }
                        if (v.isNotEmpty() && v != "nothing") log("sniff: $tag -> $v")
                    }
                } catch (_: Exception) {}
            }

            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    val host = request.url?.host ?: ""
                    if (adHostKeywords.any { host.contains(it, ignoreCase = true) }) return emptyResponse()
                    // v24: Yandex Metrica. Its hosts fail TLS on this network for the app's
                    // client too (v22 log), so answer at once instead of a doomed handshake.
                    if (brokenTrackerHosts.any { host.contains(it, ignoreCase = true) }) return notFoundResponse()
                    if (imaHostKeywords.any { host.contains(it, ignoreCase = true) }) {
                        adsBlocked.incrementAndGet()
                        return notFoundResponse()
                    }
                    // The master playlist, whatever path it lives under. Some of these
                    // players serve it as .txt or with no extension at all.
                    val u = url.lowercase()
                    // v27: after the master, the first other request to the stream's host
                    // (variant playlist, whatever it is called — or a segment) is what the
                    // player sends for media. Its headers are the ones the link needs.
                    val m0 = masterRef.get()
                    val pathLc = (request.url?.path ?: "").lowercase()
                    // v35: when the stream is served from the page's own host (Sistenn "hlsmod"), that
                    // host also carries the page's API calls, icons and thumbnails — the v34 log took a
                    // PNG for the variant and /api/v1/player for a segment. Those are not media.
                    val notMedia = pathLc.contains("/api/") || pathLc.contains("/cdn-cgi/") || pathLc.endsWith(".ico") ||
                        pathLc.contains("thumbnail") || pathLc.endsWith(".js") || pathLc.endsWith(".css") || pathLc.endsWith(".html") ||
                        pathLc == "/" || pathLc.endsWith("/anizm.net")
                    if (m0 != null && url != m0 && !notMedia && host.isNotEmpty() && host == (try { java.net.URI(m0).host } catch (_: Exception) { null })) {
                        val kept = cleanCapturedHeaders(request.requestHeaders ?: emptyMap())
                        val looksPlaylist = url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".txt") }
                        if (!looksPlaylist) sniffedSegmentSets.getOrPut(m0) { java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>()) }
                            .let { if (it.size < 40) it.add(url.substringBefore('?')) }
                        // v29: v28's log had every header set refused on the variant playlist
                        // 200ms after the page's player opened the same one, while the master
                        // from the same host was fine. Headers aren't it, so log the FULL
                        // addresses (v27/v28 cut them at '?') — a token in the query is the
                        // likely difference — and wait for the first segment too.
                        if (looksPlaylist && variantRef.compareAndSet(null, url)) {
                            sniffedHeaders[m0] = kept
                            sniffedVariantUrls[m0] = url
                            log("sniff: player opened variant ${url.take(260)}")
                            log("sniff:   with headers: " + headersForLog(kept))
                            handler.postDelayed({ if (!done) { log("sniff: no segment request within 4s"); finishCapturing(m0) } }, 4_000L)
                        } else if (!looksPlaylist) {
                            if (sniffedSegmentUrls.put(m0, url) == null) {
                                log("sniff: player fetched segment ${url.take(260)}")
                                if (kept != sniffedHeaders[m0]) log("sniff:   segment headers: " + headersForLog(kept))
                                if (sniffedHeaders[m0] == null || variantRef.get() == null) sniffedHeaders[m0] = kept
                                // v35: a second more lets the player load its other playlists and a few
                                // more segments, which is what the variant filter below goes by.
                                handler.postDelayed({ finishCapturing(m0) }, 1_000L)
                            }
                        } else log("sniff: player opened another playlist ${url.take(200)}")
                        return null
                    }
                    // v26: the v25 log's real stream was …/v4/nc9/<id>/cf-master.1789396075.txt,
                    // which none of the old patterns matched — the player played it while the
                    // sniffer waited for its timeout. Match any master/playlist/index file
                    // ending .m3u8 or .txt, whatever prefix or stamp it carries.
                    val isPlaylist = u.contains(".m3u8") || u.contains("/master.") || u.contains("playlist.txt") ||
                        playlistFileRe.containsMatchIn(u.substringBefore('?').substringAfterLast('/')) ||
                        (u.contains("/hlsmod/") && u.contains("/tt/"))
                    if (isPlaylist) {
                        // v20: Sistenn's player attaches a placeholder source (…/preload.m3u8 on
                        // the page's own host) the moment it initialises, and only asks the API
                        // for the real stream afterwards. v18 took that first hit and killed the
                        // page 1.6s in, so the link in the list was a 404. Let the decoy through
                        // untouched (blocking it makes the player give up) and keep listening.
                        val file = u.substringBefore('?').substringAfterLast('/')
                        val decoy = decoyPlaylistNames.any { file.startsWith(it) }
                        if (decoy) {
                            if (decoysSeen.incrementAndGet() == 1) log("sniff: ignoring placeholder $file, waiting for the real one")
                            return null
                        }
                        // v27: the v26 log fetched the master fine but every variant came back
                        // 403 with our headers. So don't stop at the master: let the page's own
                        // player load it and then ask for one variant, record the headers it
                        // used, and give those to the link. At most 5s more.
                        val master = masterRef.get()
                        val reqHeaders = request.requestHeaders ?: emptyMap()
                        if (master == null) {
                            masterRef.set(url)
                            log("sniff: found ${url.take(260)} — waiting for the player to open a variant")
                            sniffedHeaders[url] = cleanCapturedHeaders(reqHeaders)
                            handler.postDelayed({ if (!done) { log("sniff: no variant request within 5s, using master headers"); finishCapturing(url) } }, 5_000L)
                            return null
                        }
                        if (url != master) {
                            val kept = cleanCapturedHeaders(reqHeaders)
                            sniffedHeaders[master] = kept
                            log("sniff: player opened variant ${url.substringBefore('?').takeLast(50)} with headers: " +
                                headersForLog(kept))
                            handler.post { finishCapturing(master) }
                        }
                        return null
                    }
                    // Media segments mean we missed the playlist — nothing useful to take.
                    if (u.contains(".ts?") || u.endsWith(".ts")) return emptyResponse()
                    // Diagnostics only: the handful of requests that could have been it.
                    if (u.contains("/api/") || u.contains("video") || u.contains(".mp4") || u.contains("stream"))
                        if (seen.size < 40) seen.add(url.substringBefore('?').takeLast(70))
                    // v34: the v24 diagnostic that re-fetched /api/v1/info through the app's client
                    // is gone (one extra request per sniff, and its answer is encrypted anyway).
                    // The flags drive the early give-up in onPageFinished.
                    if (u.contains("/api/v1/info")) infoLogged.set(true)
                    if (u.contains("/api/v1/video")) { videoAsked.set(true); log("sniff: page asked for the video: ${url.substringBefore('?').takeLast(60)}") }
                    // v35: the page's token refresh (window.__refreshPlayToken → /api/v1/player?t=…, every
                    // ~10s; answers {k, kx=now+1800}). Its t stays valid for hours without cookies, so
                    // the local server can renew the stream token itself mid-playback.
                    if (u.contains("/api/v1/player?t=") && sniffedRefreshUrls.put(pageUrl, url) == null)
                        log("sniff: captured the token refresh call (${url.substringBefore('?').takeLast(40)}, t=${url.substringAfter("t=").length} chars)")
                    // v22: a host the WebView refused on TLS goes through the app's client.
                    if (host in sslBadHosts && request.method.equals("GET", true))
                        return proxyViaApp(request) { diag(it) }
                    return null
                }

                // v25: the v24 log showed the first real tap replacing the player page with
                // another one (viewport 465 -> 980 wide, no media-player): an invisible ad
                // layer that opens a pop-under on the first click. In a browser that goes to
                // a new tab and the next click reaches the player; here it navigated the only
                // window away. Keep the main frame on the player's own site, then tap again.
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (request?.isForMainFrame != true) return false
                    val target = request.url?.host ?: return false
                    if (pageHost.isEmpty() || target.equals(pageHost, true) || target.endsWith(".$pageHost", true)) return false
                    val n = navsBlocked.incrementAndGet()
                    log("sniff: blocked navigation to $target (pop-under #$n)")
                    if (n <= 4) handler.postDelayed({ tapAgain?.invoke() }, 800L)
                    return true
                }

                // v22: default behaviour is to cancel silently. Still cancel (never proceed
                // on a bad certificate), but log it, remember the host, and reload once so
                // the page's requests to it can go through the app's client instead.
                override fun onReceivedSslError(view: WebView?, h: SslErrorHandler?, error: SslError?) {
                    try { h?.cancel() } catch (_: Throwable) {}
                    val badHost = try { android.net.Uri.parse(error?.url ?: "").host } catch (_: Throwable) { null } ?: return
                    // Ad hosts: nothing to rescue, and not worth routing through the app.
                    if (isAdHost(badHost)) { diag("sniff: TLS refused for ad host $badHost"); return }
                    val isNew = sslBadHosts.add(badHost)
                    diag("sniff: TLS refused for $badHost (${sslErrorName(error)})${if (isNew) " — will route it through the app" else ""}")
                    if (isNew && !reloadedForTls && !done) {
                        reloadedForTls = true
                        handler.postDelayed({
                            if (!done) { log("sniff: reloading page once with $badHost routed through the app"); try { view?.reload() } catch (_: Throwable) {} }
                        }, 300L)
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    val h = request?.url?.host ?: return
                    if (isAdHost(h)) return
                    diag("sniff: request failed ${h}${request.url?.path?.take(50) ?: ""}: ${error?.errorCode} ${error?.description}")
                    // v33: the player page itself did not load (DNS, refused, timeout): stop now.
                    if (request.isForMainFrame && !done && h.equals(pageHost, true)) {
                        parkSniffHost(pageUrl, "${error?.description}")
                        handler.post { finish(null) }
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    val h = request?.url?.host ?: return
                    if (isAdHost(h)) return
                    diag("sniff: http ${errorResponse?.statusCode} for ${h}${request.url?.path?.take(50) ?: ""}")
                    // v33: the player page itself answered 5xx (rpmvid/strp2p: 530 + Cloudflare
                    // "Error 1016", device logs 2026-09-26). Each of those used to sit out the
                    // whole 24s budget before giving up.
                    val code = errorResponse?.statusCode ?: 0
                    if (request.isForMainFrame && code >= 500 && !done && h.equals(pageHost, true)) {
                        parkSniffHost(pageUrl, "http $code")
                        handler.post { finish(null) }
                    }
                    // v36: the stream's own master playlist answered 5xx (rpmvid hlsmod: 530 on the
                    // master and every segment, device log 2026-09-26) — the page loaded, the stream is
                    // down. Stop now instead of waiting out the budget; park the host only when the
                    // stream lives on the page's own host (a separate stream host rotates).
                    val master = masterRef.get()
                    if (master != null && code >= 500 && !done && request.url?.toString() == master) {
                        if (h.equals(pageHost, true)) parkSniffHost(pageUrl, "stream master http $code")
                        else log("sniff: stream master answered http $code — giving up on this one")
                        handler.post { finish(null) }
                    }
                    // 2026-11: the page's own video request was refused (400 "player is not recognized"
                    // on every Sistenn host, device logs 2026-11-01). The page never asks again, so each
                    // one sat out the whole budget (~20 s per source, ~1 min per episode). Stop now.
                    if (!done && code in 400..499 && code != 429 && h.equals(pageHost, true) &&
                        request.url?.path?.startsWith("/api/v1/video") == true) {
                        log("sniff: the page's video request was refused (http $code) — giving up on this one")
                        handler.post { finish(null) }
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    try { view?.evaluateJavascript(docStartJs, null) } catch (_: Throwable) {}
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (done) return
                    log("sniff: page ready")
                    poke(view, "poke@ready")
                    // v34: a Sistenn-family page (it loads /api/v1/info) asks /api/v1/video about
                    // 3-4s after load once tapped. strp2p's page loaded, never asked, and sat out
                    // the full 24s budget (device log 2026-09-26). Give such a page 12s.
                    handler.postDelayed({
                        if (!done && infoLogged.get() && !videoAsked.get() && masterRef.get() == null) {
                            log("sniff: page never asked for a video within 12s of loading — giving up")
                            finish(null)
                        }
                    }, 12_000L)
                }
            }
            // v22: console errors from the page (a thrown exception in the key/decrypt step
            // would otherwise be invisible).
            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                    if (m == null) return true
                    val lvl = m.messageLevel()
                    if (lvl == ConsoleMessage.MessageLevel.ERROR || lvl == ConsoleMessage.MessageLevel.WARNING) {
                        val src = try { android.net.Uri.parse(m.sourceId() ?: "").let { "${it.host}${it.path?.takeLast(30) ?: ""}" } } catch (_: Throwable) { "" }
                        diag("sniff: console ${lvl.name.lowercase()} $src:${m.lineNumber()} ${m.message().take(160)}")
                    }
                    return true
                }
            }
            // v22: log the full URL. The id is in the #fragment and the page needs it; v21
            // trimmed it from this line only, which made the log look like it was dropped.
            log("sniff: loading $pageUrl${if (sslBadHosts.isNotEmpty()) " (via app: ${sslBadHosts.joinToString()})" else ""}")
            wv.loadUrl(pageUrl, mapOf("Referer" to referer))
            // Fixed timeline, not tied to onPageFinished: with ad scripts in the page that
            // callback can arrive after the whole budget has run out.
            // v24: a real tap. JS click() is an untrusted event; if the player only asks
            // for the video on a genuine user gesture, this is what it's waiting for.
            // Aimed at the centre (vidstack's big play button), then a little off-centre
            // in case the centre is covered.
            fun realTap(tag: String, fx: Float, fy: Float) {
                if (done || masterRef.get() != null) return
                try {
                    val x = wv.width * fx; val y = wv.height * fy
                    if (wv.width <= 0 || wv.height <= 0) return
                    val t = android.os.SystemClock.uptimeMillis()
                    val down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                    val up = android.view.MotionEvent.obtain(t, t + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
                    syntheticTouch = true
                    try { wv.dispatchTouchEvent(down); wv.dispatchTouchEvent(up) } finally { syntheticTouch = false }
                    down.recycle(); up.recycle()
                    log("sniff: $tag real tap at ${x.toInt()},${y.toInt()}")
                    // v27: the page now plays for a moment while we wait for a variant;
                    // a real tap counts as a gesture, so make sure it plays silently.
                    try { wv.evaluateJavascript(muteJs, null) } catch (_: Throwable) {}
                    handler.postDelayed({ if (!done) try { wv.evaluateJavascript(muteJs, null) } catch (_: Throwable) {} }, 400L)
                } catch (e: Throwable) { log("sniff: tap failed: ${e.message}") }
            }
            tapAgain = { realTap("tap after pop-under", 0.5f, 0.5f) }
            handler.postDelayed({ realTap("tap@2s", 0.5f, 0.5f) }, 2_000L)
            handler.postDelayed({ realTap("tap@5s", 0.5f, 0.5f) }, 5_000L)
            handler.postDelayed({ realTap("tap@9s", 0.5f, 0.6f) }, 9_000L)
            handler.postDelayed({ realTap("tap@14s", 0.5f, 0.5f) }, 14_000L)
            for (delay in listOf(1_500L, 3_000L, 5_000L, 7_500L, 10_000L, 13_000L, 17_000L, 21_000L)) {
                handler.postDelayed({ poke(wv, "poke@${delay / 1000}s") }, delay)
            }
        }
    }

/**
 * Fetch one sniffer GET through the app's HTTP client (runs on the WebView's IO thread,
 * so blocking is fine). Returns null — i.e. "let the WebView try itself" — on any failure.
 */
internal fun AnizmProvider.proxyViaApp(req: WebResourceRequest, note: (String) -> Unit): WebResourceResponse? {
    val url = req.url?.toString() ?: return null
    val host = req.url?.host ?: ""
    return try {
        val h = HashMap<String, String>(req.requestHeaders ?: emptyMap())
        h["User-Agent"] = ua
        cookiesFor(url)?.let { h["Cookie"] = it }
        val r = runBlocking { app.get(url, headers = h, timeout = 10L) }
        val code = r.code
        // The app client follows redirects; a 3xx here or a 1xx can't be expressed in a
        // WebResourceResponse, so hand those back to the WebView.
        if (code < 200 || code in 300..399) { note("sniff: via app $host -> http $code, giving it back"); return null }
        val ct = r.headers["Content-Type"] ?: "application/octet-stream"
        val mime = ct.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
        val charset = charsetRe.find(ct)?.groupValues?.get(1)
        val bytes = bodyBytes(r) ?: ByteArray(0)
        val headers = LinkedHashMap<String, String>()
        for (name in r.okhttpResponse.headers.names()) {
            // Already decoded by the client; a stale length/encoding would corrupt the body.
            if (name.equals("content-encoding", true) || name.equals("content-length", true) ||
                name.equals("transfer-encoding", true)) continue
            if (name.equals("set-cookie", true)) {
                r.okhttpResponse.headers.values(name).forEach { c ->
                    try { CookieManager.getInstance().setCookie(url, c) } catch (_: Throwable) {}
                }
                continue
            }
            headers[name] = r.okhttpResponse.headers.values(name).joinToString(", ")
        }
        note("sniff: via app $host${req.url?.path?.take(40) ?: ""} -> http $code, ${bytes.size}B $mime")
        val reason = r.okhttpResponse.message.ifBlank { if (code < 400) "OK" else "Error" }
        WebResourceResponse(mime, charset, code, reason, headers, ByteArrayInputStream(bytes))
    } catch (e: Throwable) {
        // If the app's client fails too, that says the host itself is broken (not just
        // the WebView's view of it) — which is worth knowing for the next step.
        note("sniff: via app $host failed too: ${e.javaClass.simpleName}: ${e.message?.take(90)}")
        null
    }
}
