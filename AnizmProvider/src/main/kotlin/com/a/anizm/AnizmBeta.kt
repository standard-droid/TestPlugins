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

// Beta Player (pl.puffytr.tr).

// Beta Player — puffytr.tr, unrelated to Aincrad. /stream/<hash>/master.txt is a standard HLS
// master; its /m3/<token> playlists expire after two hours, its segments are unsigned.
internal suspend fun AnizmProvider.processBeta(embed: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
    val hash = embed.removePrefix("bp:")
    log("beta: hash=$hash for $label")
    val betaOrigin = betaOrigins[hash] ?: "https://pl.puffytr.tr"
    val known = betaOrigin.endsWith("puffytr.tr")
    // v48: what the watch page's own player sends. The page sets <meta name="referrer"
    // content="no-referrer"> (seen 2026-11-10), so a browser sends no Referer for the master, the
    // playlists or the segments; this used to send the watch URL as Referer and nothing but a
    // user agent beside it. Checked in the page: all three answer without a Referer. The same
    // set goes on the link, so the app's player sends it too.
    val betaHeaders = clientHints + mapOf("User-Agent" to ua, "Accept" to "*/*", "Accept-Language" to acceptLang,
        "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Dest" to "empty")
    suspend fun fetch(u: String): String = try { app.get(u, headers = betaHeaders, timeout = 10L).text }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { log("beta: ${u.substringAfterLast('/')} fetch failed: ${e.message}"); "" }
    var masterUrl = "$betaOrigin/stream/$hash/master.txt"
    var body = fetch(masterUrl)
    if (!Hls.isPlaylist(body) && known) {
        // The master is not where it was: the watch page names it.
        val page = try {
            browserGet("$betaOrigin/watch/$hash", clientHints + mapOf("Upgrade-Insecure-Requests" to "1", "User-Agent" to ua,
                "Accept" to chromeDocAccept, "Sec-Fetch-Site" to "cross-site", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Dest" to "iframe",
                "Referer" to "$mainUrl/", "Accept-Language" to acceptLang), 8L).text
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "" }
        val named = betaMasterRe.find(page)?.groupValues?.get(1)?.replace("\\/", "/")?.let { Hls.resolve("$betaOrigin/", it) }
        if (named != null && named != masterUrl) {
            logW("site-change warning: beta: master.txt is not a playlist (${oneLine(body, 40)}); the watch page names ${named.substringAfter("//").take(80)} — using that")
            masterUrl = named
            body = fetch(masterUrl)
        }
    }
    if (!Hls.isPlaylist(body)) {
        if (known) logW("site-change warning: beta: ${masterUrl.substringAfter("//").substringBefore('/')} master.txt is not a playlist (${oneLine(body, 60)})")
        return false
    }
    val variants = parseVariants(body, masterUrl)
    if (variants.isEmpty()) { log("beta: no parseable variants in master.txt"); return false }
    log("beta: master lists ${variants.joinToString { "${it.height}p ${it.url.substringAfterLast('/').substringBefore('?').take(40)}${if (it.url.contains('?')) "?…" else ""}" }}")
    // One pass of reads gives the sizes and where the timestamps restart; kept per video, so
    // only the first load of a video makes them. Capped: if the segment host is slow, the
    // variants are listed as they are rather than held back.
    val key = "bp:$hash"
    val info = withTimeoutOrNull(12_000L) {
        analyseStream(key, variants, betaHeaders, checkJoins = true, wantSizes = estimateHlsSizes, tag = "beta")
    }
    if (info == null) log("beta: size and join check did not finish in 12 s — listing the variants as they are")
    return emitVariants(withJoinsMarked(variants, info, betaHeaders), cleanDisplayName(label), "", betaHeaders, callback, "beta", key,
        known = info ?: StreamInfo(emptyMap(), emptyMap()), probeWhole = true)
}

internal suspend fun AnizmProvider.withJoinsMarked(variants: List<Variant>, info: StreamInfo?, headers: Map<String, String>): List<Variant> = coroutineScope {
    variants.map { v -> async {
        val at = info?.joins?.get(v.height).orEmpty()
        if (at.isEmpty()) return@async v
        val fixed = try { Hls.parseMedia(playlistText(v.url, headers, 10L), v.url)?.let { Hls.withDiscontinuities(it, at) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { log("beta: could not mark the joins of ${v.height}p: ${e.message}"); null }
        val local = fixed?.let { localHls.register(v.url, "", headers, it) }
        if (local != null) { log("beta: ${v.height}p timestamps restart at segment(s) ${at.joinToString()} — marked as discontinuities"); v.copy(url = local) } else v
    } }.map { it.await() }
}
