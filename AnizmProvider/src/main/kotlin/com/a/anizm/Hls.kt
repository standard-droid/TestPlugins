package com.a.anizm

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** One entry of an HLS master playlist. */
internal data class Variant(val height: Int, val bandwidth: Long?, val url: String)

/**
 * Playlist and MPEG-TS reading with no network, Android or CloudStream dependency, so
 * AnizmProvider/tools/check-hls.sh can test it with a plain kotlinc.
 */
internal object Hls {
    private val resAttrRe = Regex("""RESOLUTION=\d+x(\d+)""")
    private val bwAttrRe = Regex("""(?<![A-Z-])BANDWIDTH=(\d+)""")
    private val extinfRe = Regex("""#EXTINF:([\d.]+)""")
    private val byteRangeRe = Regex("""#EXT-X-BYTERANGE:(\d+)""")

    fun resolve(base: String, ref: String): String =
        try { java.net.URI(base).resolve(ref.trim()).toString() } catch (_: Exception) { ref.trim() }

    fun isPlaylist(text: String) = text.trimStart().startsWith("#EXTM3U")

    /**
     * The master's variants, best first, one per height (the highest BANDWIDTH of a height wins).
     * The URI is the first non-tag line after #EXT-X-STREAM-INF, as the spec allows other tags
     * and blank lines in between.
     */
    fun parseVariants(master: String, masterUrl: String): List<Variant> {
        val out = ArrayList<Variant>()
        var attrs: String? = null
        for (raw in master.lineSequence()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-STREAM-INF:") -> attrs = l.substringAfter(':')
                l.isEmpty() || l.startsWith("#") -> {}
                else -> {
                    val a = attrs; attrs = null
                    if (a == null) continue
                    val height = resAttrRe.find(a)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    val u = try { java.net.URI(masterUrl).resolve(l).toString() } catch (_: Exception) { continue }
                    out += Variant(height, bwAttrRe.find(a)?.groupValues?.get(1)?.toLongOrNull(), u)
                }
            }
        }
        return out.sortedWith(compareByDescending<Variant> { it.height }.thenByDescending { it.bandwidth ?: 0L })
            .distinctBy { it.height }
    }

    /** The highest RESOLUTION height anywhere in a master, for masters parseVariants can't read. */
    fun maxHeight(master: String): Int? = resAttrRe.findAll(master).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()

    /**
     * The master with every #EXT-X-STREAM-INF entry but [keep]'s removed. Media lines (the audio
     * group) and the header stay. Null if [keep]'s entry is not found, so the caller plays the
     * full master rather than a broken one.
     */
    fun topOnlyMaster(master: String, masterUrl: String, keep: Variant): String? {
        val lines = master.lines()
        val out = ArrayList<String>(lines.size)
        var kept = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                var j = i + 1
                while (j < lines.size && lines[j].isBlank()) j++
                val uri = lines.getOrNull(j)?.trim().orEmpty()
                if (uri.isNotEmpty() && !uri.startsWith("#") && resolve(masterUrl, uri) == keep.url) { out += line; out += uri; kept = true }
                i = j + 1
                continue
            }
            out += line
            i++
        }
        return if (kept) out.joinToString("\n") else null
    }

    /** One media segment: where the playlist puts it, its line, and what the playlist says about it. */
    class Seg(val dur: Double, val start: Double, val line: Int, val url: String, val byteLen: Long?, val marked: Boolean)
    class Media(val lines: List<String>, val segs: List<Seg>, val hasByteRange: Boolean, val hasMap: Boolean) {
        val total: Double = segs.sumOf { it.dur }
    }

    /** A media playlist's segments; [segQuery] is added to segment URLs that carry no query. Null if it isn't one. */
    fun parseMedia(text: String, url: String, segQuery: String = ""): Media? {
        if (!isPlaylist(text)) return null
        val lines = text.lines()
        val segs = ArrayList<Seg>()
        var dur: Double? = null
        var byteLen: Long? = null
        var t = 0.0
        var disc = false
        var hasRange = false
        var hasMap = false
        lines.forEachIndexed { i, raw ->
            val l = raw.trim()
            when {
                l.isEmpty() -> {}
                l.startsWith("#EXTINF:") -> dur = extinfRe.find(l)?.groupValues?.get(1)?.toDoubleOrNull()
                l.startsWith("#EXT-X-BYTERANGE:") -> { hasRange = true; byteLen = byteRangeRe.find(l)?.groupValues?.get(1)?.toLongOrNull() }
                l.startsWith("#EXT-X-MAP") -> hasMap = true
                l.startsWith("#EXT-X-DISCONTINUITY") && !l.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> disc = true
                l.startsWith("#") -> {}
                else -> {
                    val d = dur
                    if (d != null) {
                        val u = resolve(url, l).let { if (segQuery.isNotEmpty() && !it.contains('?')) "$it?$segQuery" else it }
                        segs += Seg(d, t, i, u, byteLen, disc); t += d
                    }
                    dur = null; byteLen = null; disc = false
                }
            }
        }
        return Media(lines, segs, hasRange, hasMap)
    }

    /** The first segment URL of a media playlist text, or null. */
    fun firstSegmentUrl(text: String, playlistUrl: String, segQuery: String = ""): String? {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        return resolve(playlistUrl, first).let { if (segQuery.isNotEmpty() && !it.contains('?')) "$it?$segQuery" else it }
    }

    /**
     * [n] sample positions, one in each of n slices of equal playing time. Where in its slice
     * each one falls depends only on [seed], so the same video is always sampled at the same
     * places (and so shows the same size on every load), without the samples lining up with
     * anything periodic in the video the way an even grid can.
     */
    fun strata(segs: List<Seg>, n: Int, seed: Int): List<Int> {
        if (segs.isEmpty() || n <= 0) return emptyList()
        if (n >= segs.size) return segs.indices.toList()
        val total = segs.sumOf { it.dur }
        if (total <= 0) return emptyList()
        val out = LinkedHashSet<Int>()
        var k = 0
        for (j in 0 until n) {
            var x = (seed * 31 + j) * -0x61c88647 // golden-ratio hash
            x = x xor (x ushr 15); x *= 0x2c1b3c6d; x = x xor (x ushr 12)
            val frac = ((x ushr 8) and 0xFFFF) / 65536.0
            val at = (j + frac) * total / n
            while (k < segs.size - 1 && segs[k].start + segs[k].dur <= at) k++
            out += k
        }
        return out.toList()
    }

    /** The segment playing at time [t]. */
    fun indexAt(segs: List<Seg>, t: Double): Int {
        var lo = 0; var hi = segs.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (segs[mid].start <= t) lo = mid else hi = mid - 1 }
        return lo
    }

    /** Whole-playlist bytes from sampled segment sizes (index -> bytes): bytes per second of the samples x total time. */
    fun estimateBytes(segs: List<Seg>, sizes: Map<Int, Long>): Long? {
        val d = sizes.keys.sumOf { segs[it].dur }
        val total = segs.sumOf { it.dur }
        if (sizes.isEmpty() || d <= 0 || total <= 0) return null
        return (sizes.values.sum() / d * total).toLong()
    }

    /** How uneven the sampled bitrates are (standard deviation / mean of bytes per second). */
    fun spread(segs: List<Seg>, sizes: Map<Int, Long>): Double {
        val rates = sizes.filter { segs[it.key].dur > 0.2 }.map { it.value / segs[it.key].dur }
        if (rates.size < 3) return 0.0
        val mean = rates.average()
        if (mean <= 0) return 0.0
        return Math.sqrt(rates.sumOf { (it - mean) * (it - mean) } / rates.size) / mean
    }

    /** First PES timestamp (PTS, in seconds) in the head of an MPEG-TS file; null if there is none. */
    fun firstTsPts(b: ByteArray): Double? {
        var i = (0 until minOf(b.size, 4_096)).firstOrNull { b[it] == 0x47.toByte() && (it + 188 >= b.size || b[it + 188] == 0x47.toByte()) }
            ?: return null
        while (i + 188 <= b.size) {
            if (b[i] != 0x47.toByte()) return null
            val unitStart = (b[i + 1].toInt() and 0x40) != 0
            val afc = (b[i + 3].toInt() shr 4) and 3
            var p = i + 4
            if (afc == 2 || afc == 3) p += 1 + (b[i + 4].toInt() and 0xff)
            if (unitStart && (afc == 1 || afc == 3) && p + 14 <= i + 188 &&
                b[p] == 0.toByte() && b[p + 1] == 0.toByte() && b[p + 2] == 1.toByte() &&
                (b[p + 3].toInt() and 0xff) in 0xC0..0xEF && (b[p + 7].toInt() and 0x80) != 0) {
                val q = p + 9
                val pts = ((b[q].toLong() and 0x0E) shl 29) or ((b[q + 1].toLong() and 0xFF) shl 22) or
                    ((b[q + 2].toLong() and 0xFE) shl 14) or ((b[q + 3].toLong() and 0xFF) shl 7) or ((b[q + 4].toLong() and 0xFE) shr 1)
                return pts / 90_000.0
            }
            i += 188
        }
        return null
    }

    class Joins(val at: List<Int>, val unresolved: Boolean, val reads: Int)

    private const val PTS_WRAP = (1L shl 33) / 90_000.0

    /** How far segment [k]'s first timestamp [p] is from where the playlist puts it, relative to segment [k0] at [p0]. */
    fun drift(segs: List<Seg>, k0: Int, p0: Double, k: Int, p: Double): Double {
        val x = ((p - p0 - (segs[k].start - segs[k0].start)) % PTS_WRAP + PTS_WRAP) % PTS_WRAP
        return if (x > PTS_WRAP / 2) x - PTS_WRAP else x
    }

    /** A step in drift between two segments that is a restart, not EXTINF rounding adding up over the gap. */
    fun isJump(segs: List<Seg>, lo: Int, dLo: Double, hi: Int, dHi: Double): Boolean =
        Math.abs(dHi - dLo) > maxOf(5.0, 0.01 * (segs[hi].start - segs[lo].start))

    /**
     * Segments whose timestamps restart (separately encoded parts joined into one playlist).
     *
     * Drift is flat within a part and steps at each join, so only ranges whose ends differ are
     * split further, up to 7 points at a time in parallel. [known] are first timestamps already in
     * hand (index -> PTS; at least the first and last segment); [ptsAt] reads one more and may
     * return null. At most [budget] reads are made; a range that can't be settled within it is
     * reported as unresolved, never guessed.
     */
    suspend fun findJoins(segs: List<Seg>, known: Map<Int, Double>, budget: Int, ptsAt: suspend (Int) -> Double?): Joins {
        val pts = known.toSortedMap()
        if (pts.size < 2 || segs.size < 3) return Joins(emptyList(), pts.size < 2, 0)
        val k0 = pts.firstKey(); val p0 = pts[k0]!!
        val left = java.util.concurrent.atomic.AtomicInteger(budget)
        val joins = java.util.concurrent.ConcurrentSkipListSet<Int>()
        val unresolved = java.util.concurrent.atomic.AtomicBoolean(false)
        val typical = segs.map { it.dur }.sorted()[segs.size / 2]
        suspend fun read(k: Int): Double? = if (left.decrementAndGet() < 0) null else ptsAt(k)
        suspend fun search(lo: Int, dLo: Double, hi: Int, dHi: Double) {
            if (!isJump(segs, lo, dLo, hi, dHi)) return
            if (hi - lo == 1) { joins += hi; return }
            val n = minOf(7, hi - lo - 1)
            val points = (1..n).map { lo + ((hi - lo).toLong() * it / (n + 1)).toInt() }.distinct().filter { it in (lo + 1) until hi }
            val ds = coroutineScope { points.map { k -> async { read(k)?.let { drift(segs, k0, p0, k, it) } } }.map { it.await() } }
            val got = points.zip(ds).filter { it.second != null }.map { it.first to it.second!! }
            if (got.isEmpty()) {
                // Nothing inside could be read. With reads left (the segments themselves failed) and a
                // short range, a part's last segment is the short one, so the join goes after it.
                val k = if (left.get() > 0 && hi - lo <= 16) ((lo + 1)..hi).minByOrNull { segs[it - 1].dur } else null
                if (k != null && segs[k - 1].dur < typical * 0.9) joins += k else unresolved.set(true)
                return
            }
            val chain = listOf(lo to dLo) + got + listOf(hi to dHi)
            coroutineScope { chain.zipWithNext().forEach { (a, b) -> launch { search(a.first, a.second, b.first, b.second) } } }
        }
        val chain = pts.entries.map { it.key to drift(segs, k0, p0, it.key, it.value) }
        coroutineScope { chain.zipWithNext().forEach { (a, b) -> launch { search(a.first, a.second, b.first, b.second) } } }
        return Joins(joins.toList(), unresolved.get(), budget - maxOf(left.get(), 0))
    }

    /** The playlist with #EXT-X-DISCONTINUITY before each of [joins] that isn't marked already; null if none is missing. */
    fun withDiscontinuities(media: Media, joins: Collection<Int>): String? {
        val missing = joins.filter { it in 1 until media.segs.size && !media.segs[it].marked }
        if (missing.isEmpty()) return null
        val after = missing.map { media.segs[it - 1].line }.toSet()
        return buildString {
            media.lines.forEachIndexed { i, l -> append(l).append('\n'); if (i in after) append("#EXT-X-DISCONTINUITY\n") }
        }
    }

    /** " [~1.3GB]" style suffix. [mark]: "~" measured estimate, "≤" declared upper bound, "" exact. */
    fun formatSize(bytes: Long?, mark: String = "", locale: java.util.Locale = java.util.Locale.getDefault()): String {
        if (bytes == null || bytes <= 0) return ""
        val gb = bytes / 1_073_741_824.0
        return if (gb >= 1) " [$mark%.1fGB]".format(locale, gb) else " [$mark%.0fMB]".format(locale, bytes / 1_048_576.0)
    }

    /** Where the real media starts in a (possibly disguised) segment: 3 MPEG-TS sync bytes 188 apart, or an MP4 ftyp/styp/moof box; null if neither. */
    fun findMediaStart(b: ByteArray): Int? = mediaStart(b).takeIf { it > 0 || looksLikeMediaAt0(b) }
    private fun looksLikeMediaAt0(b: ByteArray): Boolean =
        (b.size > 376 && b[0] == 0x47.toByte() && b[188] == 0x47.toByte() && b[376] == 0x47.toByte()) ||
            (b.size >= 8 && isMp4Box(b, 4))
    private fun isMp4Box(b: ByteArray, i: Int): Boolean {
        val c0 = b[i].toInt(); val c1 = b[i + 1].toInt(); val c2 = b[i + 2].toInt(); val c3 = b[i + 3].toInt()
        return (c1 == 't'.code && c2 == 'y'.code && c3 == 'p'.code && (c0 == 'f'.code || c0 == 's'.code)) ||
            (c0 == 'm'.code && c1 == 'o'.code && c2 == 'o'.code && c3 == 'f'.code)
    }
    private fun mediaStart(b: ByteArray): Int {
        val limit = minOf(b.size, 262_144)
        var i = 0
        while (i + 376 < limit) {
            if (b[i] == 0x47.toByte() && b[i + 188] == 0x47.toByte() && b[i + 376] == 0x47.toByte()) return i
            i++
        }
        i = 4
        while (i + 4 <= limit) {
            if (isMp4Box(b, i)) return i - 4
            i++
        }
        return 0
    }
}
