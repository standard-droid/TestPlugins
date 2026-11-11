package com.a.anizm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
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

// Sizes and timestamp joins: ranged reads of a few segments per video. See the notes in AnizmProvider (mediaStore).

/**
 * One ranged read of a segment: its full size from the answer's headers (206 Content-Range total,
 * or Content-Length where the server ignores Range) and, with [headBytes] > 1, the first
 * timestamp in its head. HEAD is not usable: the segment hosts leave the length out of it.
 */
internal suspend fun AnizmProvider.readSegment(url: String, headers: Map<String, String>, headBytes: Int): SegRead? {
    val r = app.get(url, headers = headers + mapOf("Range" to "bytes=0-${headBytes - 1}"), timeout = 6L)
    try {
        val size = when (r.code) {
            206 -> r.headers["Content-Range"]?.substringAfterLast('/')?.trim()?.toLongOrNull()
            200 -> r.headers["Content-Length"]?.toLongOrNull()
            else -> return null
        }?.takeIf { it > 1 }
        val pts = if (headBytes <= 1) null else try {
            r.okhttpResponse.body.byteStream().let { s ->
                val b = ByteArray(headBytes); var n = 0
                while (n < b.size) { val k = s.read(b, n, b.size - n); if (k < 0) break; n += k }
                Hls.firstTsPts(b.copyOf(n))
            }
        } catch (_: Exception) { null }
        return SegRead(size, pts)
    } finally { r.closeQuietly() }
}

/** Reads [sizeIdx] (size, and timestamp when headBytes > 1) and [ptsIdx] (timestamp only), 6 at a time. */
internal suspend fun AnizmProvider.measure(media: Hls.Media, sizeIdx: Collection<Int>, ptsIdx: Collection<Int>, headers: Map<String, String>, headBytes: Int): Measured {
    val sizes = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    val pts = java.util.concurrent.ConcurrentHashMap<Int, Double>()
    val forSize = sizeIdx.toHashSet()
    val gate = Semaphore(6)
    coroutineScope {
        for (i in (sizeIdx + ptsIdx).distinct()) launch {
            gate.withPermit {
                val r = try { readSegment(media.segs[i].url, headers, if (i in forSize) headBytes else 16_384) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { null }
                if (r != null) {
                    if (i in forSize) r.size?.let { sizes[i] = it }
                    r.pts?.let { pts[i] = it }
                }
            }
        }
    }
    return Measured(media, sizes, pts)
}

/**
 * A rendition's sampled sizes (and, with [wantPts], first timestamps, the first and last segment
 * included). Byte-range playlists carry their sizes, so those need no requests. Sizes are empty
 * when under half the samples answered.
 */
internal suspend fun AnizmProvider.sampleRendition(media: Hls.Media, headers: Map<String, String>, n: Int, seed: Int,
                                    wantPts: Boolean = false, adaptive: Boolean = false): Measured {
    val segs = media.segs
    if (segs.isEmpty() || media.total <= 0) return Measured(media, emptyMap(), emptyMap())
    if (media.hasByteRange) {
        // Every length known: exact. Some missing: the known ones are the sample.
        val known = segs.indices.filter { segs[it].byteLen != null }.associateWith { segs[it].byteLen!! }
        return Measured(media, known, emptyMap(), exact = known.size == segs.size)
    }
    val idx = Hls.strata(segs, n, seed)
    val ends = if (wantPts) listOf(0, segs.size - 1) else emptyList()
    var m = measure(media, idx, ends, headers, if (wantPts) 16_384 else 1)
    if (m.sizes.size < maxOf(2, (idx.size + 1) / 2)) {
        log("size: only ${m.sizes.size}/${idx.size} samples answered")
        return Measured(media, emptyMap(), m.pts)
    }
    if (adaptive && segs.size > n * 3 && Hls.spread(segs, m.sizes) > 0.6) {
        val more = Hls.strata(segs, n, seed + 1).filter { it !in m.sizes }
        val m2 = measure(media, more, emptyList(), headers, 1)
        log("size: uneven bitrate (spread %.2f) — ${m2.sizes.size} more samples".format(java.util.Locale.US, Hls.spread(segs, m.sizes)))
        m = Measured(media, m.sizes + m2.sizes, m.pts + m2.pts)
    }
    return m
}

/**
 * Sizes of every variant ([wantSizes]) and where each one's timestamps restart ([checkJoins]),
 * from one pass of reads; kept for good under [key]. Variants are best first. Null when nothing
 * could be worked out.
 */
internal suspend fun AnizmProvider.analyseStream(key: String, variants: List<Variant>, headers: Map<String, String>, checkJoins: Boolean,
                                  wantSizes: Boolean, declaredFallback: Boolean = false, segQuery: String = "", tag: String): StreamInfo? {
    val cached = loadInfo(key)
    val needSizes = wantSizes && cached?.sizes.isNullOrEmpty()
    val needJoins = checkJoins && cached?.joinsChecked != true
    if (!needSizes && !needJoins) return cached
    val top = variants.firstOrNull() ?: return cached
    val medias = coroutineScope {
        variants.map { v -> async {
            try { Hls.parseMedia(playlistText(v.url, headers, 8L), v.url, segQuery) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        } }.map { it.await() }
    }
    val topMedia = medias[0]?.takeIf { it.segs.isNotEmpty() } ?: return cached
    val seed = key.hashCode()
    val gate = Semaphore(6)
    suspend fun ptsOf(media: Hls.Media, k: Int): Double? = gate.withPermit {
        try { readSegment(media.segs[k].url, headers, 16_384)?.pts }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
    }
    // Timestamps can only be read from the head of whole MPEG-TS segment files.
    fun tsReadable(m: Hls.Media) = !m.hasByteRange && !m.hasMap && m.segs.size >= 3
    /** The joins of one rendition, and whether every range could be settled. */
    suspend fun joinsOf(media: Hls.Media, known: Map<Int, Double>, label: String): Pair<List<Int>, Boolean> {
        val k = HashMap(known)
        for (e in listOf(0, media.segs.size - 1)) if (e !in k) ptsOf(media, e)?.let { k[e] = it }
        if (0 !in k || (media.segs.size - 1) !in k) { log("$tag: join check for $label: no timestamp in the first or last segment"); return emptyList<Int>() to false }
        val j = Hls.findJoins(media.segs, k, 60) { i -> ptsOf(media, i) }
        log("$tag: $label timestamps " + (if (j.at.isEmpty()) "continuous" else "restart at ${j.at.joinToString { "segment $it (%.1f s)".format(java.util.Locale.US, media.segs[it].start) }}") +
            " (${k.size} + ${j.reads} reads${if (j.unresolved) ", some ranges unreadable" else ""})")
        return j.at to !j.unresolved
    }

    val m = sampleRendition(topMedia, headers, if (needSizes) sizeSamples else 4, seed,
        wantPts = needJoins && tsReadable(topMedia), adaptive = needSizes)
    val topBytes = if (needSizes) m.bytes else null
    val sizes = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    val joins = java.util.concurrent.ConcurrentHashMap<Int, List<Int>>()
    val allSettled = java.util.concurrent.atomic.AtomicBoolean(true)
    var declared = false
    if (topBytes != null) {
        sizes[top.height] = topBytes
        log("size: $tag ${top.height}p ${if (m.exact) "=" else "≈"} ${topBytes / 1_048_576}MB (${(topBytes * 8 / topMedia.total / 1000).toLong()} kbps " +
            "${if (m.exact) "from byte ranges" else "measured, ${m.sizes.size} samples"} vs ${(top.bandwidth ?: 0) / 1000} kbps declared, ${(topMedia.total / 60).toInt()} min)")
    } else if (needSizes && declaredFallback && variants.any { (it.bandwidth ?: 0) > 0 }) {
        // The host won't answer ranged segment requests: the declared bitrate, marked as an upper bound.
        declared = true
        for (v in variants) v.bandwidth?.takeIf { it > 0 }?.let { sizes[v.height] = (it / 8.0 * topMedia.total).toLong() }
        log("size: $tag segments not measurable, using declared bitrate x ${(topMedia.total / 60).toInt()} min (upper bound)")
    }
    var topJoins: List<Int>? = null
    if (needJoins) {
        if (!tsReadable(topMedia)) topJoins = emptyList() // byte-range / fMP4: nothing to read, nothing to fix
        else joinsOf(topMedia, m.pts, "${top.height}p").let { (at, settled) ->
            if (settled || at.isNotEmpty()) topJoins = at
            if (!settled) allSettled.set(false)
        }
        topJoins?.let { joins[top.height] = it } ?: allSettled.set(false)
    }
    val topJoinsFinal = topJoins

    coroutineScope {
        variants.forEachIndexed { vi, v ->
            val media = medias[vi]?.takeIf { it.segs.isNotEmpty() }
            if (vi == 0 || media == null) { if (vi > 0 && needJoins) allSettled.set(false); return@forEachIndexed }
            launch {
                val sameGrid = media.segs.size == topMedia.segs.size && Math.abs(media.total - topMedia.total) < 1.0
                // Size: a few reads at the moments the top quality was read, scaled by the ratio there.
                if (needSizes && topBytes != null && (m.exact || media.hasByteRange)) {
                    sampleRendition(media, headers, sizeSamples, seed).bytes?.let { sizes[v.height] = it }
                } else if (needSizes && topBytes != null) {
                    val at = m.sizes.keys.sorted()
                    val want = minOf(if (settings.accurateSizes) 8 else 4, at.size)
                    val picks = (0 until want).map { at[((it + 0.5) * at.size / want).toInt().coerceIn(0, at.size - 1)] }.distinct()
                    val pairs = picks.associateWith { ti -> if (sameGrid) ti else Hls.indexAt(media.segs, topMedia.segs[ti].start + topMedia.segs[ti].dur / 2) }
                    val got = measure(media, pairs.values, emptyList(), headers, 1)
                    val used = pairs.filter { it.value in got.sizes }
                    val topRate = used.keys.sumOf { m.sizes[it]!! } / used.keys.sumOf { topMedia.segs[it].dur }.coerceAtLeast(0.001)
                    val lowRate = used.values.sumOf { got.sizes[it]!! } / used.values.sumOf { media.segs[it].dur }.coerceAtLeast(0.001)
                    if (used.isNotEmpty() && used.size * 2 >= picks.size && topRate > 0) {
                        sizes[v.height] = (topBytes * (lowRate / topRate) * (media.total / topMedia.total)).toLong()
                        log("size: $tag ${v.height}p ≈ ${sizes[v.height]!! / 1_048_576}MB (${"%.2f".format(java.util.Locale.US, lowRate / topRate)} x the ${top.height}p rate at ${used.size} points)")
                    } else {
                        // Not readable: the declared ladder's ratio, which is rougher.
                        val tb = top.bandwidth; val vb = v.bandwidth
                        if (tb != null && tb > 0 && vb != null) sizes[v.height] = topBytes * vb / tb
                    }
                }
                // Joins: the parts are the same upload at every quality, so with the same segment
                // grid the joins are at the same segments; each is confirmed with two reads.
                if (needJoins) {
                    val tj = topJoinsFinal
                    when {
                        !tsReadable(media) -> joins[v.height] = emptyList()
                        tj != null && sameGrid && tj.isEmpty() -> joins[v.height] = emptyList()
                        tj != null && sameGrid && tj.all { j ->
                            val a = ptsOf(media, j - 1); val b = ptsOf(media, j)
                            a != null && b != null && Hls.isJump(media.segs, j - 1, 0.0, j, Hls.drift(media.segs, j - 1, a, j, b))
                        } -> { joins[v.height] = tj; log("$tag: ${v.height}p restarts at the same ${tj.size} segment(s) as ${top.height}p") }
                        else -> joinsOf(media, emptyMap(), "${v.height}p").let { (at, settled) ->
                            if (settled || at.isNotEmpty()) joins[v.height] = at
                            if (!settled) allSettled.set(false)
                        }
                    }
                }
            }
        }
    }
    val info = StreamInfo(if (needSizes) sizes.toMap() else cached?.sizes ?: emptyMap(),
        if (needJoins) joins.toMap() else cached?.joins ?: emptyMap(),
        declared = declared, joinsChecked = if (needJoins) allSettled.get() else cached?.joinsChecked ?: false)
    saveInfo(key, info)
    return info
}

// Split-audio master (Aincrad): top video rendition + the audio rendition, as one figure.
internal suspend fun AnizmProvider.estimateSplitAudioBytes(key: String, master: String, masterUrl: String, variants: List<Variant>, headers: Map<String, String>): Long? = coroutineScope {
    val top = variants.firstOrNull() ?: return@coroutineScope null
    val audioUrl = audioMediaUriRe.find(master)?.groupValues?.get(1)?.let { Hls.resolve(masterUrl, it) }
    suspend fun media(u: String) = try { Hls.parseMedia(playlistText(u, headers, 8L), u) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    // Audio is near-constant bitrate AAC (3 samples were within 1 % on the measured episode).
    val audioJob = audioUrl?.let { u -> async { media(u)?.let { sampleRendition(it, headers, 3, key.hashCode()) } } }
    val vm = media(top.url)
    val video = vm?.let { sampleRendition(it, headers, sizeSamples, key.hashCode(), adaptive = true) }
    val vBytes = video?.bytes
    if (vm == null || vBytes == null) { audioJob?.cancel(); return@coroutineScope null }
    val aBytes = audioJob?.await()?.bytes
    val bytes = vBytes + (aBytes ?: 0L)
    log("size: split-audio ${top.height}p ≈ ${bytes / 1_048_576}MB (video ${(vBytes * 8 / vm.total / 1000).toLong()} kbps from ${video.sizes.size} samples + " +
        "audio ${aBytes?.let { "${it / 1_048_576}MB" } ?: "missing"}, ${(vm.total / 60).toInt()} min, ${(top.bandwidth ?: 0) / 1000} kbps declared)")
    bytes
}

// Shared by Aincrad (muxed masters), Beta Player and sniffed streams: one selectable link per
// resolution, each validated by content before it's emitted.
internal suspend fun AnizmProvider.emitVariants(
    variants: List<Variant>, cleanLabel: String, referer: String, headers: Map<String, String>,
    callback: (ExtractorLink) -> Unit, tag: String, sizeKey: String,
    allowDeclaredFallback: Boolean = false,
    // Emit every variant as HLS without the probe (used when our own client is refused but the
    // player's network stack may not be).
    trustPlaylists: Boolean = false,
    // What the caller already worked out about this stream (then nothing is measured here).
    known: StreamInfo? = null,
    // A playlist is fetched whole, as a page does; the ranged probe is for hosts that may hand back a video file.
    probeWhole: Boolean = false,
): Boolean {
    if (trustPlaylists) {
        for (v in variants) callback(variantLink(v, cleanLabel, "", referer, headers))
        return variants.isNotEmpty()
    }
    val info = known ?: if (estimateHlsSizes) withTimeoutOrNull(sizeEstimateBudgetMs) {
        analyseStream(sizeKey, variants, headers, checkJoins = false, wantSizes = true, declaredFallback = allowDeclaredFallback, tag = tag)
    } else null
    val mark = sizeMark(info)
    fun size(v: Variant) = Hls.formatSize(info?.sizes?.get(v.height), mark)
    val any = java.util.concurrent.atomic.AtomicBoolean(false)
    val gate = Semaphore(3)
    coroutineScope {
        for (v in variants) {
            launch {
                gate.withPermit {
                    // A playlist this load already downloaded (the size and join pass) is the answer.
                    if (recentPlaylist(v.url) != null) {
                        callback(variantLink(v, cleanLabel, size(v), referer, headers))
                        any.set(true)
                        return@withPermit
                    }
                    val probe = try {
                        app.get(v.url, headers = if (probeWhole) headers else headers + mapOf("Range" to "bytes=0-4095"), timeout = 8L)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { log("$tag: variant ${v.height}p probe failed: ${e.message}"); return@withPermit }
                    val pt = cappedText(probe).trimStart()
                    when {
                        // A refusal is a refusal, whatever its body looks like.
                        probe.code !in 200..299 ->
                            log("$tag: variant ${v.height}p refused (http ${probe.code})")
                        pt.startsWith("#EXTM3U") -> {
                            callback(variantLink(v, cleanLabel, size(v), referer, headers))
                            any.set(true)
                        }
                        pt.startsWith("<") || pt.contains("<html", ignoreCase = true) || pt.isBlank() ->
                            log("$tag: variant ${v.height}p unusable")
                        else -> {
                            callback(variantLink(v, cleanLabel, Hls.formatSize(parseContentRangeTotal(probe.headers)), referer, headers, ExtractorLinkType.VIDEO))
                            any.set(true)
                        }
                    }
                }
            }
        }
    }
    return any.get()
}

/** One link for one quality; a variant of unknown height gets no "…p" in its name. */
internal suspend fun AnizmProvider.variantLink(v: Variant, cleanLabel: String, size: String, referer: String, headers: Map<String, String>,
                                type: ExtractorLinkType = ExtractorLinkType.M3U8, url: String = v.url, tag: String = ""): ExtractorLink =
    newExtractorLink(source = cleanLabel, name = (if (v.height != Qualities.Unknown.value) "$cleanLabel ${v.height}p" else cleanLabel) + tag + size,
        url = url, type = type) { quality = v.height; this.referer = referer; this.headers = headers }

/**
 * v28: try header sets in order against one variant playlist and its first segment (one
 * byte). Returns the first set that gets both; logs what each refused one got.
 */
internal suspend fun AnizmProvider.pickStreamHeaders(variantUrl: String, sets: List<Pair<String, Map<String, String>>>, tokenQuery: String = ""): Map<String, String>? {
    for ((name, h) in sets) {
        val why = try {
            val pl = app.get(variantUrl, headers = h, timeout = 8L)
            val text = pl.text
            if (pl.code !in 200..299 || !text.trimStart().startsWith("#EXTM3U")) {
                "playlist http ${pl.code} ${pl.headers["Content-Type"] ?: ""} ${oneLine(text, 60)}"
            } else {
                val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                if (first == null) "no segments in playlist" else {
                    // v32: resolving drops the variant's query; the segment needs the token (as the player now gets it).
                    val segUrl = (try { java.net.URI(variantUrl).resolve(first).toString() } catch (_: Exception) { first })
                        .let { if (tokenQuery.isNotEmpty() && !it.contains('?')) "$it?$tokenQuery" else it }
                    val r = app.get(segUrl, headers = h + mapOf("Range" to "bytes=0-0"), timeout = 8L)
                    val code = r.code
                    val info = "${r.headers["Content-Type"]}, server=${r.headers["server"]}"
                    r.closeQuietly()
                    if (code in 200..299) { log("sniff: headers '$name' work (playlist ok, segment $code, $info)"); return h }
                    "segment http $code, $info"
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            // v33: a timeout means the host is ignoring this client, not judging headers;
            // the v32 log spent 5 x 8s finding that out. Stop at the first one.
            if (e is java.io.InterruptedIOException) { log("sniff: headers '$name' timed out — host not answering this client, skipping the rest"); return null }
            "error ${e.javaClass.simpleName}: ${e.message?.take(60)}"
        }
        log("sniff: headers '$name' refused: $why")
        delay(250)
    }
    return null
}
