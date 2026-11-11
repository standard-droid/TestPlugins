// Checks the parts of the extension that need no network, Android or CloudStream: Hls.kt (playlist
// parsing, the timestamp-join search, MPEG-TS timestamps, size arithmetic) and the bits of
// AnizmParser that need no saved pages. Not part of the Gradle build — run tools/check-units.sh.
// Exits non-zero if any check fails. The playlists below are shaped like the live ones (2026-11-10).

import com.a.anizm.AnizmParser
import com.a.anizm.Hls
import com.a.anizm.onDomain
import com.a.anizm.sameHost
import com.a.anizm.withParams
import com.a.anizm.redactForLog
import com.a.anizm.headersForLog
import com.a.anizm.CastingLoad
import com.a.anizm.isCastingLoad
import com.a.anizm.isLocalLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import kotlin.system.exitProcess

private var failed = 0
private var passed = 0
private fun check(what: String, ok: Boolean, detail: Any? = null) {
    if (ok) passed++ else failed++
    println((if (ok) "  ok    " else "  FAIL  ") + what + (if (!ok && detail != null) "  →  $detail" else ""))
}
private fun section(name: String) = println("\n## $name")

/** Two MPEG-TS packets whose first one starts a PES packet with [pts] (90 kHz ticks). */
private fun tsWithPts(pts: Long, adaptation: Int = 0, streamId: Int = 0xE0): ByteArray {
    val b = ByteArray(376)
    for (o in intArrayOf(0, 188)) { b[o] = 0x47; b[o + 1] = 0x01; b[o + 2] = 0x00; b[o + 3] = 0x10 }
    b[1] = 0x41 // payload unit start
    var p = 4
    if (adaptation > 0) { b[3] = 0x30; b[4] = adaptation.toByte(); p = 5 + adaptation }
    b[p] = 0; b[p + 1] = 0; b[p + 2] = 1; b[p + 3] = streamId.toByte()
    b[p + 6] = 0x80.toByte(); b[p + 7] = 0x80.toByte(); b[p + 8] = 5
    b[p + 9] = (0x21 or (((pts shr 30) and 7).toInt() shl 1)).toByte()
    b[p + 10] = ((pts shr 22) and 0xFF).toByte()
    b[p + 11] = ((((pts shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
    b[p + 12] = ((pts shr 7) and 0xFF).toByte()
    b[p + 13] = (((pts and 0x7F).toInt() shl 1) or 1).toByte()
    return b
}

private fun mediaPlaylist(durations: List<Double>, discAt: Set<Int> = emptySet()) = buildString {
    append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:3\n#EXT-X-MEDIA-SEQUENCE:0\n")
    durations.forEachIndexed { i, d -> if (i in discAt) append("#EXT-X-DISCONTINUITY\n"); append("#EXTINF:$d,\nseg$i.ts\n") }
    append("#EXT-X-ENDLIST\n")
}

fun main() {
    section("Master playlists")
    val beta = """
        #EXTM3U
        #EXT-X-VERSION:3

        #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,CODECS="avc1.640029,mp4a.40.2",NAME="720p"
        /m3/TOKEN720

        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640029,mp4a.40.2",NAME="1080p"
        /m3/TOKEN1080

        #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=854x480,CODECS="avc1.640029,mp4a.40.2",NAME="480p"
        /m3/TOKEN480
    """.trimIndent()
    val bv = Hls.parseVariants(beta, "https://pl.example.tr/stream/abc/master.txt")
    check("Beta master: three variants, best first", bv.map { it.height } == listOf(1080, 720, 480), bv)
    check("Beta master: URLs resolved against the master", bv.first().url == "https://pl.example.tr/m3/TOKEN1080", bv.first().url)
    check("Beta master: bandwidth read", bv.first().bandwidth == 5_000_000L, bv.first().bandwidth)

    val aincrad = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",LANGUAGE="und",NAME="Undefined",DEFAULT=NO,AUTOSELECT=YES,URI="/m3/v1.AUDIO"
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=276000,RESOLUTION=640x360,NAME="360p",CODECS="avc1.4D4015,mp4a.40.2",AUDIO="audio"
        /m3/v1.A360
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=4096000,RESOLUTION=1920x1080,NAME="1080p",CODECS="avc1.640028,mp4a.40.2",AUDIO="audio"
        /m3/v1.A1080
    """.trimIndent()
    val av = Hls.parseVariants(aincrad, "https://player.example.com/cdn/hls/x/master.txt")
    check("Aincrad master: ascending list comes out best first", av.map { it.height } == listOf(1080, 360), av)
    val top = Hls.topOnlyMaster(aincrad, "https://player.example.com/cdn/hls/x/master.txt", av.first())
    check("top-only master keeps the audio group and one variant",
        top != null && top.contains("TYPE=AUDIO") && top.contains("/m3/v1.A1080") && !top.contains("A360") && top.lines().count { it.startsWith("#EXT-X-STREAM-INF") } == 1, top)
    check("top-only master: null when the variant isn't listed",
        Hls.topOnlyMaster(aincrad, "https://player.example.com/cdn/hls/x/master.txt", av.first().copy(url = "https://elsewhere/x")) == null)

    val odd = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000,AVERAGE-BANDWIDTH=700000,RESOLUTION=1280x720\n#EXT-X-SOMETHING:1\n\nlow.m3u8\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\nhigh.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=100\nnores.m3u8\n"
    val ov = Hls.parseVariants(odd, "https://h/a/master.m3u8")
    check("a tag between STREAM-INF and its URI is skipped", ov.any { it.url.endsWith("/a/low.m3u8") } || ov.any { it.url.endsWith("/a/high.m3u8") }, ov)
    check("two variants of one height: the higher bitrate is kept", ov.size == 1 && ov[0].url.endsWith("high.m3u8") && ov[0].bandwidth == 2_500_000L, ov)
    check("BANDWIDTH is not read out of AVERAGE-BANDWIDTH", Hls.parseVariants(odd.substringBefore("#EXT-X-STREAM-INF:BANDWIDTH=2500000"), "https://h/m")[0].bandwidth == 900_000L)
    check("maxHeight", Hls.maxHeight(aincrad) == 1080)

    section("Media playlists")
    val text = "#EXTM3U\n#EXTINF:2.002,\na.ts\n#EXTINF:1.5,\n#EXT-X-BYTERANGE:1000@0\nb.ts?x=1\n#EXT-X-DISCONTINUITY\n#EXTINF:0.5,\n/abs/c.ts\n#EXT-X-ENDLIST\n"
    val media = Hls.parseMedia(text, "https://h/p/list.m3u8", segQuery = "k=1")!!
    check("segments, durations and start times", media.segs.size == 3 && media.segs[2].start == 3.502 && Math.abs(media.total - 4.002) < 1e-9, media.segs.map { it.start })
    check("segment URLs resolved, token added only where there is no query",
        media.segs.map { it.url } == listOf("https://h/p/a.ts?k=1", "https://h/p/b.ts?x=1", "https://h/abs/c.ts?k=1"), media.segs.map { it.url })
    check("byte range and discontinuity noted", media.hasByteRange && media.segs[1].byteLen == 1000L && media.segs[2].marked && !media.segs[1].marked)
    check("not a playlist → null", Hls.parseMedia("<html>", "https://h/x") == null)
    check("first segment URL", Hls.firstSegmentUrl(text, "https://h/p/list.m3u8", "k=1") == "https://h/p/a.ts?k=1")

    section("MPEG-TS timestamps")
    check("PTS read from a video PES", Hls.firstTsPts(tsWithPts(133_200)) == 1.48, Hls.firstTsPts(tsWithPts(133_200)))
    check("PTS read behind an adaptation field", Hls.firstTsPts(tsWithPts(42_734_700, adaptation = 7)) == 474.83, Hls.firstTsPts(tsWithPts(42_734_700, adaptation = 7)))
    check("PTS above 2^32 ticks", Hls.firstTsPts(tsWithPts(8_000_000_000L)) == 8_000_000_000L / 90_000.0)
    check("junk before the first sync byte is skipped", Hls.firstTsPts(ByteArray(10) + tsWithPts(90_000)) == 1.0)
    check("no PES start → null", Hls.firstTsPts(ByteArray(376).also { it[0] = 0x47; it[188] = 0x47 }) == null)
    check("not MPEG-TS → null", Hls.firstTsPts(ByteArray(400) { 1 }) == null)

    section("Timestamp joins")
    // Three ~474 s parts of 2 s segments, each restarting near 1.48 s: the Beta upload v45/v47 fixed.
    val durs = List(714) { if (it % 238 == 237) 0.04 else 2.002 }
    val joined = Hls.parseMedia(mediaPlaylist(durs), "https://h/v/list.m3u8")!!
    fun ptsOf(m: Hls.Media, parts: List<Int>): (Int) -> Double = { k ->
        val partStart = parts.last { it <= k }
        1.48 + (m.segs[k].start - m.segs[partStart].start)
    }
    val truth = ptsOf(joined, listOf(0, 238, 476))
    var reads = 0
    val found = runBlocking { Hls.findJoins(joined.segs, mapOf(0 to truth(0), 713 to truth(713)), 60) { reads++; truth(it) } }
    check("two joins found from the first and last timestamp alone", found.at == listOf(238, 476) && !found.unresolved, found.at)
    check("…in well under the read budget", reads in 1..45, reads)
    val sampled = Hls.strata(joined.segs, 16, 7).associateWith { truth(it) } + mapOf(0 to truth(0), 713 to truth(713))
    reads = 0
    val found2 = runBlocking { Hls.findJoins(joined.segs, sampled, 60) { reads++; truth(it) } }
    check("same joins when 16 sampled timestamps are already known, with fewer reads", found2.at == listOf(238, 476) && reads < 30, "${found2.at} in $reads")

    val clean = ptsOf(joined, listOf(0))
    reads = 0
    val none = runBlocking { Hls.findJoins(joined.segs, mapOf(0 to clean(0), 713 to clean(713)), 60) { reads++; clean(it) } }
    check("continuous timestamps: no joins, no reads", none.at.isEmpty() && !none.unresolved && reads == 0, "${none.at} / $reads")

    // A two-hour film whose real segment lengths differ from EXTINF by 0.1 %: 7 s of drift, no join.
    val film = Hls.parseMedia(mediaPlaylist(List(3600) { 2.0 }), "https://h/f/list.m3u8")!!
    val drifting: (Int) -> Double = { 10.0 + film.segs[it].start * 1.001 }
    reads = 0
    val slow = runBlocking { Hls.findJoins(film.segs, mapOf(0 to drifting(0), 3599 to drifting(3599)), 60) { reads++; drifting(it) } }
    check("slow drift over a long film is not a join", slow.at.isEmpty() && reads == 0, "${slow.at} / $reads")

    val starved = runBlocking { Hls.findJoins(joined.segs, mapOf(0 to truth(0), 713 to truth(713)), 3) { truth(it) } }
    check("out of reads: reported as unresolved, nothing guessed", starved.unresolved && starved.at.all { it == 238 || it == 476 }, starved.at)
    val unreadable = runBlocking { Hls.findJoins(joined.segs, mapOf(0 to truth(0), 713 to truth(713)), 60) { null } }
    check("segments unreadable in a long range: unresolved, nothing guessed", unreadable.unresolved && unreadable.at.isEmpty(), unreadable.at)

    val wrapBase = (1L shl 33) / 90_000.0 - 100.0 // timestamps wrap 100 s in
    val wrapping: (Int) -> Double = { (wrapBase + joined.segs[it].start) % ((1L shl 33) / 90_000.0) }
    val wrapped = runBlocking { Hls.findJoins(joined.segs, mapOf(0 to wrapping(0), 713 to wrapping(713)), 60) { wrapping(it) } }
    check("a 33-bit timestamp wrap is not a join", wrapped.at.isEmpty() && !wrapped.unresolved, wrapped.at)

    val fixed = Hls.withDiscontinuities(joined, listOf(238, 476))
    val refixed = fixed?.let { Hls.parseMedia(it, "https://h/v/list.m3u8") }
    check("discontinuities go in before the right segments", refixed != null && refixed.segs.indices.filter { refixed.segs[it].marked } == listOf(238, 476))
    check("…and the playlist is otherwise unchanged",
        fixed != null && fixed.replace("#EXT-X-DISCONTINUITY\n", "").trimEnd() == joined.lines.joinToString("\n").trimEnd())
    check("already marked joins: nothing to add", refixed != null && Hls.withDiscontinuities(refixed, listOf(238, 476)) == null)

    section("Size sampling")
    val idx = Hls.strata(joined.segs, 16, 12345)
    check("16 sample points, one per slice of playing time", idx.size == 16 && idx == idx.sorted() &&
        idx.withIndex().all { (j, k) -> joined.segs[k].start + joined.segs[k].dur >= j * joined.total / 16 - 1e-6 && joined.segs[k].start <= (j + 1) * joined.total / 16 + 1e-6 }, idx)
    check("the same video is sampled at the same places", idx == Hls.strata(joined.segs, 16, 12345))
    check("another video is sampled elsewhere", idx != Hls.strata(joined.segs, 16, 54321))
    check("more samples than segments: every segment", Hls.strata(media.segs, 16, 1) == listOf(0, 1, 2))
    val sizes = joined.segs.indices.associateWith { (joined.segs[it].dur * 400_000).toLong() } // 3.2 Mbps, constant
    val est = Hls.estimateBytes(joined.segs, idx.associateWith { sizes[it]!! })!!
    val real = sizes.values.sum()
    check("constant bitrate: the estimate is the real size", Math.abs(est - real) < real / 1000, "$est vs $real")
    check("even bitrate has no spread, uneven has", Hls.spread(joined.segs, idx.associateWith { sizes[it]!! }) < 0.01 &&
        Hls.spread(joined.segs, idx.withIndex().associate { (j, k) -> k to (if (j % 2 == 0) 100_000L else 2_000_000L) }) > 0.6)
    check("segment playing at a time", Hls.indexAt(joined.segs, 0.0) == 0 && Hls.indexAt(joined.segs, 3.0) == 1 && Hls.indexAt(joined.segs, 1e9) == 713)
    val us = java.util.Locale.US
    check("size text", Hls.formatSize(608_279_756L, "~", us) == " [~580MB]" && Hls.formatSize(1_503_238_553L, "", us) == " [1.4GB]" && Hls.formatSize(null) == "" && Hls.formatSize(900_000_000L, "≤", us) == " [≤858MB]",
        Hls.formatSize(608_279_756L, "~", us) + Hls.formatSize(1_503_238_553L, "", us) + Hls.formatSize(900_000_000L, "≤", us))

    section("Disguised segments")
    val ts = ByteArray(188 * 4).also { for (o in 0 until 4) it[o * 188] = 0x47 }
    check("plain MPEG-TS starts at 0", Hls.findMediaStart(ts) == 0)
    check("an image header in front is found", Hls.findMediaStart(ByteArray(120) { 0x11 } + ts) == 120)
    check("fMP4 box found", Hls.findMediaStart(byteArrayOf(0, 0, 0, 24) + "styp".toByteArray() + ByteArray(600)) == 0)
    check("neither → null", Hls.findMediaStart(ByteArray(2000) { 0x22 }) == null)

    section("Anime page (markup of 2026-11-10)")
    val page = """
        <html><body><main><section id="bolumler"><span class="v3-section-kicker">Yani Neko</span>
          <div class="v3-anime-episode-grid">
            <a href="https://anizm.net/yani-neko-1-bolum-izle"><span class="v3-anime-episode-number">1</span>
              <span><strong>1. Bölüm</strong><small>Yani Neko · Türkçe altyazılı izle</small></span></a>
            <a href="https://anizm.net/yani-neko-2-bolum-izle"><span class="v3-anime-episode-number">2</span>
              <span><strong>2. Bölüm</strong><small>Yani Neko · Türkçe altyazılı izle</small></span></a>
          </div></section></main></body></html>
    """.trimIndent()
    val notes = ArrayList<String>()
    AnizmParser.onFallback = { notes += it }
    val eps = AnizmParser.episodes(Jsoup.parse(page, "https://anizm.net/yani-neko"), "https://anizm.net/yani-neko")
    check("episodes read from the class-named grid, without a fallback", eps.map { it.number } == listOf(1, 2) && eps[0].name == "1. Bölüm" && notes.isEmpty(), "$eps $notes")
    val eps2 = AnizmParser.episodes(Jsoup.parse(page.replace("v3-anime-episode-grid", "renamed"), "https://anizm.net/yani-neko"), "https://anizm.net/yani-neko")
    check("renamed grid: still read, and the fallback is reported", eps2.size == 2 && notes.size == 1, notes)

    section("Source credits")
    val info = AnizmParser.videoInfo("""{"success":true,"url":"https:\/\/anizm.net\/player\/1866766","source":"Beta Player","translator":"Net-Rip","redaktor":"","encoder":"Rinrintan","uploader":{"name":"Rinrintan","url":"x","uploads":652677}}""")
    check("translator, encoder and uploader read", info?.translator == "Net-Rip" && info.encoder == "Rinrintan" && info.uploader == "Rinrintan", info)
    check("encoder = uploader collapses; the fansub is not repeated", AnizmParser.creditsFor(info!!.credits(), "Net-Rip") == "Enc/Up: Rinrintan", AnizmParser.creditsFor(info.credits(), "Net-Rip"))
    check("all three when they differ", AnizmParser.creditsFor(AnizmParser.VideoInfo("A|Subs", "B", "C").credits(), "Other") == "Tr: A/Subs, Enc: B, Up: C",
        AnizmParser.creditsFor(AnizmParser.VideoInfo("A|Subs", "B", "C").credits(), "Other"))
    check("only an uploader", AnizmParser.creditsFor(AnizmParser.VideoInfo(null, null, "C").credits(), "X") == "Up: C")
    check("nothing known → no credits", AnizmParser.videoInfo("""{"success":true,"url":"x"}""") == null && AnizmParser.creditsFor("||", "X") == "")

    section("Addresses")
    check("the site itself", onDomain("anizm.net", "anizm.net") && onDomain("www.anizm.net", "anizm.net"))
    check("a lookalike is not the site", !onDomain("evilanizm.net", "anizm.net") && !onDomain("anizm.net.evil.com", "anizm.net"))
    check("a whole address works too", onDomain("https://pl.puffytr.tr", "puffytr.tr") && !onDomain("https://evilpuffytr.tr/watch/x", "puffytr.tr"))
    check("case does not matter", onDomain("WWW.Anizm.NET", "anizm.net"))
    check("an unreadable address is not on any domain", !onDomain("https://", "anizm.net"))
    check("same host", sameHost("https://anizm.net/a", "https://ANIZM.net/b?x=1"))
    check("another host, even a subdomain, is not the same", !sameHost("https://anizm.net/a", "https://cdn.anizm.net/a") && !sameHost("https://anizm.net/", "https://evil.com/"))
    check("withParams: added to a bare address", withParams("https://h/p", mapOf("k" to "a b")) == "https://h/p?k=a+b", withParams("https://h/p", mapOf("k" to "a b")))
    check("withParams: replaces a key and keeps the others", withParams("https://h/p?a=1&k=old", mapOf("k" to "new")) == "https://h/p?a=1&k=new", withParams("https://h/p?a=1&k=old", mapOf("k" to "new")))
    check("withParams: a fragment stays at the end, once", withParams("https://h/p#id", mapOf("k" to "v")) == "https://h/p?k=v#id", withParams("https://h/p#id", mapOf("k" to "v")))
    check("withParams: query and fragment", withParams("https://h/p?a=1#id", mapOf("k" to "v")) == "https://h/p?a=1&k=v#id", withParams("https://h/p?a=1#id", mapOf("k" to "v")))

    section("Log redaction")
    val signed = "sniff: found https://cdn.h/p/v.m3u8?k=AbCdEfGhIjKlMnOpQrSt&kx=1763000000&q=1080"
    check("a token is cut to 4 characters and its length", redactForLog(signed) == "sniff: found https://cdn.h/p/v.m3u8?k=AbCd…(20)&kx=1763000000&q=1080", redactForLog(signed))
    check("short values and text without a query are untouched", redactForLog("http 404 for https://anizm.net/x?sayfa=2") == "http 404 for https://anizm.net/x?sayfa=2" &&
        redactForLog("plain message") == "plain message")
    val hdr = headersForLog(linkedMapOf("User-Agent" to "Mozilla/5.0", "Cookie" to "cf_clearance=SECRET1; __cf_bm=SECRET2", "Authorization" to "Bearer SECRET3"))
    check("cookies by name only, credentials hidden", hdr == "User-Agent=Mozilla/5.0; Cookie=[cf_clearance,__cf_bm]; Authorization=[hidden]", hdr)
    check("no secret survives", !hdr.contains("SECRET"))

    section("Casting mark")
    val outside = runBlocking { isCastingLoad() }
    val inside = runBlocking { withContext(CastingLoad()) { isCastingLoad() } }
    val inChild = runBlocking { withContext(CastingLoad()) { coroutineScope { async { isCastingLoad() }.await() } } }
    check("not marked outside a casting run", !outside)
    check("marked inside one, and in its child coroutines", inside && inChild, "$inside $inChild")
    check("local addresses recognised", isLocalLink("http://127.0.0.1:41234/hls/abc.m3u8") && !isLocalLink("https://anizmplayer.com/cdn/hls/x/master.txt"))

    println("\n$passed passed, $failed failed")
    exitProcess(if (failed == 0) 0 else 1)
}
