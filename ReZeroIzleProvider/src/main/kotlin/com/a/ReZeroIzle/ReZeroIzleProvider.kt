package com.a.rezeroizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * ReZero İzle CloudStream 3 Provider
 *
 * Site structure:
 *   Homepage nav:   p.subtitle a[href] — lists all seasons and OVAs
 *   Season index:   <div class="hub-card"><ul><li><a href="...">
 *   Episode page:   /sezon/N/bolum/M.html or /sezon/N/ozel/SLUG.html; downloadBtn is "#"
 *                   until player.js fills it in
 *   JS data file:   seasons-data.js → SEASON_CONFIGS[N].episodeDriveIds[M-1] (+ episodeDriveIds2/3),
 *                   specials[].driveId for OVAs; read by SeasonsData
 */
class ReZeroIzleProvider : MainAPI() {

    override var mainUrl        = "https://rezeroizle.com"
    override var name           = "ReZero İzle"
    override var lang           = "tr"
    override val hasMainPage    = false
    override val supportedTypes = setOf(TvType.Anime, TvType.OVA)

    // ── Pre-compiled regex ────────────────────────────────────────────────────
    companion object {
        private val SEASON_NUM_RE    = Regex("""/sezon/(\d+)/""")
        private val EPISODE_HREF_RE  = Regex("""/(bolum|arabolum|ozel)/""")
        private val EPISODE_URL_RE   = Regex("""/sezon/(\d+)/bolum/(\d+)\.html""")
        private val SPECIAL_URL_RE   = Regex("""/sezon/(\d+)/ozel/([^/?#]+?)\.html""")
        private val GDRIVE_PARAM_RE = Regex("""[?&](?:amp;)?id=([A-Za-z0-9_-]{25,45})""")
        private val GDRIVE_URL_RE   = Regex("""drive\.google\.com/(?:uc|file/d)[?/][^\s"'<>]*?id[=/]([A-Za-z0-9_-]{25,45})""")

        private val UC_SIZE_RE      = Regex("""\(\s*([\d.,]+)\s*([KMGT])B?\s*\)""", RegexOption.IGNORE_CASE)

        private const val CACHE_TTL_MS = 300_000L  // 5 min
    }

    // ── Browser-like headers ──────────────────────────────────────────────────
    private val baseHeaders = mapOf(
        "User-Agent"                to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept"                    to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language"           to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.5",
        "Referer"                   to "$mainUrl/",
        "DNT"                       to "1",
        "Sec-Fetch-Dest"            to "document",
        "Sec-Fetch-Mode"            to "navigate",
        "Sec-Fetch-Site"            to "same-origin",
        "Upgrade-Insecure-Requests" to "1",
    )

    private val scriptHeaders = mapOf(
        "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept"           to "*/*",
        "Accept-Language"  to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.5",
        "Referer"          to "$mainUrl/",
        "Sec-Fetch-Dest"   to "script",
        "Sec-Fetch-Mode"   to "no-cors",
        "Sec-Fetch-Site"   to "same-origin",
    )

    // ── Caches ────────────────────────────────────────────────────────────────
    private val pageCache   = mutableMapOf<String, Pair<Long, org.jsoup.nodes.Document>>()
    private val scriptCache = mutableMapOf<String, Pair<Long, String>>()

    private suspend fun fetchDocument(url: String): org.jsoup.nodes.Document {
        val now = System.currentTimeMillis()
        pageCache[url]?.let { (ts, doc) ->
            if (now - ts < CACHE_TTL_MS) return doc
        }
        val doc = app.get(url, headers = baseHeaders).document
        pageCache[url] = now to doc
        return doc
    }

    private suspend fun fetchScript(url: String): String {
        val now = System.currentTimeMillis()
        scriptCache[url]?.let { (ts, text) ->
            if (now - ts < CACHE_TTL_MS) return text
        }
        val text = app.get(url, headers = scriptHeaders).text
        scriptCache[url] = now to text
        return text
    }

    // ── Known metadata (fallback posters & plots for known entries) ───────────
    private data class KnownMeta(
        val poster: String,
        val plot: String,
    )

    // Keyed by URL path suffix so it matches regardless of domain changes
    private val knownMeta = mapOf(
        "/sezon/1/index.html" to KnownMeta(
            poster = "$mainUrl/images/s1.jpg",
            plot   = "Subaru Natsuki başka bir dünyaya ışınlanır ve 'Ölümden Dönüş' yeteneğiyle hayatta kalmaya çalışır. (1. Sezon – 25 bölüm)",
        ),
        "/sezon/2/index.html" to KnownMeta(
            poster = "$mainUrl/images/s2.webp",
            plot   = "Subaru yeni tehditlerle yüzleşirken Rem gizemli bir uykuya dalar. (2. Sezon – 25 bölüm)",
        ),
        "/sezon/3/index.html" to KnownMeta(
            poster = "$mainUrl/images/s3.webp",
            plot   = "Re:Zero 3. Sezon – Türkçe altyazılı.",
        ),
        "/sezon/4/index.html" to KnownMeta(
            poster = "$mainUrl/images/s4.webp",
            plot   = "Re:Zero 4. Sezon – Türkçe altyazılı.",
        ),
        "/sezon/1/ozel/memory-snow.html" to KnownMeta(
            poster = "$mainUrl/images/s1.jpg",
            plot   = "Kış şenliği ve karlı bir gün. Subaru ile Emilia'nın tatlı kış macerası.",
        ),
        "/sezon/2/ozel/frozen-bond.html" to KnownMeta(
            poster = "$mainUrl/images/frozen-bond.webp",
            plot   = "Emilia'nın geçmişi ve Frozen Bond – Türkçe altyazılı OVA.",
        ),
    )

    // Resolve poster for unknown future entries by guessing image path
    private fun guessPoster(url: String): String {
        val seasonNum = SEASON_NUM_RE.find(url)?.groupValues?.get(1)
        return if (seasonNum != null) "$mainUrl/images/s$seasonNum.webp"
        else "$mainUrl/images/s1.jpg"
    }

    // ── Dynamic catalogue from homepage ──────────────────────────────────────
    // Cached list discovered from the homepage nav bar (p.subtitle a[href])
    private var discoveredCatalogue: List<SearchResponse>? = null
    private var catalogueTimestamp = 0L

    private suspend fun getCatalogue(): List<SearchResponse> {
        val now = System.currentTimeMillis()
        discoveredCatalogue?.let {
            if (now - catalogueTimestamp < CACHE_TTL_MS) return it
        }

        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        try {
            val doc = fetchDocument("$mainUrl/index.html")

            // Homepage nav: p.subtitle contains links to all seasons & OVAs
            doc.select("p.subtitle a[href]").forEach { el ->
                val href = el.attr("abs:href").ifBlank { el.attr("href") }
                if (!href.contains("/sezon/")) return@forEach
                if (!seen.add(href)) return@forEach

                val label = el.text().trim().ifBlank { return@forEach }
                val isOva = href.contains("/ozel/")
                val type = if (isOva) TvType.OVA else TvType.Anime

                // Build title: "Re:Zero – Label" or "Re:Zero N. Sezon"
                val title = if (isOva) "Re:Zero – $label (OVA)"
                    else "Re:Zero $label"

                val pathKey = href.removePrefix(mainUrl)
                val meta = knownMeta[pathKey]
                val poster = meta?.poster ?: guessPoster(href)

                results.add(
                    newAnimeSearchResponse(title, href, type) {
                        posterUrl = poster
                    }
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("ReZeroIzle", "Homepage fetch failed: ${e.message}")
        }

        // Fallback: if homepage failed or returned nothing, use hardcoded entries
        if (results.isEmpty()) {
            android.util.Log.w("ReZeroIzle", "Using hardcoded catalogue fallback")
            val fallback = listOf(
                Triple("Re:Zero 1. Sezon",           "$mainUrl/sezon/1/index.html",           TvType.Anime),
                Triple("Re:Zero 2. Sezon",           "$mainUrl/sezon/2/index.html",           TvType.Anime),
                Triple("Re:Zero 3. Sezon",           "$mainUrl/sezon/3/index.html",           TvType.Anime),
                Triple("Re:Zero 4. Sezon",           "$mainUrl/sezon/4/index.html",           TvType.Anime),
                Triple("Re:Zero – Memory Snow (OVA)", "$mainUrl/sezon/1/ozel/memory-snow.html", TvType.OVA),
                Triple("Re:Zero – Frozen Bond (OVA)", "$mainUrl/sezon/2/ozel/frozen-bond.html", TvType.OVA),
            )
            fallback.forEach { (title, url, type) ->
                val pathKey = url.removePrefix(mainUrl)
                val poster = knownMeta[pathKey]?.poster ?: guessPoster(url)
                results.add(
                    newAnimeSearchResponse(title, url, type) {
                        posterUrl = poster
                    }
                )
            }
        }

        discoveredCatalogue = results
        catalogueTimestamp = now
        return results
    }

    // ── Search ─────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase().trim()
        val keywords = listOf(
            "re", "zero", "rezero", "subaru", "emilia", "rem", "ram",
            "memory", "snow", "frozen", "bond", "sezon", "ova",
        )
        val isMatch = q.isBlank() || keywords.any { q.contains(it) }
        if (!isMatch) return emptyList()
        return getCatalogue()
    }

    // ── Load ──────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val pathKey = url.removePrefix(mainUrl)
        val meta    = knownMeta[pathKey]
        val poster  = meta?.poster ?: guessPoster(url)
        val plot    = meta?.plot

        // Derive a title from the URL if we don't have one
        val seasonNum = SEASON_NUM_RE.find(url)?.groupValues?.get(1)?.toIntOrNull()
        val isOva = url.contains("/ozel/")
        val type  = if (isOva) TvType.OVA else TvType.Anime
        val title = if (isOva) {
            val slug = url.substringAfterLast("/").removeSuffix(".html")
                .replace("-", " ")
                .replaceFirstChar { it.uppercase() }
            "Re:Zero – $slug (OVA)"
        } else {
            "Re:Zero ${seasonNum ?: ""}. Sezon"
        }

        if (isOva) {
            return newAnimeLoadResponse(title, url, type) {
                posterUrl = poster
                this.plot = plot
                this.tags = listOf("OVA", "Türkçe Altyazı")
                addEpisodes(DubStatus.Subbed, listOf(
                    newEpisode(url) { this.name = title; this.season = 1; this.episode = 1 }
                ))
            }
        }

        val doc = try {
            fetchDocument(url)
        } catch (e: Exception) {
            android.util.Log.e("ReZeroIzle", "Season $seasonNum fetch failed: ${e.message}")
            return newAnimeLoadResponse(title, url, type) {
                posterUrl = poster; this.plot = plot
                addEpisodes(DubStatus.Subbed, emptyList())
            }
        }

        val episodes = mutableListOf<Episode>()
        var counter = 0
        val pageBaseDir = url.substringBeforeLast("/") + "/"
        val seen = mutableSetOf<String>()

        // Primary selector — site's known structure
        var elements = doc.select("div.hub-card ul li a[href]")

        // Fallback — if site restructured, grab all links and filter by URL pattern
        if (elements.isEmpty()) {
            elements = doc.select("a[href]")
        }

        elements.forEach { el ->
            val rawHref = el.attr("href").ifBlank { return@forEach }

            val href = el.attr("abs:href").ifBlank {
                when {
                    rawHref.startsWith("http") -> rawHref
                    rawHref.startsWith("/")    -> "$mainUrl$rawHref"
                    else                       -> pageBaseDir + rawHref
                }
            }.ifBlank { return@forEach }

            // Only keep episode pages; deduplicate ("İzlemeye Başla" repeats first ep)
            if (!EPISODE_HREF_RE.containsMatchIn(href)) return@forEach
            if (!seen.add(href)) return@forEach

            val label = el.text().trim().ifBlank { return@forEach }

            counter++
            episodes.add(
                newEpisode(href) {
                    this.name    = label
                    this.season  = 1
                    this.episode = counter
                }
            )
        }

        android.util.Log.d("ReZeroIzle", "Season $seasonNum: ${episodes.size} episodes loaded")

        return newAnimeLoadResponse(title, url, type) {
            posterUrl  = poster
            this.plot  = plot
            this.tags  = listOf("İsekai", "Fantazi", "Aksiyon", "Türkçe Altyazı")
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ── Load links ────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        android.util.Log.d("ReZeroIzle", "loadLinks url=$data")

        // The episode HTML ships with downloadBtn href="#"; the site's player.js fills it
        // in from seasons-data.js. Read the page only to find that script's URL.
        val doc = try {
            fetchDocument(data)
        } catch (e: Exception) {
            android.util.Log.w("ReZeroIzle", "Episode fetch error: ${e.message}")
            null
        }
        val dataJsUrl = doc?.select("script[src]")
            ?.map { it.attr("abs:src") }
            ?.firstOrNull { it.contains("seasons-data") }
            ?: "$mainUrl/seasons-data.js"

        val ids = try {
            val js = fetchScript(dataJsUrl)
            val episode = EPISODE_URL_RE.find(data)
            val special = SPECIAL_URL_RE.find(data)
            when {
                episode != null -> SeasonsData.episodeIds(
                    js, episode.groupValues[1].toInt(), episode.groupValues[2].toInt(),
                )
                special != null -> SeasonsData.specialIds(
                    js, special.groupValues[1].toInt(), special.groupValues[2],
                )
                else -> emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.w("ReZeroIzle", "seasons-data.js failed: ${e.message}")
            emptyList()
        }.toMutableList()

        // Fallback: a real Drive link in the page itself, should the site ever inline one.
        if (ids.isEmpty() && doc != null) {
            val dlHref = doc.selectFirst("a#downloadBtn[href]")?.attr("href").orEmpty()
            val html = doc.outerHtml()
            (GDRIVE_PARAM_RE.find(dlHref) ?: GDRIVE_URL_RE.find(html) ?: GDRIVE_PARAM_RE.find(html))
                ?.let { ids.add(it.groupValues[1]) }
        }

        if (ids.isEmpty()) {
            android.util.Log.w("ReZeroIzle", "No GDrive ID for $data (episode not uploaded yet?)")
            return false
        }

        ids.forEachIndexed { i, fileId ->
            val drive = resolveDrive(fileId)
            android.util.Log.d("ReZeroIzle", "GDrive fileId[$i]=$fileId size=${drive.sizeBytes}")
            val label = if (i == 0) "Google Drive" else "Google Drive ${i + 1}"
            callback(
                newExtractorLink(
                    source = name,
                    name   = label + formatSize(drive.sizeBytes),
                    url    = drive.url,
                    type   = ExtractorLinkType.VIDEO,
                ) {
                    quality = Qualities.Unknown.value
                    referer = "https://drive.google.com/"
                    headers = drive.headers
                }
            )
        }
        return true
    }

    // ── Google Drive: playable URL and file size ─────────────────────────────
    private data class DriveLink(val url: String, val headers: Map<String, String>, val sizeBytes: Long?)

    // One ranged byte from Drive's download endpoint gives the size in Content-Range.
    // Files past Google's virus-scan limit answer with an interstitial page instead; its
    // "(1.4G)" text gives the size, and submitting its download form (as Anizm does) gives
    // a URL that serves the video. confirm=t is the last resort, as before.
    private suspend fun resolveDrive(fileId: String): DriveLink {
        val base = "https://drive.usercontent.google.com/download?id=$fileId&export=download"
        val ua = baseHeaders.getValue("User-Agent")
        val fallback = DriveLink("$base&confirm=t", emptyMap(), null)
        val h = mapOf("User-Agent" to ua, "Range" to "bytes=0-0")
        return try {
            val r1 = app.get(base, headers = h, timeout = 15)
            if (!isHtml(r1)) return DriveLink(base, emptyMap(), rangedSize(r1))

            val gdoc = org.jsoup.Jsoup.parse(r1.text, r1.url)
            val pageSize = gdoc.selectFirst(".uc-name-size")?.text()?.let { UC_SIZE_RE.find(it) }?.let { m ->
                val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let null
                val mult = when (m.groupValues[2].uppercase()) { "K" -> 1L shl 10; "M" -> 1L shl 20; "G" -> 1L shl 30; else -> 1L shl 40 }
                (n * mult).toLong()
            }
            val form = gdoc.selectFirst("form#download-form")
                ?: gdoc.select("form").firstOrNull { it.attr("action").contains("download") || it.selectFirst("input[name=id]") != null }
            if (form != null) {
                val action = form.attr("abs:action").ifBlank { "https://drive.usercontent.google.com/download" }
                val params = form.select("input[name]").associate { it.attr("name") to it.attr("value") }
                    .let { if ("id" !in it) it + ("id" to fileId) else it }
                val query = params.entries.joinToString("&") {
                    "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
                }
                val url = if (action.contains('?')) "$action&$query" else "$action?$query"
                val cookie = r1.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                val playHeaders = mapOf("User-Agent" to ua) + (if (cookie.isNotBlank()) mapOf("Cookie" to cookie) else emptyMap())
                val r2 = app.get(url, headers = h + playHeaders, timeout = 15)
                if (!isHtml(r2)) return DriveLink(url, playHeaders, rangedSize(r2) ?: pageSize)
                android.util.Log.w("ReZeroIzle", "GDrive $fileId: still HTML after the download form")
            }
            fallback.copy(sizeBytes = pageSize)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ReZeroIzle", "GDrive $fileId probe failed: ${e.message}")
            fallback
        }
    }

    private fun isHtml(r: com.lagradost.nicehttp.NiceResponse) =
        (r.headers["Content-Type"] ?: "").contains("text/html", ignoreCase = true)

    // Reads the size and closes the response, so the 1-byte body is never left open.
    private fun rangedSize(r: com.lagradost.nicehttp.NiceResponse): Long? {
        val size = when (r.code) {
            206 -> r.headers["Content-Range"]?.substringAfterLast('/')?.trim()?.toLongOrNull()
            200 -> r.headers["Content-Length"]?.toLongOrNull()
            else -> null
        }
        try { r.okhttpResponse.close() } catch (_: Exception) {}
        return size
    }

    // " [1.3GB]" / " [350MB]", the same format as Anizm.
    private fun formatSize(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return ""
        val gb = bytes / 1_073_741_824.0
        return if (gb >= 1) " [%.1fGB]".format(gb) else " [%.0fMB]".format(bytes / 1_048_576.0)
    }
}
