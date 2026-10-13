// Checks AnizmParser against saved copies of anizm.net. Not part of the Gradle build — run
// tools/check-parser.sh <folder>. Exits non-zero if any check fails.
//
// The saved pages are deliberately not in the repo. They come from running a capture script in
// the browser console on anizm.net (one JSON file with every page), split into gzipped
// <name>.html.gz / <name>.json.gz files named the way the checks below read them.
//
// Besides the pages as saved, it re-runs the key checks on deliberately damaged copies
// (renamed classes, removed attributes) to prove each fallback actually catches that change.

import com.a.anizm.AnizmParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.system.exitProcess

private val dir = File(System.getProperty("fixtures") ?: "AnizmProvider/tools/fixtures")
private var failed = 0
private var passed = 0

private fun text(name: String) = GZIPInputStream(File(dir, "$name.gz").inputStream()).bufferedReader().readText()
private fun doc(name: String, url: String) = Jsoup.parse(text(name), url)

private fun check(what: String, ok: Boolean, detail: Any? = null) {
    if (ok) passed++ else failed++
    println((if (ok) "  ok    " else "  FAIL  ") + what + (detail?.let { "  →  $it" } ?: ""))
}

private fun section(name: String) = println("\n## $name")

private fun Document.damaged(block: Document.() -> Unit) = clone().also(block)

fun main() {
    val site = "https://anizm.net"

    section("Home page rows")
    val home = doc("home.html", "$site/")
    for ((id, min) in listOf("son-bolumler" to 10, "populer" to 10, "yeni-seriler" to 10)) {
        val row = AnizmParser.homeRow(home, id)
        check("$id: ≥$min cards", row.size >= min, row.size)
        check("$id: every card has a poster (home uses data-v3-deferred-src)", row.isNotEmpty() && row.all { it.poster != null }, row.count { it.poster == null }.let { "$it missing" })
        check("$id: titles clean", row.all { it.title.isNotBlank() && !it.title.endsWith("posteri") }, row.take(3).map { it.title })
    }
    val latest = AnizmParser.homeRow(home, "son-bolumler")
    check("episode cards link to the anime, not the episode", latest.none { it.url.contains("bolum") }, latest.take(3).map { it.url })
    val renamed = home.damaged { select("article").forEach { it.tagName("div").removeAttr("class") } }
    check("fallback: row found with <article>/classes gone", AnizmParser.homeRow(renamed, "populer").size >= 10, AnizmParser.homeRow(renamed, "populer").size)

    section("/yeniler")
    val y1 = AnizmParser.listing(doc("yeniler_sayfa_1.html", "$site/yeniler?sayfa=1"), 1)
    check("page 1: ≥20 anime", y1.cards.size >= 20, y1.cards.size)
    check("page 1: has next", y1.hasNext)
    check("page 1: posters", y1.cards.all { it.poster != null })
    val y2 = AnizmParser.listing(doc("yeniler_sayfa_2.html", "$site/yeniler?sayfa=2"), 2)
    check("page 2: ≥20 anime, has next", y2.cards.size >= 20 && y2.hasNext, y2.cards.size)
    val y1Doc = doc("yeniler_sayfa_1.html", "$site/yeniler?sayfa=1")
    val yLoose = AnizmParser.listing(y1Doc.damaged { select("article").forEach { it.tagName("div").removeAttr("class") } }, 1).cards
    check("fallback: cards with <article>/classes gone, same count, no site-logo card",
        yLoose.size == y1.cards.size && yLoose.none { it.url.trimEnd('/') == site }, yLoose.size)

    section("Search")
    val arama = AnizmParser.listing(doc("arama_q_frieren.html", "$site/arama?q=frieren"), 1).cards
    check("/arama?q=frieren: 3 results", arama.size == 3, arama.map { it.title })
    check("/arama: Frieren first, with poster and year", arama.firstOrNull()?.let { it.url == "$site/sousou-no-frieren" && it.poster != null && it.year == 2023 } == true, arama.firstOrNull())
    check("/arama?q=frieren: no next page", !AnizmParser.listing(doc("arama_q_frieren.html", "$site/arama?q=frieren"), 1).hasNext)
    val op = AnizmParser.listing(doc("arama_q_one_20piece.html", "$site/arama?q=one%20piece"), 1).cards
    check("/arama?q=one piece: finds One Piece", op.any { it.url == "$site/one-piece" }, op.size)
    val api = AnizmParser.searchApi(text("searchAnime_frieren.json"), site)
    check("/searchAnime frieren: 3 hits with slug/poster", api.size == 3 && api.all { it.slug.isNotBlank() && it.poster?.contains("/storage/pcovers/") == true }, api.map { it.slug })
    check("/searchAnime haystack has the English title", api.any { it.haystack.contains("beyondjourneysend") })
    check("/searchAnime naruto: hits", AnizmParser.searchApi(text("searchAnime_naruto.json"), site).size >= 5)
    check("/searchAnime: a 500 body parses to nothing, not a crash", AnizmParser.searchApi("""{"message":"Server Error"}""", site).isEmpty())
    val sug = AnizmParser.suggestions(text("arama-onerileri_q_frieren.json"))
    check("/arama-onerileri: 3 suggestions", sug.size == 3 && sug[0].url == "$site/sousou-no-frieren", sug.map { it.title })

    section("Anime page: Frieren")
    val frDoc = doc("sousou-no-frieren.html", "$site/sousou-no-frieren")
    val fr = AnizmParser.anime(frDoc, "$site/sousou-no-frieren")
    check("title", fr.title == "Sousou no Frieren", fr.title)
    check("English alias", "Frieren: Beyond Journey's End" in fr.aliases, fr.aliases)
    check("Japanese alias", fr.aliases.any { it.contains("フリーレン") }, fr.aliases)
    check("poster (large cover)", fr.poster?.endsWith("l.webp") == true, fr.poster)
    check("full plot (not the 160-char meta)", (fr.plot?.length ?: 0) > 300, fr.plot?.length)
    check("year", fr.year == 2023, fr.year)
    check("genres", fr.tags == listOf("Dram", "Fantastik", "Macera", "Shounen"), fr.tags)
    check("28 episodes", fr.episodes.size == 28, fr.episodes.size)
    check("numbered 1..28", fr.episodes.map { it.number } == (1..28).toList(), fr.episodes.map { it.number })
    check("names are just the episode title", fr.episodes.first().name == "1. Bölüm" && fr.episodes.last().name == "28. Bölüm Final", fr.episodes.last().name)

    val noGrid = frDoc.damaged { select("[data-anime-episode-grid]").removeAttr("data-anime-episode-grid") }
    check("fallback: episodes without the grid attribute", AnizmParser.anime(noGrid, "$site/sousou-no-frieren").episodes.map { it.number } == (1..28).toList())
    val noSection = frDoc.damaged { select("#bolumler").removeAttr("id"); select("[data-anime-episode-grid]").removeAttr("data-anime-episode-grid") }
    check("fallback: episodes with neither grid nor #bolumler", AnizmParser.anime(noSection, "$site/sousou-no-frieren").episodes.size == 28,
        AnizmParser.anime(noSection, "$site/sousou-no-frieren").episodes.size)
    val relative = frDoc.damaged { select("[data-anime-episode-grid] > a[href]").forEach { it.attr("href", it.attr("href").removePrefix(site)) } }
    check("relative episode hrefs still come out absolute",
        AnizmParser.anime(relative, "$site/sousou-no-frieren").episodes.map { it.url } == fr.episodes.map { it.url })
    val bare = frDoc.damaged { select(".v3-anime-detail-cover, #v3-anime-summary-content, .v3-anime-detail-tags, #bolumler .v3-section-kicker").remove() }
    val frBare = AnizmParser.anime(bare, "$site/sousou-no-frieren")
    check("fallback: title/plot/poster/genres from JSON-LD", frBare.title == "Sousou no Frieren" && (frBare.plot?.length ?: 0) > 300 &&
        frBare.poster?.endsWith("180410001696010341l.webp") == true && frBare.tags.size == 4, frBare.copy(episodes = emptyList(), plot = null))

    section("Anime page: One Piece")
    val opa = AnizmParser.anime(doc("one-piece.html", "$site/one-piece"), "$site/one-piece")
    check("1175 episodes", opa.episodes.size == 1175, opa.episodes.size)
    check("41.Bölüm (one-piece-41bolum-izle) is episode 41", opa.episodes.any { it.number == 41 && it.url.endsWith("41bolum-izle") })
    val dbl = opa.episodes.filter { it.url.contains("215-216") }
    check("215-216 double episode kept, numbered 0", dbl.size == 1 && dbl[0].number == 0, dbl)
    val dupes = opa.episodes.filter { it.number > 0 }.groupBy { it.number }.filter { it.value.size > 1 }
    check("no two regular episodes share a number", dupes.isEmpty(), dupes.keys.take(10))
    check("1180 present (data-episode-number drift ignored)", opa.episodes.any { it.number == 1180 && it.url.endsWith("one-piece-1180-bolum-izle") })

    section("URL helpers")
    check("episode → anime URL", AnizmParser.animeUrlFromEpisodeUrl("$site/bungou-stray-dogs-wan-2-11-bolum-izle") == "$site/bungou-stray-dogs-wan-2")
    check("double episode → anime URL (with its label)", AnizmParser.animeUrlFromEpisodeUrl("$site/one-piece-215-216-bolum-izle", "215-216. Bölüm") == "$site/one-piece")
    check("no-hyphen slug → anime URL", AnizmParser.animeUrlFromEpisodeUrl("$site/one-piece-41bolum-izle") == "$site/one-piece")
    check("final → anime URL", AnizmParser.animeUrlFromEpisodeUrl("$site/sousou-no-frieren-28-bolum-final-izle") == "$site/sousou-no-frieren")
    check("home poster ignores the defaultcover placeholder", AnizmParser.absUrl(site, "/upload/assets/defaultcover.webp") == null)

    section("Episode page → fansubs → sources")
    val epUrl = "$site/sousou-no-frieren-1-bolum-izle"
    val epHtml = text("sousou-no-frieren-1-bolum-izle.html")
    val trs = AnizmParser.translators(epHtml, epUrl)
    check("Frieren ep 1: 4 fansubs", trs.map { it.name } == listOf("Hoşumuza Giden Şeyler", "Holy", "GachaFlex Fansub", "NekoSubs"), trs.map { it.name })
    check("fansub URLs", trs.all { Regex("""^https://anizm\.net/bolum/73483/cevirmen/\d+$""").matches(it.url) }, trs.map { it.url })
    val noSources = Jsoup.parse(epHtml, epUrl).also { it.select("[data-sources]").removeAttr("data-sources") }.outerHtml()
    check("fallback: data-sources gone → built from data-translator-id + the player block's /bolum/<id>/",
        AnizmParser.translators(noSources, epUrl).map { it.url } == trs.map { it.url })
    val noSourcesNoWt = Jsoup.parse(noSources, epUrl).also { it.select(".v3-watch-together, [data-episode-id]").remove() }.outerHtml()
    check("fallback: …and with the Watch Together widget gone too",
        AnizmParser.translators(noSourcesNoWt, epUrl).map { it.url } == trs.map { it.url })
    val noSourcesNoPlayerIds = Jsoup.parse(noSources, epUrl).also { it.select("[data-progress]").removeAttr("data-progress"); it.select("[data-url]").removeAttr("data-url") }.outerHtml()
    check("fallback: …or with the player block's URLs gone (data-episode-id backup)",
        AnizmParser.translators(noSourcesNoPlayerIds, epUrl).map { it.url } == trs.map { it.url })
    val noTitles = Jsoup.parse(epHtml, epUrl).also { it.select("[data-v3-translator]").removeAttr("title") }.outerHtml()
    check("fallback: titles gone → names from the button text (DOM path)",
        AnizmParser.translators(noTitles, epUrl).map { it.name } == trs.map { it.name }, AnizmParser.translators(noTitles, epUrl).map { it.name })
    check("text scan unescapes entities in names and URLs",
        AnizmParser.translators("""<button data-sources="/bolum/1/cevirmen/2?a=1&amp;b=2" title="A &amp; B">x</button>""", epUrl)
            .let { it.size == 1 && it[0].name == "A & B" && it[0].url == "$site/bolum/1/cevirmen/2?a=1&b=2" })
    val mixed = epHtml.replaceFirst("<button ", """<button data-translator-id="555" title="Only Id">x</button><button """)
    check("a button with only data-translator-id is still found (DOM path)",
        AnizmParser.translators(mixed, epUrl).let { it.size == trs.size + 1 && it.any { t -> t.url == "$site/bolum/73483/cevirmen/555" } })
    val noButtons = Jsoup.parse(epHtml, epUrl).also { it.select("[data-v3-translator]").remove() }.outerHtml() +
        trs.joinToString("") { "<script>var u='${it.url.removePrefix(site)}';</script>" }
    check("fallback: buttons gone → URLs found in the raw HTML", AnizmParser.translators(noButtons, epUrl).map { it.url } == trs.map { it.url },
        AnizmParser.translators(noButtons, epUrl).map { it.url })
    for (f in listOf("28-bolum-final-izle" to 1, "41bolum-izle" to 1, "215-216-bolum-izle" to 1)) {
        val name = if (f.first.startsWith("28")) "sousou-no-frieren-${f.first}" else "one-piece-${f.first}"
        val t = AnizmParser.translators(text("$name.html"), "$site/$name")
        check("$name: ≥${f.second} fansub", t.size >= f.second, t.map { it.name })
    }

    val counts = mapOf("92417" to 4, "92397" to 11, "106762" to 1, "97303" to 3)
    for ((tr, n) in counts) {
        val v = AnizmParser.videos(text("bolum_73483_cevirmen_$tr.json"))
        check("fansub $tr: $n sources, numeric ids", v.size == n && v.all { it.id.all(Char::isDigit) }, v.map { it.name })
    }
    val holy = AnizmParser.videos(text("bolum_73483_cevirmen_92397.json"))
    check("source names unescaped", holy.first().name == "Aincrad (Reklamsız)", holy.first().name)
    check("One Piece ep 1: 24 sources", AnizmParser.videos(text("bolum_18853_cevirmen_18853.json")).size == 24)
    check("videos: wrapped in data{}", AnizmParser.videos("""{"data":{"videos":[{"id":5,"name":"X"}]}}""").map { it.id } == listOf("5"))
    check("videos: bare array", AnizmParser.videos("""[{"id":"7","name":"Y"}]""").map { it.id } == listOf("7"))
    check("videos: error JSON → empty", AnizmParser.videos("""{"success":false}""").isEmpty())
    check("videos: HTML error page → empty", AnizmParser.videos("<html>404</html>").isEmpty())

    println("\n$passed passed, $failed failed")
    if (failed > 0) exitProcess(1)
}
