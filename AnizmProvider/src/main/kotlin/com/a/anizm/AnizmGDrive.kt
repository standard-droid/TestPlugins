package com.a.anizm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.serialization.Serializable
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

// Google Drive: the preview player's streaming links first, the download route as a fallback.

internal suspend fun AnizmProvider.processGDrive(embed: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
    val fileId = embed.removePrefix("gd:")
    log("gdrive: fileId=$fileId for $label")
    // v35: stream first, the way Drive's own embedded player does. The download route below
    // hits the file's download quota ("Quota exceeded") long before the streaming one runs
    // out; the website kept playing files the app could not (2026-09-26).
    val streams = try { gdriveGate.withPermit { resolveGDriveStreams(fileId) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { log("gdrive: stream lookup error: ${e.message}"); emptyList() }
    val cleanLabel = cleanDisplayName(label)
    // v56: the uploaded file itself, as "<label> Original <height>p [size]". Google's streams are its own
    // re-encodes, far smaller than the upload (Kusuriya S3 E7: 1080p stream 184 MB, about 1 Mbps).
    suspend fun emitOriginal(res: GDriveResolution) =
        callback(newExtractorLink(source = cleanLabel, name = "$cleanLabel Original" + (res.height?.let { " ${it}p" } ?: "") + formatSize(res.sizeBytes),
            url = res.url, type = ExtractorLinkType.VIDEO) {
            quality = res.height ?: Qualities.Unknown.value; referer = "https://drive.google.com/"; headers = res.headers })
    if (streams.isNotEmpty()) {
        for (st in streams) callback(newExtractorLink(source = cleanLabel, name = "$cleanLabel ${st.height}p" + formatSize(st.sizeBytes),
            url = st.url, type = ExtractorLinkType.VIDEO) {
            quality = st.height; referer = "https://drive.google.com/"; headers = mapOf("User-Agent" to ua) })
        // Next to the streams, when the setting is on: the streams are listed first and stay whatever
        // happens here, and the lookup is capped so a slow download route can't hold the episode up.
        if (settings.driveOriginal) withTimeoutOrNull(driveOriginalBudgetMs) { driveOriginal(fileId) }
            ?.let { emitOriginal(it) } ?: log("gdrive: no original-file link for $fileId this time")
        return true
    }
    // No streams: the original is the only way left (whatever the setting says).
    val resolved = driveOriginal(fileId) ?: run { log("gdrive: could not resolve a playable link for $fileId"); return false }
    emitOriginal(resolved)
    return true
}

/**
 * The uploaded file through Drive's download route, checked with a second small read (see
 * [gdriveReusable]). Kept [driveOriginalTtlMs] (a miss for a shorter while), so CloudStream's double
 * call and the next few loads ask Google once. Null when the file's download quota is used up.
 */
internal suspend fun AnizmProvider.driveOriginal(fileId: String): GDriveResolution? {
    val now = System.currentTimeMillis()
    driveOriginalCache[fileId]?.let { (res, at) ->
        if (now - at < (if (res != null) driveOriginalTtlMs else driveOriginalMissTtlMs)) { log("gdrive: reusing original-file lookup for $fileId"); return res }
    }
    gdriveQuotaUntil[fileId]?.let { until ->
        if (now < until) {
            log("gdrive: $fileId hit its download quota recently — not asking Google again for ${(until - now) / 60000} min")
            return null
        }
    }
    val resolved = try {
        gdriveGate.withPermit { resolveGDrive(fileId) }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (e: Exception) { log("gdrive: resolve error: ${e.message}"); null }
    val ok = resolved?.takeIf { gdriveReusable(it, fileId) }
    driveOriginalCache[fileId] = ok to System.currentTimeMillis()
    if (driveOriginalCache.size > 100) driveOriginalCache.entries.removeAll { now - it.value.second > driveOriginalTtlMs }
    return ok
}

internal suspend fun AnizmProvider.resolveGDriveStreams(fileId: String): List<DriveStream> {
    val now = System.currentTimeMillis()
    driveStreamCache[fileId]?.takeIf { now - it.second < driveStreamTtlMs }?.let { log("gdrive: reusing stream links for $fileId"); return it.first }
    driveStreamQuotaUntil[fileId]?.takeIf { now < it }?.let { log("gdrive: $fileId is over its streaming limit (recently) — skipping the stream lookup"); return emptyList() }
    val keys = driveApiKey?.let { listOf(it) } ?: run {
        // v41: the preview as anizm's player frame opens it (Chrome headers and network stack).
        val html = try { browserGet("https://drive.google.com/file/d/$fileId/preview", clientHints + mapOf("Upgrade-Insecure-Requests" to "1",
            "User-Agent" to ua, "Accept" to chromeDocAccept, "Sec-Fetch-Site" to "cross-site", "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Dest" to "iframe", "Referer" to "$mainUrl/", "Accept-Language" to acceptLang), 12L).text }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "" }
        driveKeyRe.findAll(html).map { it.value }.distinct().toList().also { log("gdrive: preview page has ${it.size} API key candidate(s)") }
    }
    if (keys.isEmpty()) return emptyList()
    // v41: the preview page's own fetch to the playback API (a different site: googleapis.com).
    val apiHeaders = clientHints + mapOf("User-Agent" to ua, "Origin" to "https://drive.google.com", "Referer" to "https://drive.google.com/",
        "Accept" to "*/*", "Accept-Language" to acceptLang,
        "Sec-Fetch-Site" to "cross-site", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Dest" to "empty")
    var body: String? = null
    for (key in keys.take(6)) {
        val r = try { browserGet("https://content-workspacevideo-pa.googleapis.com/v1/drive/media/$fileId/playback?key=$key&auditContext=forDisplay",
            apiHeaders, 12L) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { log("gdrive: playback API error: ${e.message}"); return emptyList() }
        when (r.code) {
            200 -> { body = r.text; driveApiKey = key; break }
            429 -> {
                log("gdrive: $fileId is over its streaming limit (429): ${oneLine(r.text, 120)}")
                driveStreamQuotaUntil[fileId] = now + 60 * 60 * 1000L
                return emptyList()
            }
            else -> {
                log("gdrive: playback API refused key #${keys.indexOf(key) + 1} (http ${r.code}): ${oneLine(r.text, 120)}")
                if (driveApiKey == key) driveApiKey = null
            }
        }
    }
    val playback = tryParseJson<DrivePlayback>(body) ?: return emptyList()
    val media = playback.mediaStreamingData
    val progressive = media?.formatStreamingData?.progressiveTranscodes
        ?: run { log("gdrive: playback answer has no progressive formats (state=${media?.transcodeAvailabilityState?.state})"); return emptyList() }
    val found = progressive.mapNotNull { o ->
        val url = o.url.nb() ?: return@mapNotNull null
        val h = o.transcodeMetadata?.height?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        h to url
    }.distinctBy { it.first }.sortedByDescending { it.first }
    // Check each link answers with media (one byte) and take its real size from Content-Range;
    // the API's own contentLength for these is wrong (~400 KB for a 23-min file).
    val checked = coroutineScope {
        found.map { (h, url) ->
            async {
                try {
                    val r = app.get(url, headers = mapOf("User-Agent" to ua, "Range" to "bytes=0-0"), timeout = 10L)
                    val size = r.headers["Content-Range"]?.substringAfterLast('/')?.trim()?.toLongOrNull()
                    val ok = r.code == 206 || r.code == 200
                    r.closeQuietly()
                    if (ok) DriveStream(h, url, size) else { log("gdrive: ${h}p stream answered http ${r.code}"); null }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { log("gdrive: ${h}p stream check failed: ${e.message}"); null }
            }
        }.mapNotNull { it.await() }
    }
    log("gdrive: streaming ${checked.joinToString { "${it.height}p" + formatSize(it.sizeBytes) }} for $fileId (of ${found.size} offered)")
    if (checked.isNotEmpty()) driveStreamCache[fileId] = checked to now
    if (driveStreamCache.size > 100) driveStreamCache.entries.removeAll { now - it.value.second > driveStreamTtlMs }
    return checked
}

internal suspend fun AnizmProvider.gdriveReusable(res: GDriveResolution, fileId: String): Boolean {
    return try {
        // v48: the first 512 bytes, not an open-ended "bytes=0-": that started a whole download
        // (which is what counts against the file's quota) only to read its first bytes.
        val r = app.get(res.url, headers = res.headers + mapOf("Range" to "bytes=0-511"), timeout = 15)
        val ct = r.headers["Content-Type"] ?: "?"
        val head = try { r.okhttpResponse.body?.byteStream()?.use { s -> ByteArray(512).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } } } catch (_: Exception) { null } ?: ByteArray(0)
        r.closeQuietly()
        val html = ct.contains("text/html", true) || String(head, Charsets.ISO_8859_1).trimStart().startsWith("<")
        if (html || r.code !in 200..299) {
            val page = String(head, Charsets.UTF_8)
            val title = htmlTitleRe.find(page)?.groupValues?.get(1)?.trim()
            logW("gdrive: resolved link is not reusable — second request got http ${r.code}, $ct, title='${title ?: "-"}': ${oneLine(page, 160)}")
            if (title?.contains("quota", ignoreCase = true) == true || page.contains("Quota exceeded", ignoreCase = true))
                gdriveQuotaUntil[fileId] = System.currentTimeMillis() + gdriveQuotaBackoffMs
            false
        } else {
            log("gdrive: second request ok (http ${r.code}, $ct, ${r.headers["Content-Range"] ?: r.headers["Content-Length"] ?: "?"}, " +
                "magic ${head.take(8).joinToString("") { "%02x".format(it) }})")
            true
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { log("gdrive: second request failed (${e.message}), listing the link anyway"); true }
}

internal suspend fun AnizmProvider.resolveGDrive(fileId: String): GDriveResolution? {
    // 64KB: covers Google's whole interstitial page (confirm/uuid sit well past 2KB), and
    // for a direct video response it's enough to hold a faststart MP4's track headers.
    val h = mapOf("User-Agent" to ua, "Range" to "bytes=0-65535")
    val base = "https://drive.usercontent.google.com/download?id=$fileId&export=download"
    val r1 = app.get(base, headers = h, timeout = 15)
    if (!isHtml(r1)) return gdriveFromMedia(r1, emptyMap(), null, null, "direct")

    val html = r1.text
    val gdoc = org.jsoup.Jsoup.parse(html, r1.url)
    // Interstitial reads like: "<a>[Fansub] Show - 01 [1080p].mp4</a> (1.4G) is too large…"
    val ucNameSize = gdoc.selectFirst(".uc-name-size")
    val pageName = ucNameSize?.selectFirst("a")?.text()?.ifBlank { null }
    val pageSize = ucNameSize?.text()?.let { ucSizeRe.find(it) }?.let { m ->
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let null
        val mult = when (m.groupValues[2].uppercase()) { "K" -> 1L shl 10; "M" -> 1L shl 20; "G" -> 1L shl 30; else -> 1L shl 40 }
        (n * mult).toLong()
    }
    val cookies1 = r1.cookies

    // v6: submit Google's download form as-is (every hidden input, to the form's own
    // action) instead of hand-picking confirm + uuid. v5 required an input literally named
    // "confirm" and gave up otherwise — and on your device every Drive file (4 files,
    // 2 fansubs) hit "gdrive confirm missing", so the page no longer has that exact shape.
    val form = gdoc.selectFirst("form#download-form")
        ?: gdoc.select("form").firstOrNull { it.attr("action").contains("download") || it.selectFirst("input[name=id]") != null }
    if (form != null) {
        val action = form.attr("abs:action").ifBlank { "https://drive.usercontent.google.com/download" }
        val params = form.select("input[name]").associate { it.attr("name") to it.attr("value") }
            .let { if ("id" !in it) it + ("id" to fileId) else it }
        val query = params.entries.joinToString("&") { "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val url = if (action.contains('?')) "$action&$query" else "$action?$query"
        val cookieHeader = cookies1.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val r2 = app.get(url, headers = h + (if (cookieHeader.isNotBlank()) mapOf("Cookie" to cookieHeader) else emptyMap()), timeout = 15)
        if (!isHtml(r2)) return gdriveFromMedia(r2, cookies1, pageName, pageSize, "form: ${params.keys.joinToString(",")}")
        log("gdrive: still HTML after submitting form (${params.keys.joinToString(",")})")
    }

    // Last try: the old confirm=t shortcut, which still works for some files.
    val r3 = app.get("$base&confirm=t", headers = h, timeout = 15)
    if (!isHtml(r3)) return gdriveFromMedia(r3, cookies1, pageName, pageSize, "confirm=t")

    // Nothing worked — say what Google actually sent, so the next log explains it.
    val title = gdoc.title().take(80)
    val reason = when {
        html.contains("quota", ignoreCase = true) -> "download quota exceeded for this file"
        html.contains("accounts.google.com", ignoreCase = true) && form == null -> "sign-in / no access"
        r1.code == 404 || html.contains("404", ignoreCase = false) && title.contains("404") -> "file removed"
        html.contains("/sorry/", ignoreCase = true) || html.contains("unusual traffic", ignoreCase = true) -> "Google rate-limited this IP"
        else -> "unknown page"
    }
    logW("gdrive: $fileId unresolvable — $reason (http ${r1.code}, title='$title', forms=${gdoc.select("form").size}, inputs=${gdoc.select("input[name]").joinToString(",") { it.attr("name") }})")
    return null
}

// Finishes a resolve once we have a response that IS the video (not an HTML page).
internal suspend fun AnizmProvider.gdriveFromMedia(r: NiceResponse, earlierCookies: Map<String, String>, nameHint: String?, sizeHint: Long?, how: String): GDriveResolution {
    val allCookies = (earlierCookies + r.cookies).entries.joinToString("; ") { "${it.key}=${it.value}" }
    val headers = mapOf("User-Agent" to ua) + (if (allCookies.isNotBlank()) mapOf("Cookie" to allCookies) else emptyMap())
    val size = sizeFromRanged(r) ?: sizeHint
    val bytes = if (r.code == 206) bodyBytes(r) else { r.closeQuietly(); null }
    val height = gdriveHeight(filenameFromDisposition(r.headers) ?: nameHint, bytes, r.url, headers, size)
    log("gdrive: resolved ($how), size=$size height=$height")
    return GDriveResolution(r.url, headers, size, height)
}

internal suspend fun AnizmProvider.gdriveHeight(name: String?, firstBytes: ByteArray?, url: String, headers: Map<String, String>, total: Long?): Int? {
    heightFromName(name)?.let { log("gdrive: ${it}p from file name"); return it }
    val bytes = firstBytes ?: return null
    val p = probeMp4(bytes)
    if (p is Mp4Probe.Height) { log("gdrive: ${p.height}p from MP4 header"); return p.height }
    if (p !is Mp4Probe.MoovAt) return null
    if (total != null && p.offset >= total) return null
    val tail = try {
        val r = app.get(url, headers = headers + mapOf("Range" to "bytes=${p.offset}-${p.offset + 65_535}"), timeout = 10)
        if (r.code == 206) bodyBytes(r) else { r.closeQuietly(); null }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } ?: return null
    val h = (probeMp4(tail, p.offset) as? Mp4Probe.Height)?.height
    log("gdrive: ${h?.let { "${it}p" } ?: "no height"} from moov at end")
    return h
}

/** Walks top-level boxes of the bytes starting at file offset `base`. */
internal fun AnizmProvider.probeMp4(b: ByteArray, base: Long = 0L): Mp4Probe {
    var o = 0
    while (o + 8 <= b.size) {
        var size = be32(b, o); val type = boxType(b, o + 4); var header = 8
        if (size == 1L) { if (o + 16 > b.size) break; size = be64(b, o + 8); header = 16 }
        val toEof = size == 0L // box runs to end of file
        if (!toEof && size < header) break
        if (base == 0L && o == 0 && type != "ftyp") return Mp4Probe.Unknown // not an MP4 (e.g. MKV)
        val fits = !toEof && o.toLong() + size <= b.size
        if (type == "moov") {
            val end = if (fits) (o + size).toInt() else b.size
            findVideoHeight(b, o + header, end)?.let { return Mp4Probe.Height(it) }
            return Mp4Probe.Unknown
        }
        if (!fits) {
            // Box extends past what we have (normally mdat). For a non-faststart file,
            // moov follows it (possibly after a small free box).
            return if (type == "mdat" && !toEof) Mp4Probe.MoovAt(base + o + size, -1) else Mp4Probe.Unknown
        }
        o += size.toInt()
    }
    return Mp4Probe.Unknown
}

internal fun AnizmProvider.findVideoHeight(b: ByteArray, start: Int, end: Int): Int? {
    var o = start
    while (o + 8 <= end) {
        val size = be32(b, o).toInt(); val type = boxType(b, o + 4)
        if (size < 8) return null
        val boxEnd = minOf(o + size, end)
        when (type) {
            "trak", "moov" -> findVideoHeight(b, o + 8, boxEnd)?.let { return it }
            "tkhd" -> {
                val version = b.getOrNull(o + 8)?.toInt() ?: return null
                val hOff = o + 8 + (if (version == 1) 88 else 76) + 4 // width, then height (16.16)
                if (hOff + 4 <= boxEnd) {
                    val h = (be32(b, hOff) shr 16).toInt()
                    if (h in 100..4400) return h // audio tracks have 0
                }
            }
        }
        o += size
    }
    return null
}

// ── Drive's playback API answer (only the fields read) ──────────────────────
@Serializable
internal data class DrivePlayback(val mediaStreamingData: DriveMediaStreaming? = null)
@Serializable
internal data class DriveMediaStreaming(
    val formatStreamingData: DriveFormats? = null,
    val transcodeAvailabilityState: DriveState? = null,
)
@Serializable
internal data class DriveFormats(val progressiveTranscodes: List<DriveTranscode>? = null)
@Serializable
internal data class DriveTranscode(val url: Str? = null, val transcodeMetadata: DriveTranscodeMeta? = null)
@Serializable
internal data class DriveTranscodeMeta(val height: Str? = null)
@Serializable
internal data class DriveState(val state: Str? = null)
