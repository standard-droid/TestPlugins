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

// Aincrad (anizmplayer.com, a FirePlayer install).

// One function per player family: processSource as a single method outgrew ART's compiler
// limit ("Method exceeds compiler instruction limit" in the device log) and ran interpreted.
internal suspend fun AnizmProvider.processAincrad(embed: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
    val hash = embed.removePrefix("ap:")
    // 4.0: the real player page is /video/{hash} — that's where /player/{numId}
    // redirects. The old warm-up hit $playerBase/player/{hash}, which is a 404 on
    // anizmplayer.com (verified), so every Aincrad play started with a request
    // no browser ever makes. (v46: the page is no-referrer, so it is not a Referer either.)
    val apBase = apDomains[hash] ?: playerBase
    val playerRef = "$apBase/video/$hash"
    // v41: what the player page's own $.ajax sends (client hints, Sec-Fetch-*, language).
    // v46: and no Referer. The page sets <meta name="referrer" content="no-referrer">, so the
    // browser sends none on getVideo, the playlists or the segments (browser capture, 2026-11-03).
    val aHeaders = clientHints + mapOf("User-Agent" to ua,
        "X-Requested-With" to "XMLHttpRequest", "Accept" to "*/*",
        "Origin" to apBase, "Accept-Language" to acceptLang,
        "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Dest" to "empty")
    // The player page itself, loaded the way anizm's iframe loads it.
    val aPageHeaders = clientHints + mapOf("Upgrade-Insecure-Requests" to "1", "User-Agent" to ua, "Accept" to chromeDocAccept,
        "Sec-Fetch-Site" to "cross-site", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Dest" to "iframe",
        "Referer" to "$mainUrl/", "Accept-Language" to acceptLang)
    val now = System.currentTimeMillis()
    val cachedSource = aincradCache[hash]?.takeIf { it.validUntil > now }
    val videoSource: String
    val securedLink: String
    // v32: the player's session cookies. The device log (2026-09-26) had master.txt
    // answer this client with 200 "security error" while the browser, which sends the
    // cookies the player page and getVideo set, got the playlist. The app's HTTP client
    // keeps no cookie jar, so they are carried by hand, to getVideo and onto the link.
    val cookieJar = LinkedHashMap<String, String>()
    // 2026-11: which client got getVideo's answer; the playlist probe uses the same one, as a
    // browser would. (Not what caused the "security error" — that was the missing Accept, see hlsHeaders.)
    var viaChrome = false
    if (cachedSource != null) {
        videoSource = cachedSource.videoSource; securedLink = cachedSource.securedLink; viaChrome = cachedSource.viaChrome
        cachedSource.cookie.split("; ").filter { '=' in it }.forEach { cookieJar[it.substringBefore('=')] = it.substringAfter('=') }
        log("aincrad: reusing signed URL for $apBase $hash (${(cachedSource.validUntil - now) / 1000}s left)")
    } else {
        try { browserGet(playerRef, aPageHeaders, 8L).cookies.let { cookieJar.putAll(it) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {}
        // 4.0: FirePlayer's own call is $.ajax POST with data {hash: ID, r: document.referrer}.
        val streamResp = try {
            val gvUrl = "$apBase/player/index.php?data=$hash&do=getVideo"
            val gvHeaders = aHeaders + (if (cookieJar.isEmpty()) emptyMap() else mapOf("Cookie" to cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }))
            val gvData = mapOf("hash" to hash, "r" to "$mainUrl/")
            val viaCronet = chromeFetch(gvUrl, gvHeaders, 10L, formBody = gvData)
            viaChrome = viaCronet != null
            (viaCronet ?: app.post(gvUrl, headers = chromeOrderedMap(gvHeaders), data = gvData, timeout = 10))
                .also { cookieJar.putAll(it.cookies) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (e: Exception) { log("aincrad error: ${e.javaClass.simpleName}: ${e.message}"); return false }
        val streamText = streamResp.text
        // v31: these two exits used to be silent, so a changed answer from getVideo left
        // nothing in the log but a missing source. Say what came back instead.
        val json = try { JSONObject(streamText) } catch (_: Exception) {
            log("aincrad: getVideo not JSON (http ${streamResp.code}, ${streamResp.headers["Content-Type"]}, server=${streamResp.headers["server"]}, " +
                "cf-mitigated=${streamResp.headers["cf-mitigated"]}, final=${streamResp.url.take(80)}): ${oneLine(streamText, 200)}")
            return false
        }
        securedLink = json.optString("securedLink", "")
        videoSource = json.optString("videoSource", "")
        log("aincrad: hls=${json.optBoolean("hls")} secured=${securedLink.isNotBlank()} source=${videoSource.isNotBlank()} same=${securedLink == videoSource} dl=${json.optJSONArray("downloadLinks")?.length() ?: 0} " +
            "keys=${json.keys().asSequence().joinToString(",")} cookies=${cookieJar.keys.joinToString(",").ifEmpty { "none" }}")
        if (videoSource.isBlank() && securedLink.isBlank())
            log("aincrad: getVideo gave no link (http ${streamResp.code}), keys=${json.keys().asSequence().joinToString()}: ${oneLine(streamText, 200)}")
        else log("aincrad: link ${videoSource.ifBlank { securedLink }.substringBefore('?').take(120)}")
        // Valid until 2 min before the URL's own expiry (seconds or ms epoch), max 30 min;
        // 10 min if the URL doesn't say.
        // v32: securedLink carries the expiry; videoSource (master.txt) has none since they split.
        val exp = (expiresParamRe.find(securedLink) ?: expiresParamRe.find(videoSource))?.groupValues?.get(1)?.toLongOrNull()
            ?.let { if (it < 100_000_000_000L) it * 1000 else it }
        val until = minOf(exp?.minus(120_000) ?: (now + 10 * 60_000), now + 30 * 60_000)
        if (until > now && (videoSource.isNotBlank() || securedLink.isNotBlank())) {
            aincradCache[hash] = AincradSource(videoSource, securedLink, until,
                cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }, viaChrome)
            if (aincradCache.size > 200) aincradCache.entries.removeAll { it.value.validUntil <= now }
        }
    }
    // Single entry per source, chosen by CONTENT, not by JSON field name — see git
    // history for the 3003 error this avoids. NOTE ON DOWNLOADS: split-audio masters
    // stream fine but CloudStream's downloader can't mux them (app limitation).
    // 2026-11: Accept must be exactly "*/*" (what Chrome and hls.js send). Without it, or with any
    // other value, master.txt and the /m3/ media playlists answer 200 "security error"; neither
    // OkHttp nor Cronet adds an Accept on its own. Also reaches ExoPlayer through the link's headers.
    // v45: the rest of what the page's hls.js sends for its playlists (same-origin XHR): client hints,
    // language, Sec-Fetch-*, and no Origin (Chrome sends none on a same-origin GET). Checked on master.txt,
    // the /m3/ playlists and the anz-gth segment hosts, which ExoPlayer reaches with this same set.
    val hlsHeaders = clientHints + mapOf("User-Agent" to ua, "Accept" to "*/*", "Accept-Language" to acceptLang,
        "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Dest" to "empty") +
        (if (cookieJar.isEmpty()) emptyMap() else mapOf("Cookie" to cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }))

    var resolved = false
    // v45: videoSource (master.txt) only. securedLink, the md5/expires-signed master.m3u8 that v32
    // switched to, has been a flat nginx 403 since 2026-11 and the player page never requests it,
    // so asking for it only added a request no browser makes. Its expires= still sets the cache time above.
    run {
        val cand = videoSource.takeIf { it.isNotBlank() } ?: return@run
        // v45: a playlist is fetched whole, as the page does; Range only for something that may be a video file.
        val isPlaylist = cand.substringBefore('?').let { it.endsWith(".txt") || it.endsWith(".m3u8") }
        val probeHeaders = if (isPlaylist) hlsHeaders else hlsHeaders + mapOf("Range" to "bytes=0-4095")
        var probedVia = "okhttp"
        val head = try {
            // 2026-11: through the client that called getVideo (see viaChrome above).
            (if (viaChrome) chromeFetch(cand, probeHeaders, 8L)?.also { probedVia = "chrome" } else null)
                ?: app.get(cand, headers = probeHeaders, timeout = 8)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { log("aincrad: probe failed: ${e.message}"); return@run }
        val t = cappedText(head).trimStart()
        log("aincrad: probe ($probedVia, getVideo via ${if (viaChrome) "chrome" else "okhttp"}) http ${head.code}, ${head.headers["Content-Type"]}, ${t.length}B, starts '${t.take(24).replace('\n', ' ')}'")
        when {
            t.startsWith("#EXTM3U") -> {
                val cleanLabel = cleanDisplayName(label)
                val variants = parseVariants(t, cand)
                if (t.contains("TYPE=AUDIO") || variants.isEmpty()) {
                    // Split audio (every Aincrad sample checked, 2026-09) or an
                    // unparseable master: one master link, ExoPlayer does ABR + audio.
                    // 4.0: this branch never showed a size before. Now it estimates the
                    // top rendition (video + audio) by sampling real segment sizes.
                    val h = variants.maxOfOrNull { it.height } ?: Hls.maxHeight(t)
                    val sizeKey = "ap:$hash"
                    val est = if (estimateHlsSizes && variants.isNotEmpty()) {
                        cachedSizes(sizeKey)?.get(0)
                            ?: withTimeoutOrNull(sizeEstimateBudgetMs) { estimateSplitAudioBytes(sizeKey, t, cand, variants, hlsHeaders) }
                                ?.also { storeSizes(sizeKey, mapOf(0 to it)) }
                    } else null
                    // v46: "Always play the best quality": a master listing only the top variant (plus the audio
                    // group), served by the local proxy, so the app's player has nothing lower to switch to.
                    // Aincrad's labelled 1080p rate is fixed and often wrong (4096 kbps on every video, against
                    // 1.1-6.3 Mbps measured), which is exactly what an automatic switch decides by. Off, or if
                    // the proxy can't start: the full master.
                    val top = variants.firstOrNull()
                    // The local URL is reused while the master is unchanged, so a repeat load emits the same link
                    // and CloudStream de-duplicates it. Keyed by the master's text: its /m3/v1.<token> paths
                    // expire (same after 18 min, different and dead 2 days later), and a new text means new ones.
                    val lockKey = "$cand|${t.hashCode()}"
                    // v49: not when casting: the receiver can't reach this phone's local server (CastingLoad).
                    val playUrl = if (settings.lockTopQuality && top != null && variants.size > 1 && !isCastingLoad())
                        (aincradLocked[lockKey]?.takeIf { now - it.second < 5 * 60 * 60_000L }?.first
                            ?: Hls.topOnlyMaster(t, cand, top)?.let { localHls.register(cand, "", hlsHeaders, it) }
                                ?.also { aincradLocked[lockKey] = it to now; if (aincradLocked.size > 200) aincradLocked.entries.removeAll { e -> now - e.value.second > 5 * 60 * 60_000L } })
                            ?.also { log("aincrad: locked to ${top.height}p (${variants.size - 1} lower variant(s) left out)") } ?: cand
                    else cand
                    // v48: the quality is in the name, as it is for every other source ("… 1080p [~305MB]").
                    callback(newExtractorLink(source = cleanLabel, name = cleanLabel + (h?.let { " ${it}p" } ?: "") + formatSize(est, isEstimate = true), url = playUrl, type = ExtractorLinkType.M3U8) {
                        quality = h ?: Qualities.Unknown.value; referer = ""; headers = hlsHeaders }) // v46: no-referrer, as above
                    resolved = true
                } else {
                    resolved = emitVariants(variants, cleanLabel, "", hlsHeaders, callback, "aincrad", "ap:$hash")
                }
            }
            // v32: only a real media answer becomes a VIDEO link. A 14-byte text/html
            // "security error" used to land in the else branch and was listed as a video,
            // which ExoPlayer then failed on (UnrecognizedInputFormat, 3003).
            !looksLikeMedia(head, t) ->
                log("aincrad: candidate unusable (http ${head.code}, ${head.headers["Content-Type"]}): ${oneLine(t, 160)}")
            else -> {
                val cleanLabel = cleanDisplayName(label)
                val sizeBytes = parseContentRangeTotal(head.headers)
                callback(newExtractorLink(source = cleanLabel, name = cleanLabel + formatSize(sizeBytes), url = cand, type = ExtractorLinkType.VIDEO) {
                    quality = Qualities.Unknown.value; referer = ""; headers = hlsHeaders })
                resolved = true
            }
        }
    }
    return resolved
}
