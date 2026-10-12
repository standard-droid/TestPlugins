package com.a.anizm

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Everything that reads anizm.net's HTML and JSON, with no network, Android or CloudStream
 * dependency — only jsoup and org.json. That keeps it checkable on its own against saved copies
 * of the site: AnizmProvider/tools/check-parser.sh compiles this file with a plain kotlinc and
 * runs it over AnizmProvider/tools/fixtures (no Gradle, no Android SDK).
 *
 * Every function tries the current layout first and then looser, structure-based fallbacks, so a
 * renamed class empties nothing on its own. The layouts are the "v3" redesign (2026-10).
 */
object AnizmParser {
    data class Card(val title: String, val url: String, val poster: String?, val year: Int?)
    data class Listing(val cards: List<Card>, val hasNext: Boolean)
    /** number = 0 for previews and joined/range entries ("1-2. Bölüm (Ön Gösterim)", "215-216. Bölüm"). */
    data class Episode(val url: String, val name: String, val number: Int)
    data class Anime(
        val title: String, val poster: String?, val plot: String?, val year: Int?,
        val tags: List<String>, val aliases: List<String>, val episodes: List<Episode>,
    )
    data class SearchHit(val slug: String, val title: String, val poster: String?, val year: Int?, val haystack: String)
    data class Translator(val url: String, val name: String)
    data class Video(val id: String, val name: String)

    private val cleanRe = Regex("""\s*[-–|]\s*Anizm.*$""", RegexOption.IGNORE_CASE)
    private val posterAltRe = Regex("""\s+(?:anime\s+)?posteri$""", RegexOption.IGNORE_CASE)
    private val izleRe = Regex("""\s+[iİ]zle\b.*$""", RegexOption.IGNORE_CASE)
    private val izleTruncRe = Regex("""\s+[iİ]z?l?e?\.{2,}.*$""", RegexOption.IGNORE_CASE)
    private val yearRe = Regex("""^\d{4}$""")
    private val nonAlnumRe = Regex("""[^\p{L}\p{N}]+""")
    private val rangeLabelRe = Regex("""\d+\s*-\s*\d+\.?\s*[Bb][oöô]l[uüû]m""")
    private val singleEpLabelRe = Regex("""^(\d+)\s*\.?\s*[Bb][oöô]l[uüû]m""")
    // "-?": 53 One Piece slugs have no hyphen before "bolum" (one-piece-41bolum-izle)
    private val singleEpUrlRe = Regex("""-(\d+)-?bolum(?:-|$)""")
    private val episodeSuffixRe = Regex("""-\d+[-.]?bolum[^/?#]*""")
    private val rangeEpisodeSuffixRe = Regex("""-\d+-\d+[-.]?bolum[^/?#]*""")
    private val episodeHrefRe = Regex("""\d-?bolum""")
    private val translatorUrlRe = Regex("""/bolum/(\d+)/cevirmen/(\d+)""")
    private val defaultCoverRe = Regex("""defaultcover""", RegexOption.IGNORE_CASE)

    fun squash(s: String) = s.lowercase().replace(nonAlnumRe, "")
    fun words(q: String) = q.split(nonAlnumRe).filter { it.length >= 2 }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Resolves [src] against [base]; null for blanks, data: URIs and the site's placeholder cover. */
    fun absUrl(base: String, src: String?): String? {
        val s = src?.trim().orEmpty()
        if (s.isEmpty() || s.startsWith("data:") || defaultCoverRe.containsMatchIn(s)) return null
        return when {
            s.startsWith("http") -> s
            s.startsWith("//") -> "https:$s"
            else -> try { java.net.URI(base).resolve(s).toString() } catch (_: Exception) { null }
        }
    }

    // The home page defers its images: the URL is in data-v3-deferred-src and the site's JS
    // copies it into src later (the <noscript> twin carries a plain src). /yeniler and /arama
    // use src directly.
    private val imgAttrs = listOf("src", "data-v3-deferred-src", "data-src", "data-original", "data-lazy-src")

    fun imageUrl(el: Element?): String? {
        if (el == null) return null
        val imgs = if (el.tagName() == "img") listOf(el) else el.select("img")
        for (img in imgs) for (a in imgAttrs) absUrl(img.baseUri(), img.attr(a))?.let { return it }
        return null
    }

    private fun Element.cleanText() = text().replace(cleanRe, "").trim()

    private fun ldJson(doc: Document, vararg types: String): JSONObject? =
        doc.select("script[type=application/ld+json]").asSequence().mapNotNull { s ->
            try { JSONObject(s.data().trim()) } catch (_: Exception) { null }
        }.firstOrNull { it.optString("@type") in types }

    private fun JSONObject.str(key: String): String? =
        opt(key)?.takeIf { it is String || it is Number }?.toString()?.trim()?.ifBlank { null }

    /** "bungou-stray-dogs-wan-2-11-bolum-izle" → "bungou-stray-dogs-wan-2". [label] tells a
     *  double episode ("215-216. Bölüm") apart from a title that ends in a number. */
    fun animeUrlFromEpisodeUrl(href: String, label: String? = null): String {
        val re = if (label != null && rangeLabelRe.containsMatchIn(label)) rangeEpisodeSuffixRe else episodeSuffixRe
        return href.replace(re, "").trimEnd('-', '/')
    }

    // ── Listings (home rows, /yeniler, /arama) ──────────────────────────────

    fun card(el: Element): Card? {
        val a = el.selectFirst("a.v3-catalog-poster[href], a[href]") ?: (if (el.tagName() == "a") el else return null)
        val href = a.attr("abs:href").ifBlank { return null }
        val img = el.selectFirst("img")
        val title = el.selectFirst(".v3-card-copy h3, .v3-catalog-card-copy h2, .v3-catalog-preview h3, h3, h2")
            ?.cleanText()?.ifBlank { null }
            ?: img?.attr("alt")?.replace(posterAltRe, "")?.trim()?.ifBlank { null }
            ?: a.attr("aria-label").replace(Regex("""\s+anime detayları$"""), "").trim().ifBlank { null }
            ?: return null
        // Episode cards link to the episode; the anime page is that URL without "-N-bolum-izle".
        val isEpisode = el.hasClass("v3-episode-card") || episodeHrefRe.containsMatchIn(href.substringAfterLast('/'))
        val url = if (isEpisode) animeUrlFromEpisodeUrl(href, el.selectFirst(".v3-card-copy span, .v3-card-tag")?.text()) else href
        val year = el.selectFirst(".v3-catalog-card-copy p, .v3-catalog-preview-meta span")?.text()?.trim()
            ?.takeIf { yearRe.matches(it) }?.toIntOrNull()
        return Card(title, url, imageUrl(img), year)
    }

    private fun cards(els: Collection<Element>) = els.mapNotNull { card(it) }.distinctBy { it.url }

    private val cardSel = "article.v3-episode-card, article.v3-series-card, article.v3-catalog-card"

    // Fallback for a renamed card class: any link with a poster image inside the main content.
    private fun looseCards(root: Element) =
        root.select("a[href]:has(img)").filter { it.parents().none { p -> p.tagName() in setOf("header", "nav", "footer") } }

    /** A home-page row: "son-bolumler", "populer" or "yeni-seriler". */
    fun homeRow(doc: Document, rowId: String): List<Card> {
        val section = doc.getElementById(rowId) ?: return emptyList()
        return cards(section.select("article").ifEmpty { looseCards(section) })
    }

    /** /yeniler?sayfa=N and /arama?…: the card grid plus whether there's a next page. */
    fun listing(doc: Document, page: Int): Listing {
        val els = doc.select(cardSel).ifEmpty { doc.selectFirst("main")?.let { looseCards(it) } ?: emptyList() }
        val list = cards(els)
        val hasNext = doc.selectFirst("a[rel=next]") != null ||
            doc.select("a[href*=sayfa=${page + 1}]").any { Regex("""[?&]sayfa=${page + 1}(?:&|$)""").containsMatchIn(it.attr("href")) }
        return Listing(list, hasNext && list.isNotEmpty())
    }

    // ── Search ──────────────────────────────────────────────────────────────

    /** /searchAnime JSON: {"data":[…],"has_more":…} (older: a bare array). */
    fun searchApi(text: String, base: String): List<SearchHit> {
        val arr = try {
            val t = text.trim()
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("data")
        } catch (_: Exception) { null } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val slug = o.str("info_slug") ?: return@mapNotNull null
            val title = o.str("info_title") ?: return@mapNotNull null
            val poster = o.str("info_poster")?.let { if (it.startsWith("http") || it.startsWith("/")) absUrl(base, it) else absUrl(base, "/storage/pcovers/$it") }
            val hay = squash(listOf("info_title", "info_titleoriginal", "info_titleenglish", "info_othernames").joinToString(" ") { o.str(it) ?: "" })
            SearchHit(slug, title, poster, o.str("info_year")?.toIntOrNull(), hay)
        }
    }

    /** /arama-onerileri JSON: {"results":[{"title","year","url"}]} — 3 results, no posters. */
    fun suggestions(text: String): List<Card> {
        val arr = try { JSONObject(text.trim()).optJSONArray("results") } catch (_: Exception) { null } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Card(o.str("title") ?: return@mapNotNull null, o.str("url") ?: return@mapNotNull null, null, o.str("year")?.toIntOrNull())
        }
    }

    // ── Anime page ──────────────────────────────────────────────────────────

    fun anime(doc: Document, url: String): Anime {
        val base = doc.location().ifBlank { url }
        val ld = ldJson(doc, "TVSeries", "Movie", "CreativeWorkSeries", "TVSeason")
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.replace(cleanRe, "")?.replace(izleRe, "")?.replace(izleTruncRe, "")?.trim()?.ifBlank { null }
        // h1 reads "Sousou no Frieren izle"; the episode section's kicker is the bare title.
        val title = doc.selectFirst("#bolumler .v3-section-kicker")?.text()?.trim()?.ifBlank { null }
            ?: ld?.str("name")
            ?: doc.selectFirst("header.v3-anime-detail-header h1, main h1, h1")?.text()?.replace(izleRe, "")?.trim()?.ifBlank { null }
            ?: ogTitle
            ?: url.trimEnd('/').substringAfterLast('/').replace('-', ' ')

        // Fact rows: <div><span>İngilizce adı</span><strong>…</strong></div>. "İ".lowercase()
        // is "i̇" (dotted), so match "ngilizce", not "ingilizce".
        val aliases = mutableListOf<String>()
        doc.select(".v3-anime-facts > div, .v3-anime-facts li, dl > div").forEach { row ->
            val label = (row.selectFirst("span, dt")?.text() ?: return@forEach).lowercase().trim().trimEnd(':')
            if (listOf("japonca", "japanese", "ngilizce", "english", "diğer ad", "other name", "romaji", "orijinal", "alternatif").none { label.contains(it) }) return@forEach
            val value = row.selectFirst("strong, dd")?.text()?.trim().orEmpty()
            value.split(",", "،", "/").map { it.trim() }.filter { it.isNotBlank() }.forEach { aliases += it }
        }
        ogTitle?.let { aliases += it }
        val cleanAliases = aliases.map { it.replace(izleRe, "").replace(izleTruncRe, "").trim() }
            .filter { it.length > 3 && !it.equals(title, ignoreCase = true) }.distinct().take(10)

        val poster = imageUrl(doc.selectFirst(".v3-anime-detail-cover img"))
            // Not "any img[src*=pcovers]": the page also shows related series (Frieren's 2nd
            // season), and those posters would win once the main cover's class changes.
            ?: absUrl(base, ld?.str("image"))
            ?: absUrl(base, doc.selectFirst("meta[property=og:image]")?.attr("content"))

        // The meta description is cut at ~160 chars, so it's the last resort.
        val plot = doc.selectFirst("p#v3-anime-summary-content, #v3-anime-summary-content")?.text()?.trim()?.ifBlank { null }
            ?: ld?.str("description")
            ?: doc.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")?.trim()?.ifBlank { null }

        // ".v3-anime-detail-meta" spans: "2023", "28 yayınlanmış bölüm", …
        val year = doc.select(".v3-anime-detail-meta span, .v3-anime-detail-meta li").map { it.text().trim() }
            .firstOrNull { yearRe.matches(it) && it.toInt() in 1950..2100 }?.toIntOrNull()

        val tags = doc.select(".v3-anime-detail-tags a").map { it.text().trim() }
            .ifEmpty { ld?.optJSONArray("genre")?.let { g -> (0 until g.length()).map { g.optString(it, "").trim() } } ?: emptyList() }
            .filter { it.isNotBlank() && it.length < 30 }.distinct().take(10)

        return Anime(title, poster, plot, year, tags, cleanAliases, episodes(doc, url))
    }

    /**
     * Every episode is in the HTML (the 12-per-group paging is client-side). Not selected by
     * a[href*=-bolum] (misses one-piece-41bolum-izle) and data-episode-number isn't used (an
     * internal sequence: it drifts from One Piece 956 on).
     */
    fun episodes(doc: Document, url: String): List<Episode> {
        val animeUrl = url.trimEnd('/')
        val links = doc.select("[data-anime-episode-grid] > a[href]")
            .ifEmpty { doc.select("#bolumler a[href]").filter { episodeHrefRe.containsMatchIn(it.attr("href")) } }
            // Last resort: any link that is this anime's URL plus an episode suffix.
            .ifEmpty { doc.select("a[href]").filter { it.attr("abs:href").startsWith("$animeUrl-") && episodeHrefRe.containsMatchIn(it.attr("href")) } }
            .distinctBy { it.attr("abs:href") }

        data class Raw(val href: String, val label: String, val special: Boolean)
        val raws = links.mapNotNull { el ->
            val href = el.attr("abs:href").ifBlank { return@mapNotNull null }
            // The link text is "1 1. Bölüm Sousou no Frieren · Türkçe altyazılı izle" — title only.
            val label = el.attr("data-episode-title").trim()
                .ifBlank { el.selectFirst("strong")?.text()?.trim().orEmpty() }
                .ifBlank { el.text().trim() }.ifBlank { return@mapNotNull null }
            val ll = label.lowercase()
            if (href.contains("fragman") || ll.contains("fragman")) return@mapNotNull null
            if (href.contains("-pv-") || ll == "pv" || ll.endsWith(" pv")) return@mapNotNull null
            // Previews ("1-2. Bölüm (Ön Gösterim)") and joined episodes would collide with or
            // shift the real numbers, so they get 0 and stay watchable.
            val special = rangeLabelRe.containsMatchIn(label) || ll.contains("önizleme") || ll.contains("ön gösterim") ||
                href.contains("onizleme") || href.contains("on-gosterim")
            Raw(href, label, special)
        }
        var regular = 0
        return raws.map { r ->
            val n = if (r.special) 0 else {
                regular++
                singleEpLabelRe.find(r.label)?.groupValues?.get(1)?.toIntOrNull()
                    ?: singleEpUrlRe.find(r.href.substringAfterLast('/'))?.groupValues?.get(1)?.toIntOrNull()
                    ?: regular // a count of regular episodes only, never raw list position
            }
            Episode(r.href, r.label, n)
        }
    }

    // ── Episode page → fansubs → sources ────────────────────────────────────

    /**
     * Fansub buttons: <button data-sources="/bolum/<epId>/cevirmen/<trId>" title="Fansub">.
     * Fallbacks: a button with only data-translator-id (URL built the way the page's JS does),
     * then any /bolum/<epId>/cevirmen/<trId> URL in the raw HTML.
     */
    fun translators(html: String, pageUrl: String): List<Translator> {
        val doc = Jsoup.parse(html, pageUrl)
        val out = LinkedHashMap<String, String>()
        val epId = doc.selectFirst("[data-episode-id]")?.attr("data-episode-id")?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        doc.select("button[data-sources], button[data-translator-id], [data-v3-translator]").forEach { e ->
            val trId = e.attr("data-translator-id").trim()
            val u = absUrl(pageUrl, e.attr("data-sources"))
                ?: if (epId != null && trId.isNotEmpty() && trId.all(Char::isDigit)) absUrl(pageUrl, "/bolum/$epId/cevirmen/$trId") else null
            if (u != null && u !in out) out[u] = e.attr("title").trim()
                .ifBlank { e.clone().also { it.select(".v3-translation-flag").remove() }.text().trim() }
                .ifBlank { "Fansub" }
        }
        if (out.isEmpty()) translatorUrlRe.findAll(html).forEach { m ->
            absUrl(pageUrl, m.value)?.let { if (it !in out) out[it] = "Fansub ${m.groupValues[2]}" }
        }
        return out.map { (u, n) -> Translator(u, n) }
    }

    /** /bolum/<epId>/cevirmen/<trId> JSON: {"success":true,"videos":[{"id","name"}]}. */
    fun videos(text: String): List<Video> {
        val arr: JSONArray = try {
            val t = text.trim()
            if (t.startsWith("[")) JSONArray(t) else {
                val o = JSONObject(t)
                o.optJSONArray("videos") ?: o.optJSONObject("data")?.optJSONArray("videos") ?: o.optJSONArray("data")
            }
        } catch (_: Exception) { null } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val v = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = (v.str("id") ?: v.str("video_id"))?.takeIf { it.all(Char::isDigit) } ?: return@mapNotNull null
            Video(id, (v.str("name") ?: v.str("source"))?.trim() ?: "Player")
        }.distinctBy { it.id }
    }
}
