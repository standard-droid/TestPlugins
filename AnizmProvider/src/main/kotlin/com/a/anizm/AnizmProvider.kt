package com.a.anizm

import android.os.Looper
import android.view.View
import android.net.http.SslError
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
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/** v44: release a response we only needed the status or headers of. */
internal fun NiceResponse.closeQuietly() { try { okhttpResponse.close() } catch (_: Exception) {} }

class AnizmProvider(internal val settings: AnizmSettings) : MainAPI() {

    // v11: lets the settings screen run the connection self-test on demand, instead of the
    // user having to force-stop the app and catch the one automatic run in a logcat.
    companion object {
        @Volatile internal var instance: AnizmProvider? = null
        @Volatile internal var lastProbeTarget: Pair<String, String>? = null // numId, episode url
    }
    init {
        instance = this
        // v48: a page that could only be read through a fallback is a site change in the making.
        AnizmParser.onFallback = { what -> logW("site-change warning: $what") }
    }

    /** "Clear saved data" in the settings: everything kept to save requests, in memory and on disk. */
    internal fun clearSavedData() {
        embedCache.clear(); embedStore.clear(); videoCredits.clear(); mediaStore.clear(); sniffDeadHosts.clear()
        sourceListCache.clear(); aincradCache.clear(); aincradLocked.clear(); playlistTexts.clear(); sniffCache.clear()
        driveStreamCache.clear(); sistennGone.clear(); sniffHostFails.clear(); sniffHostBlockedUntil.clear()
        log("settings: saved data cleared")
    }

    /** Called from the settings screen. Returns the report lines (also written to logcat). */
    // v37: "Site check" in the settings. Runs every step on one known anime and reports what
    // each one found, so a site change can be pinned to a step from the phone (where Cloudflare
    // lets requests through) without a browser session. Read-only: the same requests as browsing.
    suspend fun runSiteCheck(slug: String = "sousou-no-frieren", query: String = "frieren"): List<String> {
        // v39: no version number here: it was typed in by hand and went stale on every bump
        // (build.gradle.kts has it; the Extensions screen shows it).
        val out = mutableListOf("Site: $mainUrl · ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}")
        // ok = true ✓, false ✗, null ⚠ (worked around by a fallback)
        suspend fun step(what: String, block: suspend () -> Pair<Boolean?, String>) {
            val (ok, detail) = try { block() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { false to "${e.javaClass.simpleName}: ${e.message}" }
            out += (when (ok) { true -> "✓ "; false -> "✗ "; null -> "⚠ " }) + "$what: $detail"
        }
        step("Home rows") {
            val doc = siteDocument("$mainUrl/", pageHeaders) ?: return@step false to "page didn't load"
            val rows = listOf("son-bolumler", "populer", "yeni-seriler").map { it to AnizmParser.homeRow(doc, it) } +
                ("banners" to AnizmParser.heroSlides(doc))
            rows.all { (_, r) -> r.isNotEmpty() && r.all { it.poster != null } } to
                rows.joinToString { (id, r) -> "$id ${r.size} (${r.count { it.poster == null }} without poster)" }
        }
        step("Latest episodes (/yeniler)") {
            val doc = siteDocument("$mainUrl/yeniler?sayfa=1", pageHeaders) ?: return@step false to "page didn't load"
            val l = AnizmParser.listing(doc, 1)
            (l.cards.isNotEmpty() && l.hasNext) to "${l.cards.size} anime, next page: ${l.hasNext}"
        }
        step("Search API (/searchAnime)") {
            val h = searchApi(query)
            (if (h.isNotEmpty()) true else null) to "${h.size} results" + (if (h.isEmpty()) " (it errors on some queries; /arama covers that)" else "")
        }
        step("Search page (/arama)") { val c = searchHtml(query); c.isNotEmpty() to "${c.size} results" }
        step("Search suggestions") { val c = searchSuggest(query); (if (c.isNotEmpty()) true else null) to "${c.size} results" }
        var episodeUrl: String? = null
        step("Anime page") {
            val url = "$mainUrl/$slug"
            val doc = siteDocument(url, pageHeaders) ?: return@step false to "page didn't load"
            val a = AnizmParser.anime(doc, url)
            episodeUrl = (a.episodes.firstOrNull { it.number == 1 } ?: a.episodes.firstOrNull())?.url
            val namesOk = a.episodes.none { it.name.contains("altyaz", ignoreCase = true) || it.name.length > 40 }
            (a.episodes.isNotEmpty() && namesOk && a.poster != null && a.plot != null) to
                "'${a.title}', ${a.episodes.size} episodes (first '${a.episodes.firstOrNull()?.name}'), poster ${if (a.poster != null) "yes" else "MISSING"}, " +
                "plot ${a.plot?.length ?: 0} chars, year ${a.year}, ${a.tags.size} genres, ${a.aliases.size} other names"
        }
        val ep = episodeUrl ?: return out.also { it += "✗ Stopped here: no episode to test" }
        var translators = emptyList<AnizmParser.Translator>()
        step("Episode page → fansubs") {
            val html = siteText(ep, pageHeaders) ?: return@step false to "page didn't load"
            noteCsrfToken(html)
            translators = AnizmParser.translators(html, onCurrentHost(ep))
            translators.isNotEmpty() to "${translators.size}: ${translators.joinToString { it.name }}"
        }
        var firstVideo: AnizmParser.Video? = null
        val allVideos = ArrayList<Pair<String, AnizmParser.Video>>() // fansub, video
        for (t in translators) step("Sources · ${t.name}") {
            val text = siteText(t.url, episodeXhrHeaders(ep)) ?: return@step false to "no answer"
            val v = AnizmParser.videos(text)
            v.forEach { allVideos += t.name to it }
            if (firstVideo == null) firstVideo = v.firstOrNull()
            v.isNotEmpty() to "${v.size}: ${v.joinToString { it.name }}"
        }
        firstVideo?.let { v ->
            step("Player redirect · ${v.name} (/player/${v.id})") {
                val embed = resolveViaRedirect(v.id, ep)
                (embed != null) to (embed?.take(90) ?: "no redirect target")
            }
        }
        // v48: one source of each of the three players this extension reads itself, all the way to
        // its links, so "the site is fine but Aincrad/Beta/Drive stopped" shows up here too.
        for (key in listOf("aincrad", "beta", "gdrive")) {
            val v = allVideos.firstOrNull { settings.groupOf(it.second.name).key == key } ?: continue
            step("Links · ${v.second.name} (${v.first})") {
                delay(600)
                val embed = getCached(v.second.id) ?: resolveViaRedirect(v.second.id, ep)
                    ?: return@step false to "no redirect target"
                val links = java.util.Collections.synchronizedList(ArrayList<String>())
                val ok = withTimeoutOrNull(30_000L) {
                    processSource(VidInfo(v.second.id, v.second.name, v.first), embed, ep, { links += it.name }, {}, null)
                }
                val expected = mapOf("aincrad" to "ap:", "beta" to "bp:", "gdrive" to "gd:")[key]!!
                (if (ok == true && embed.startsWith(expected)) true else if (ok == true) null else false) to
                    "${embed.substringBefore(':')} → " + (if (links.isEmpty()) (if (ok == null) "timed out" else "no links") else links.joinToString())
            }
        }
        settings.recentWarnings().takeIf { it.isNotEmpty() }?.let { w -> out += "Recent site warnings:"; out += w.takeLast(8).map { "⚠ $it" } }
        return out
    }

    suspend fun runConnectionTest(): List<String> {
        val (nid, episodeUrl) = lastProbeTarget
            ?: return listOf("Open an episode first (so there is a player id to test), then run this again.")
        val out = mutableListOf<String>()
        diagnosedThisSession = false
        diagnoseDirectBlock(nid, episodeUrl) { out += it }
        val blocked = System.currentTimeMillis() < playerHttpBlockedUntil
        out += if (blocked) "Currently using the WebView fallback." else "Direct lookups are in use."
        return out
    }

    override var mainUrl    = "https://anizm.net"
    override var name       = "Anizm"
    override var lang       = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    @Volatile
    internal var playerBase = "https://anizmplayer.com"
    // Derived, not hardcoded — if the site ever moves domains, only mainUrl needs updating;
    // this and every check that uses it follow automatically instead of silently going stale.
    // v40: a getter, not lazy, so it follows a domain move (below).
    internal val mainHost: String get() = android.net.Uri.parse(mainUrl).host ?: "anizm.net"

    // v40: domain moves. Turkish sites get blocked and move (anizm.net → anizm.tv, anizm2.net…),
    // usually leaving a redirect behind. When an anizm request ends up on another anizm host, that
    // host becomes mainUrl and is remembered across restarts; URLs saved under an old host
    // (bookmarks, history, episode data) are sent to the current one. Only hosts named
    // "anizm<digits>.<tld>" count — never anizmplayer.com or a block/notice page.
    internal val siteHostRe = Regex("""^(?:www\.)?anizm\d*\.[a-z]{2,10}(?:\.[a-z]{2,3})?$""")
    init {
        settings.siteHost?.takeIf { siteHostRe.matches(it) }?.let { mainUrl = "https://$it" }
    }
    internal fun onCurrentHost(url: String): String {
        val host = try { java.net.URI(url).host } catch (_: Exception) { null } ?: return url
        if (host == mainHost || !siteHostRe.matches(host)) return url
        return url.replaceFirst("//$host", "//$mainHost")
    }
    internal fun noteFinalHost(requested: String, r: NiceResponse) {
        val finalHost = try { r.okhttpResponse.request.url.host } catch (_: Exception) { return }
        val reqHost = try { java.net.URI(requested).host } catch (_: Exception) { null }
        if (r.code !in 200..299 || finalHost == mainHost || reqHost != mainHost || !siteHostRe.matches(finalHost)) return
        log("site: $mainHost now redirects to $finalHost — using $finalHost from now on")
        mainUrl = "https://$finalHost"
        try { settings.siteHost = finalHost } catch (_: Exception) {}
    }
    // Class-level, not per-loadLinks: every fansub group's GDrive source hits the same
    // Google endpoint regardless of episode, so two overlapping loadLinks calls (prefetch
    // + manual click) sharing this matters here in a way it doesn't for the other host
    // types below, each of which is spread across many different, unrelated domains.
    // Google's abuse detection is known to be sensitive to bursty interstitial requests —
    // it's the entire reason resolveGDrive()'s multi-step dance exists — so this stays at
    // 1, not the 5 used for general step4 work.
    internal val gdriveGate = Semaphore(1)
    // v13: this runs on Android, so it now says so — and says it with THIS device's real
    // browser identity instead of an invented one. The UA is taken from the system WebView
    // (minus the "; wv" marker, so it reads as Chrome rather than an embedded WebView), which
    // means the Chrome version is whatever is actually installed and never goes stale.
    //
    // Why it matters beyond honesty: the WebView fallback runs real JavaScript on the page,
    // where Cloudflare can read navigator.userAgentData, the platform, screen and touch
    // support. Claiming "Chrome on Windows" from an Android tablet contradicts all of that,
    // and header/JS disagreement is exactly what bot scoring looks for. One identity now:
    // same UA on OkHttp and the WebView, client hints derived from it.
    internal val ua: String by lazy {
        val fromSystem = try {
            val ctx = com.lagradost.cloudstream3.CloudStreamApp.context
            // v18: also drop the two tokens only a WebView carries — "; wv" and "Version/4.0" —
            // and the device's Build/… tag. Real Chrome on Android sends neither, and the log
            // showed we were still announcing "Version/4.0 Chrome/153" (i.e. "I am a WebView").
            if (ctx != null) WebSettings.getDefaultUserAgent(ctx)
                ?.replace("; wv", "")?.replace(" wv)", ")")
                ?.replace(Regex(""" Build/[^);]+"""), "")
                ?.replace(Regex("""Version/\d+\.\d+ """), "")
                ?.replace(Regex("""\s+"""), " ")?.trim()
            else null
        } catch (_: Throwable) { null }
        val resolved = fromSystem?.takeIf { it.contains("Chrome/") && it.contains("Android") }
            ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36"
        log("ua: $resolved")
        resolved
    }
    internal val chromeMajor: String by lazy {
        Regex("""Chrome/(\d+)""").find(ua)?.groupValues?.get(1) ?: "152"
    }
    // Old stub system: CloudflareKiller is directly on the compile classpath again
    // (it's part of the full pre-release APK), no reflection needed.
    internal val cfKiller = CloudflareKiller()
    // 4.0: CSRF/session machinery removed. Verified on the live site (2026-09-17):
    // /searchAnime answers identically with or without _token, and nothing else used the
    // token — so every getSession() was a full homepage download (every 5 min, before
    // search/load/loadLinks) for nothing, and search leaked the token into a query string.
    // What replaced it: siteGet() below, which handles the one thing that matters —
    // Cloudflare — and does it on the actual request instead of on the homepage.
    @Volatile internal var cfUntil = 0L
    internal val cfStickyMs = 10 * 60 * 1000L
    internal val cfSolveBudgetMs = 20_000L
    // v5: set when anizm refuses a direct (OkHttp) /player/ lookup. While set, lookups go
    // straight to the in-app WebView resolver — a real browser engine — instead of
    // retrying a request that's being blocked (and adding to whatever triggered the block).
    @Volatile internal var playerHttpBlockedUntil = 0L
    internal val playerHttpBlockCooldownMs = 15 * 60 * 1000L

    // ── Tuning: stealth vs. speed ────────────────────────────────────────────
    // (1) Pacing of /player/{id} lookups: one at a time, and successive request starts are spaced
    // by a random gap in [min, max] (0.5-1.2 s). A person switching players doesn't open three
    // within a second. Worst case for a 16-source episode with lazy loading off: ~16 x 0.85 s.
    internal val resolveGapMinMs = 500L
    internal val resolveGapMaxMs = 1200L
    // One gate for the whole provider, so overlapping waves (and loads) share the limit.
    internal val resolveGate = Semaphore(1)

    // (2) Which players to use, (4) lazy loading and the size estimate are user settings
    // now — see AnizmSettings (Extensions screen → Anizm → settings). Read on every call.
    // Lazy waves grow: 3 sources, then 6, then 12… so an old episode where only one
    // obscure source still works doesn't crawl through it 3 at a time.
    internal val lazyFirstWaveSize = 3

    // The size estimate's extra requests (see analyseStream). User setting.
    internal val estimateHlsSizes get() = settings.estimateSizes
    // v7: 4s → 8s. Real-device log: an Aincrad source came up without a size on first load
    // (budget ran out on a cold connection) and with one on the next.
    // v48: 8 s -> 6 s. All qualities are read together now, and a video is only measured once.
    internal val sizeEstimateBudgetMs = 6_000L

    internal val aincradCache = java.util.concurrent.ConcurrentHashMap<String, AincradSource>()
    // v46: master URL + text hash -> (local top-quality-only master URL, when registered).
    internal val aincradLocked = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    internal val expiresParamRe = Regex("""[?&]expires=(\d{9,13})""")
    // Sizes per video (ap:<hash>, bp:<hash>, sistenn:<host>:<id>:<kind>) are kept across restarts:
    // see mediaStore / cachedSizes / storeSizes in the size section.

    // 4.0: short cache of an episode's source list. Prefetch + manual click (or reopening
    // the source picker) used to re-download the ~890KB episode page and every translator
    // XHR each time; embedCache only covered the step after that.
    internal val sourceListCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<VidInfo>, Long>>()
    internal val sourceListTtlMs = 10 * 60 * 1000L
    // v9: last loadLinks time per episode, for the reload detection in loadLinks.
    internal val lastLoadAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    internal val reloadMinGapMs = 5_000L      // below this it's the app's own preload + open
    internal val reloadWindowMs = 30 * 60 * 1000L

    // Hash cache — numId → embed string, with timestamps for TTL
    // Hashes are content-based (not session-based) so safe to cache
    // Was local to loadLinks; now needed at class level so processSource() can take it
    // as a parameter — loadLinks dispatches each source to it as soon as that source's
    // numId resolves, instead of waiting for every source in the episode to resolve first.
    // v39: epId = the episode id from the fansub list URL (/bolum/<epId>/cevirmen/…), for playerUrlFor.
    internal val bolumIdRe = Regex("""/bolum/(\d+)/""")
    // v48: 30 min -> 24 h, and kept across restarts. Which player an id points at doesn't change
    // (the hash names the upload), so opening an episode again costs no /player/ lookups. A reload
    // (see loadLinksWork) still drops them. Stored as embed, the host it was found on, and the
    // source's credits line, tab-separated.
    internal val cacheTtlMs = 24 * 60 * 60 * 1000L
    internal val embedCache = TtlCache<String, String>(400, cacheTtlMs)
    internal val embedStore by lazy { PersistentCache(settings.prefs, "embeds", 600, cacheTtlMs) }
    // Who made a source (translator, encoder, uploader), by player id; see fetchVideoInfo.
    internal val videoCredits = TtlCache<String, String>(600, cacheTtlMs)

    // 2.12: short cache for getMainPage — the home/list rows don't change second to
    // second, and CloudStream re-calls getMainPage on things like tab switches and
    // pull-to-refresh far more often than the underlying page content actually changes.
    internal val mainPageCache = java.util.concurrent.ConcurrentHashMap<String, Pair<HomePageResponse, Long>>()
    internal val mainPageCacheTtlMs = 3 * 60 * 1000L // 3 minutes — short on purpose, this is a burst-of-taps guard, not a real cache
    internal val mainPageCacheLock = Any()

    internal fun getCached(numId: String): String? {
        embedCache[numId]?.let { return it }
        val parts = embedStore.get(numId)?.split('\t') ?: return null
        val embed = parts[0].takeIf { it.length > 3 } ?: return null
        parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { origin ->
            if (embed.startsWith("bp:")) betaOrigins[embed.removePrefix("bp:")] = origin
            if (embed.startsWith("ap:")) apDomains[embed.removePrefix("ap:")] = origin
        }
        parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { if (videoCredits[numId] == null) videoCredits[numId] = it }
        embedCache[numId] = embed
        return embed
    }
    internal fun putCache(numId: String, embed: String) {
        embedCache[numId] = embed
        val origin = when {
            embed.startsWith("bp:") -> betaOrigins[embed.removePrefix("bp:")]
            embed.startsWith("ap:") -> apDomains[embed.removePrefix("ap:")]
            else -> null
        }.orEmpty()
        embedStore.put(numId, listOf(embed, origin, videoCredits[numId].orEmpty()).joinToString("\t") { it.replace('\t', ' ') })
    }
    /** A reload: forget where this id points and what was measured about its video. */
    internal fun dropCached(numId: String) {
        val embed = embedCache.remove(numId) ?: embedStore.get(numId)?.substringBefore('\t')
        embedStore.remove(numId)
        videoCredits.remove(numId)
        if (embed == null) return
        if (embed.startsWith("ap:")) aincradCache.remove(embed.removePrefix("ap:"))
        if (embed.startsWith("ap:") || embed.startsWith("bp:")) mediaStore.remove(embed)
    }

    // Pre-compiled regexes — avoid recompilation in hot paths
    internal val apRe = Regex("""(https?://[a-z0-9]*player[a-z0-9]*\.[a-z.]+)/(?:video|player)/([a-f0-9]{24,40})""", RegexOption.IGNORE_CASE)
    internal val gdRe = Regex("""drive\.google\.com/(?:file/d/|uc\?[^"]*id=|open\?[^"]*id=)([A-Za-z0-9_-]{20,})""")
    // Beta Player: /player/{numId} redirects to pl.puffytr.tr/watch/{hash} — a genuinely
    // new backend, unrelated to Aincrad/anizmplayer.com despite similar button styling.
    // v39: the host is kept too (group 1), so a move to another puffytr.tr subdomain is followed.
    internal val puffytrRe = Regex("""(https?://[a-z0-9.-]*puffytr\.tr)/watch/([a-f0-9]+)""", RegexOption.IGNORE_CASE)
    // Master parsing: resolution + bandwidth + the variant URI on the line after — shared
    // between Aincrad's HLS master and Beta Player's master.txt, same format either way.
    internal val audioMediaUriRe = Regex("""#EXT-X-MEDIA:[^\n]*TYPE=AUDIO[^\n]*URI="([^"]+)"""")
    // Hosts CloudStream ships extractors for. Anything matching gets forwarded to
    // loadExtractor(), so upstream maintains them — new host on anizm = add one keyword.
    internal val extractorHostKeywords = listOf(
        "voe", "sibnet", "dood", "vidmoly", "ok.ru", "okru", "odnoklassniki",
        "sendvid", "mp4upload", "uqload", "hdvid", "abyss")
    // csrfRe1/csrfRe2 removed (3.1) — was two regexes to handle attribute-order variation
    // (name-then-content vs content-then-name). Jsoup's selector doesn't care about
    // attribute order at all, so one query replaces both, and can't break the same way
    // if the site ever reorders these attributes again.
    internal val adsRe = Regex("""\([Rr]eklamsız\)""")
    internal val qualityTagRe = Regex("""\b\d{3,4}[pP]\b""")
    internal val urlRe = Regex("""https?://[^\s"'<>\\]+""")
    // 1.9: remaining inline regexes, hoisted with the plan's requested names
    internal val emptyParenRe = Regex("""\(\s*\)""")
    internal val multiSpaceRe = Regex("""\s{2,}""")
    internal val asciiOnlyRe = Regex("^[\\x20-\\x7E]+$")
    // gdriveConfirmRe/gdriveUuidRe removed (3.1) — same reasoning as the CSRF regexes:
    // Jsoup's input[name=...] selector doesn't care about attribute order, and is also
    // more tolerant of the deliberately-truncated HTML this fetch produces (Range-limited).

    // 1.5: single shared cleaner for every path that displays a source name, so quality
    // text can never appear twice (the original "1080p 1080p" bug) or leak through
    // unstripped in whichever path nobody's tested most recently. Intentionally dumb:
    // strips quality tokens and formatting debris only, never touches fansub/title text.
    internal fun cleanDisplayName(raw: String): String = raw
        .replace(qualityTagRe, "")
        .replace(emptyParenRe, "")
        .replace(multiSpaceRe, " ")
        .trim().trimEnd('-', ' ').trim()

    // Reusable empty response — avoid allocations in shouldInterceptRequest (called 100s of times)
    internal val emptyBytes = ByteArray(0)
    internal fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(emptyBytes))

    // v48: the last lines are also kept in memory, so "Copy recent log" in the settings can hand
    // them over without a computer and adb. Warnings that say the site changed are kept across
    // restarts (the last 20) and shown in the settings and the site check.
    internal val recentLog = java.util.ArrayDeque<String>()
    internal val logClock = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
    internal fun remember(level: String, msg: String) {
        synchronized(recentLog) {
            recentLog.addLast("${logClock.format(java.util.Date())} $level $msg")
            while (recentLog.size > 600) recentLog.removeFirst()
        }
    }
    internal fun recentLogText(): String = synchronized(recentLog) { recentLog.joinToString("\n") }
    internal fun log(msg: String) { android.util.Log.i("Anizm", msg); remember("I", msg) }
    internal fun logW(msg: String) {
        android.util.Log.w("Anizm", msg); remember("W", msg)
        if (msg.startsWith("site-change")) try { settings.addWarning(msg.removePrefix("site-change warning: ")) } catch (_: Throwable) {}
    }

    internal fun isCf(html: String) = html.contains("Just a moment", true) ||
        html.contains("cf-browser-verification", true) ||
        html.contains("cf-turnstile", true) ||
        html.contains("/cdn-cgi/challenge-platform/h/", true)
        // 4.0: was bare "challenge-platform". Every normal anizm page now embeds
        // /cdn-cgi/challenge-platform/scripts/precursor/main.js (passive bot-management
        // JS), so that marker matched on every successful response. Interstitials load
        // from .../challenge-platform/h/... instead. Still NOT matching bare "turnstile".

    // v5: a 403/503 from a Cloudflare server is NOT enough to call CloudflareKiller any
    // more. Real-device log (v4): anizm answered OkHttp's /player/ requests with an error
    // CloudflareKiller can't solve — it opened a WebView per request, waited the full 60s for
    // a cf_clearance cookie that never came, and loadLinks hit CloudStream's 120s limit
    // with nothing. Only an actual challenge (cf-mitigated: challenge, or a challenge page
    // body) is worth solving; a plain block is handled by the caller instead.
    internal fun looksCfBlocked(r: NiceResponse): Boolean {
        val challengeHeader = r.headers["cf-mitigated"]?.contains("challenge", true) == true
        return when (r.code) {
            403, 429, 503 -> challengeHeader || isCf(r.text)
            // 3xx: never read the body of a redirect we deliberately didn't follow
            in 300..399 -> false
            else -> r.code in 200..299 && r.text.length < 60_000 && isCf(r.text)
        }
    }

    // v44: shared small helpers.
    internal val acceptLang = "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    internal val wsRe = Regex("""\s+""")
    /** A response body (or any text) on one line for a log, without rewriting all of it first. */
    internal fun oneLine(text: String, max: Int): String = wsRe.replace(text.take(max * 2), " ").take(max)

    // ── Chrome's network stack (v41) ─────────────────────────────────────────
    // The UA and client hints say "Chrome on Android", but OkHttp's TLS handshake (cipher list,
    // extensions, ALPN) and HTTP/2 settings are plainly not Chrome's: the mismatch Cloudflare's
    // bot scoring (JA3/JA4, HTTP/2 fingerprint) exists to catch. CloudStream already ships
    // Chrome's own network stack (Cronet from Google Play services; the device log shows
    // "Cronet version: 151…", and ExoPlayer plays through it). Requests to the sites that can
    // judge us (anizm, the players' APIs, Google Drive) now go through it. It is reached by
    // reflection, so there is no build dependency on it; if it is missing or fails, the request
    // goes out through OkHttp as before.
    internal val cronetEngine: Any? by lazy {
        try {
            val ctx = com.lagradost.cloudstream3.CloudStreamApp.context ?: return@lazy null
            val bc = Class.forName("org.chromium.net.CronetEngine\$Builder")
            val b = bc.getConstructor(android.content.Context::class.java).newInstance(ctx)
            for (m in listOf("enableHttp2", "enableQuic", "enableBrotli"))
                try { bc.getMethod(m, Boolean::class.javaPrimitiveType).invoke(b, true) } catch (_: Throwable) {}
            try { bc.getMethod("setUserAgent", String::class.java).invoke(b, ua) } catch (_: Throwable) {}
            val engine = bc.getMethod("build").invoke(b)
            val ver = try { engine?.javaClass?.getMethod("getVersionString")?.invoke(engine) } catch (_: Throwable) { null }
            log("net: Chrome network stack ready (${ver ?: "?"})")
            engine
        } catch (t: Throwable) { log("net: Chrome network stack unavailable (${t.javaClass.simpleName}: ${t.message?.take(80)}) — using OkHttp"); null }
    }
    internal val cronetOpen: java.lang.reflect.Method? by lazy {
        try { Class.forName("org.chromium.net.CronetEngine").getMethod("openConnection", java.net.URL::class.java) } catch (_: Throwable) { null }
    }
    // Five failures in a row (not timeouts) and the Chrome stack is left alone for 10 minutes.
    // v48: it used to stay off until the app was restarted, so a moment without a connection
    // switched every later request to OkHttp's handshake under a Chrome user agent. Failures to
    // reach the network at all (no route, DNS) don't count: OkHttp fails on those just the same.
    internal val cronetFailures = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile internal var cronetOffUntil = 0L
    internal fun noteCronetFailure(t: Throwable?) {
        if (t is java.net.UnknownHostException || t is java.net.ConnectException || t is java.net.NoRouteToHostException) return
        if (cronetFailures.incrementAndGet() >= 5) {
            cronetFailures.set(0)
            cronetOffUntil = System.currentTimeMillis() + 10 * 60_000L
            log("net: Chrome network stack failed 5 times in a row — using OkHttp for 10 min")
        }
    }

    // Chrome's header order on the wire: one for navigations, one for fetch/XHR.
    internal val chromeNavOrder = listOf("sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "upgrade-insecure-requests", "user-agent",
        "accept", "sec-fetch-site", "sec-fetch-mode", "sec-fetch-user", "sec-fetch-dest", "referer", "accept-language", "cookie", "range")
    internal val chromeXhrOrder = listOf("sec-ch-ua-platform", "x-requested-with", "user-agent", "accept", "sec-ch-ua", "content-type",
        "sec-ch-ua-mobile", "origin", "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest", "referer", "accept-language", "cookie", "range")
    internal fun chromeOrdered(h: Map<String, String>): List<Pair<String, String>> {
        val nav = h.entries.any { it.key.equals("Sec-Fetch-Mode", true) && it.value == "navigate" }
        val order = if (nav) chromeNavOrder else chromeXhrOrder
        return h.entries.map { it.key to it.value }.sortedBy { (k, _) -> order.indexOf(k.lowercase()).let { i -> if (i < 0) order.size else i } }
    }

    internal fun readUpTo(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16_384)
        while (out.size() < max) { val n = input.read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) }
        return out.toByteArray()
    }
    internal fun withQuery(url: String, params: Map<String, String>): String = if (params.isEmpty()) url else
        url + (if ('?' in url) "&" else "?") + params.entries.joinToString("&") { "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }

    // v41: one browser session. anizm requests carry the cookies the site set (and those the
    // in-app WebView picked up, Cloudflare's included) and keep the ones it sets, in the WebView's
    // own cookie store. Before, every request arrived with no cookies, so each one started a
    // fresh server session, which is not what a browser does.
    internal fun withSiteCookies(url: String, headers: Map<String, String>): Map<String, String> {
        if (headers.keys.any { it.equals("Cookie", true) }) return headers
        val c = try { CookieManager.getInstance().getCookie(url) } catch (_: Throwable) { null }
        return if (c.isNullOrBlank()) headers else headers + ("Cookie" to c)
    }
    internal fun keepSiteCookies(r: NiceResponse) {
        val set = try { r.headers.values("Set-Cookie") } catch (_: Exception) { emptyList() }
        if (set.isEmpty()) return
        try { val cm = CookieManager.getInstance(); val u = r.url; for (c in set) cm.setCookie(u, c) } catch (_: Throwable) {}
    }

    // v12: Cloudflare's challenge on /player/ was about MISSING CLIENT HINTS, not the
    // Referer and not cookies. Device self-test (2026-09-18), same URL, same second:
    //   minimal / episode-referer / no-UA / webview-cookies → 403, cf-mitigated=challenge
    //   chrome hints / cookies+hints                        → 302 (the embed redirect)
    //   episode page, home page (no hints)                  → 200
    // A UA that says "Chrome 152" with no sec-ch-ua headers is a contradiction, and that
    // path is where Cloudflare enforces it. v5 removed these headers on the theory that they
    // looked inconsistent — exactly backwards. They're on every anizm request now.
    // Mobile hints, matching the Android UA above — and the exact shape the device self-test
    // got its 302 with. Version comes from the real Chrome version in the UA.
    // v36: the brand list is built the way Chrome builds it (GREASE brand, version and order all
    // depend on the major version). v12–v35 sent "Chromium", "Google Chrome", "Not?A_Brand";v="24"
    // for every version — for Chrome 153 real Chrome sends "Google Chrome", "Not_A Brand";v="8",
    // "Chromium". The algorithm reproduces this device's own WebView header exactly
    // ("Android WebView";v="153", "Not_A Brand";v="8", "Chromium";v="153", log 2026-09-26), so
    // the app's requests and the WebView's no longer disagree about what browser this is.
    internal val chromeBrandList: String by lazy {
        val major = chromeMajor.toIntOrNull() ?: 152
        val chars = listOf(" ", "(", ":", "-", ".", "/", ")", ";", "=", "?", "_")
        val versions = listOf("8", "99", "24")
        val grease = "Not" + chars[major % chars.size] + "A" + chars[(major + 1) % chars.size] + "Brand"
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        val order = orders[major % orders.size]
        val slots = arrayOfNulls<String>(3)
        slots[order[0]] = "\"$grease\";v=\"${versions[major % versions.size]}\""
        slots[order[1]] = "\"Chromium\";v=\"$major\""
        slots[order[2]] = "\"Google Chrome\";v=\"$major\""
        slots.joinToString(", ")
    }
    // v42: Chrome sends these from version 90 on. Android boxes often keep an old system WebView,
    // whose version is what the UA carries; below 90 they would contradict it, so none are sent.
    internal val clientHints get() = if ((chromeMajor.toIntOrNull() ?: 152) < 90) emptyMap() else mapOf(
        "sec-ch-ua" to chromeBrandList,
        // v42: from the UA. TV boxes and tablets have no "Mobile" in it and Chrome sends ?0 there.
        "sec-ch-ua-mobile" to (if (ua.contains(" Mobile")) "?1" else "?0"),
        "sec-ch-ua-platform" to "\"Android\"")
    // v41: Chrome's own Accept for a document. The old value (…*/*;q=0.8 with nothing else) is
    // Firefox's, which contradicts a Chrome UA.
    internal val chromeDocAccept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
    internal val navHints get() = clientHints + mapOf(
        "Sec-Fetch-Dest" to "iframe", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Site" to "same-origin",
        "Upgrade-Insecure-Requests" to "1",
        "Accept" to chromeDocAccept)

    internal val baseHeaders get() = clientHints + mapOf(
        "User-Agent" to ua,
        "Accept-Language" to acceptLang, "Referer" to "$mainUrl/")
    // v41: page loads (home, anime and episode pages) as Chrome sends them after a click. They
    // used to go out with no Accept and no Sec-Fetch-* at all, beside a Chrome UA and client hints.
    internal val pageHeaders get() = baseHeaders + mapOf(
        "Upgrade-Insecure-Requests" to "1", "Accept" to chromeDocAccept,
        "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-User" to "?1", "Sec-Fetch-Dest" to "document")
    // v46: the episode page's own JSON lookups (/bolum/<ep>/cevirmen/<tr>, /bolum/<ep>/video/<id>), as its
    // script sends them (browser capture, 2026-11-03): Accept application/json, X-CSRF-TOKEN from the page's
    // <meta name="csrf-token">, the episode URL as Referer, and no Origin (same-origin GET). anizm does not
    // check the token today; sending it means the day it does, translator lists keep loading.
    @Volatile internal var csrfToken: String? = null
    internal val csrfMetaRe = Regex("""<meta\b[^>]*\bname\s*=\s*["']csrf-token["'][^>]*>""", RegexOption.IGNORE_CASE)
    internal val contentAttrRe = Regex("""\bcontent\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    internal fun noteCsrfToken(html: String) {
        val tag = csrfMetaRe.find(html)?.value ?: return
        contentAttrRe.find(tag)?.groupValues?.get(1)?.takeIf { it.length in 16..200 }?.let { csrfToken = it }
    }
    internal fun episodeXhrHeaders(episodeUrl: String): Map<String, String> =
        xhrHeaders + mapOf("Accept" to "application/json", "Referer" to onCurrentHost(episodeUrl)) +
            (csrfToken?.let { mapOf("X-CSRF-TOKEN" to it) } ?: emptyMap())
    // v48: no Origin. These are same-origin GETs, and Chrome sends Origin on none of those.
    internal val xhrHeaders get() = clientHints + mapOf(
        "Sec-Fetch-Dest" to "empty", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Site" to "same-origin",
        "User-Agent" to ua,
        "Accept-Language" to acceptLang, "Referer" to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json, text/javascript, */*; q=0.01")
    // v48: the home page as a first visit (typed address or bookmark): no Referer, Sec-Fetch-Site none.
    internal val homeHeaders get() = (pageHeaders - "Referer") + mapOf("Sec-Fetch-Site" to "none")
    /** A page opened from [from] by a click on the site. */
    internal fun pageHeadersFrom(from: String) = pageHeaders + mapOf("Referer" to onCurrentHost(from))

    // ── Embed resolver (primary: HTTP redirect) ─────────────────────────────
    // 4.0: verified on the live site (2026-09-17) that /player/{numId} is NOT a page that
    // assembles the embed in JS — it's a plain server-side 302 straight to the host
    // (anizmplayer.com/video/{hash}, pl.puffytr.tr/watch/{hash},
    // drive.google.com/file/d/{id}/preview, voe.sx/e/…, vids.st/e/…). The only gate is a
    // same-site Referer: no Referer → 404; Referer https://anizm.net/ → 302, with or
    // without cookies. The older note that "plain fetch fails regardless of cookies" was a
    // CORS artifact — a browser fetch() that follows a 302 to another origin throws, which
    // looks like a failure but isn't one.
    //
    // So: one small anizm.net request per source, redirects NOT followed, Location header
    // read and classified. No WebView, no iframe injection, no loading the host's embed
    // page just to learn its URL, and no per-id 1.5s timeouts. One lookup at a time (see resolveGate).
    internal val playerBaseLock = Any()
    // v31: the domain each "ap:" hash was found on. apRe accepts any *player* host with a
    // /video/<hex> path (FirePlayer clones included), but every ap: source used to be sent to
    // the one global playerBase — whichever such host was seen last. An episode with Aincrad
    // plus another FirePlayer host then asked the wrong server for half its getVideo calls.
    internal val apDomains = TtlCache<String, String>(600, 24 * 60 * 60 * 1000L)
    // v39: the Beta Player host each "bp:" hash was found on (processBeta used to assume pl.puffytr.tr).
    internal val betaOrigins = TtlCache<String, String>(600, 24 * 60 * 60 * 1000L)
    // v48: Beta Player by the shape of its address (/watch/<32 hex>) on any host, so a move off
    // puffytr.tr is followed. If such a host turns out not to be Beta, processSource hands it on.
    internal val betaShapeRe = Regex("""^(https?://[a-z0-9.-]+)/watch/([a-f0-9]{32})/?(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    // v39: what both resolvers note about an ap:/bp: match, in one place (the WebView one had its own copy).
    internal fun noteApMatch(m: MatchResult): String {
        val domain = m.groupValues[1]
        synchronized(playerBaseLock) {
            if (!playerBase.contains(domain.substringAfter("://"))) { playerBase = domain; log("resolve: player domain updated to $domain") }
        }
        noteApDomain(m.groupValues[2], domain)
        return m.groupValues[2]
    }
    internal fun noteBetaMatch(m: MatchResult): String {
        betaOrigins[m.groupValues[2]] = m.groupValues[1].lowercase()
        return m.groupValues[2]
    }
    internal fun noteApDomain(hash: String, domain: String) { apDomains[hash] = domain }

    internal fun classifyEmbedUrl(url: String): String? {
        val host = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
        if (host.isBlank() || host.endsWith(mainHost)) return null // self-redirect = not an embed
        puffytrRe.find(url)?.let { return "bp:${noteBetaMatch(it)}" }
        apRe.find(url)?.let { return "ap:${noteApMatch(it)}" }
        gdRe.find(url)?.let { return "gd:${it.groupValues[1]}" }
        betaShapeRe.find(url)?.let {
            logW("site-change warning: a Beta-shaped player address on a new host ($host) — trying it as Beta Player")
            return "bp:${noteBetaMatch(it)}"
        }
        // Anything else goes to loadExtractor(). No keyword whitelist on purpose: if
        // CloudStream has no extractor for the host, loadExtractor() returns false without
        // touching the network, so an unknown host costs nothing — and a host CloudStream
        // gains an extractor for later starts working with no change here.
        return if (url.startsWith("http")) "ex:$url" else null
    }

    // v10: cookies the WebView picked up for anizm (Cloudflare's __cf_bm / cf_clearance
    // among them) are in the app-wide CookieManager. OkHttp doesn't use that store, so until
    // now the direct lookups arrived cookieless while the WebView path was fully "logged in"
    // to Cloudflare's eyes. Sharing them costs nothing and may be the whole difference.
    internal fun webViewCookies(): String? = try {
        CookieManager.getInstance().getCookie(mainUrl)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) { null }

    /**
     * v19: cookies the hidden WebView picked up for one specific host. A sniffed stream often
     * lives behind the same session the page set up; ExoPlayer gets its own cookie jar, so
     * anything the player needs has to be copied onto the link's headers by hand.
     */
    internal fun cookiesFor(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) { null }

    // v39: where the site's player frame for an id lives. The site's own /bolum/<ep>/video/<id>
    // answer names it ("url": "https://anizm.net/player/<id>" in every capture, 2026-10-13); it is
    // only asked when /player/<id> stops answering, and a new path is then used for every id.
    @Volatile internal var playerUrlTemplate: String? = null // "{id}" marks the id
    internal fun playerUrlFor(nid: String): String =
        playerUrlTemplate?.let { onCurrentHost(it.replace("{id}", nid)) } ?: "$mainUrl/player/$nid"

    // Shared across loadLinks calls on purpose: prefetch + a manual click overlapping
    // shouldn't double the request rate to /player/.
    internal val resolvePacer = Mutex()
    @Volatile internal var lastResolveStart = 0L
    internal suspend fun paceResolve() {
        resolvePacer.withLock {
            val span = (resolveGapMaxMs - resolveGapMinMs).coerceAtLeast(0L)
            val gap = resolveGapMinMs + (Math.random() * (span + 1)).toLong()
            val wait = lastResolveStart + gap - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastResolveStart = System.currentTimeMillis()
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    // 4.0: no session fetch, no _token (verified unnecessary). Also handles a real miss:
    // the endpoint is a plain substring match, so "re zero" returns nothing for
    // "Re:Zero kara Hajimeru…" while "Re:Zero" or "zero kara" work. On an empty result we
    // retry once with the longest word and filter locally on all words, punctuation-blind.
    // v37: all HTML/JSON reading lives in AnizmParser, which tools/check-parser.sh checks
    // against saved copies of the site. This file only fetches and maps to CloudStream types.
    internal suspend fun searchApi(q: String): List<AnizmParser.SearchHit> {
        val params = mapOf("query" to q, "type" to "detailed", "limit" to "20",
            "priorityField" to "info_title", "orderBy" to "info_year", "orderDirection" to "ASC")
        val text = try { siteText("$mainUrl/searchAnime", xhrHeaders, params = params, timeout = 10L) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null } ?: return emptyList()
        return AnizmParser.searchApi(text, mainUrl)
    }

    // v36: the catalog page's own search (/arama?q=) — plain HTML, with posters.
    internal suspend fun searchHtml(q: String): List<AnizmParser.Card> {
        val url = "$mainUrl/arama"
        val text = try { siteText(url, pageHeaders, params = mapOf("q" to q), timeout = 10L) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null } ?: return emptyList()
        return AnizmParser.listing(org.jsoup.Jsoup.parse(text, url), 1).cards
    }

    // v37: the header box's suggestions — 3 results, no posters, so only the last resort.
    internal suspend fun searchSuggest(q: String): List<AnizmParser.Card> {
        val text = try { siteText("$mainUrl/arama-onerileri", xhrHeaders, params = mapOf("q" to q), timeout = 8L) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null } ?: return emptyList()
        return AnizmParser.suggestions(text)
    }

    internal fun cardResponse(c: AnizmParser.Card) =
        newAnimeSearchResponse(c.title, c.url, TvType.Anime) { posterUrl = c.poster; year = c.year }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().ifBlank { return emptyList() }
        var hits = searchApi(q)
        if (hits.isEmpty()) {
            // v36: /searchAnime answers some queries (e.g. "frieren") with a 500 every time.
            val html = searchHtml(q)
            if (html.isNotEmpty()) { log("search: '$q' api empty, /arama gave ${html.size}"); return html.map { cardResponse(it) } }
            val words = AnizmParser.words(q)
            val longest = words.maxByOrNull { it.length }
            if (words.size > 1 && longest != null && longest.length >= 3) {
                val squashedWords = words.map { AnizmParser.squash(it) }
                val broad = searchApi(longest)
                hits = broad.filter { h -> squashedWords.all { h.haystack.contains(it) } }.ifEmpty { broad }
                log("search: '$q' empty, retried with '$longest' → ${hits.size}")
            }
        }
        if (hits.isEmpty()) {
            val sug = searchSuggest(q)
            log("search: '$q' still empty, /arama-onerileri gave ${sug.size}")
            return sug.map { cardResponse(it) }
        }
        return hits.map { h ->
            newAnimeSearchResponse(h.title, "$mainUrl/${h.slug}", TvType.Anime) { posterUrl = h.poster; year = h.year }
        }
    }

    // ── Home page ─────────────────────────────────────────────────────────────
    // v36 (site revamp 2026-10): /anime-izle is gone (redirects home). Latest episodes are
    // /yeniler?sayfa=N; the home page has the "Popüler" and "Yeni Seriler" rows, unpaged.
    // v40: the home page's banner slider ("Öne çıkan animeler") first, as wide cards with the slider's
    // own full-size images (1672x941 up to 3840x2160).
    override val mainPage = listOf(MainPageData(name = "Öne Çıkanlar", data = "one-cikanlar", horizontalImages = true)) + mainPageOf(
        "yeniler" to "Son Eklenen Bölümler",
        "yeni-seriler" to "Yeni Seriler",
        "populer" to "Popüler")

    // v39: "yeni-seriler" and "populer" both come from the home page, and CloudStream asks for the
    // rows at the same time; each used to download and parse it. Now one download serves both
    // (the second row waits for the first) and is kept as long as the row cache.
    internal val homeRowsLock = Mutex()
    internal val heroBanners = java.util.concurrent.ConcurrentHashMap<String, String>() // anime url -> banner
    @Volatile internal var homeRows: Pair<Map<String, List<AnizmParser.Card>>, Long>? = null
    internal suspend fun homeRowCards(rowId: String): List<AnizmParser.Card>? = homeRowsLock.withLock {
        homeRows?.takeIf { System.currentTimeMillis() - it.second < mainPageCacheTtlMs }?.first
            ?: siteDocument("$mainUrl/", homeHeaders, timeout = 12L)?.let { doc ->
                setOf("one-cikanlar", "yeni-seriler", "populer", rowId)
                    .associateWith { if (it == "one-cikanlar") AnizmParser.heroSlides(doc) else AnizmParser.homeRow(doc, it) }
                    .also { rows ->
                        if (rows.values.any { it.isNotEmpty() }) homeRows = rows to System.currentTimeMillis()
                        // v40: the anime pages have no wide image; a featured anime's banner becomes its background there.
                        rows["one-cikanlar"]?.forEach { c -> c.poster?.let { heroBanners[c.url] = it } }
                        if (heroBanners.size > 100) heroBanners.clear()
                    }
            }
    }?.get(rowId)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val paged = request.data == "yeniler"
        if (!paged && page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val cacheKey = "$page:${request.data}"
        mainPageCache[cacheKey]?.let { (cached, time) ->
            if (System.currentTimeMillis() - time < mainPageCacheTtlMs) return cached
        }
        // v37: home images keep their URL in data-v3-deferred-src (v36 read only src, so the
        // home rows had no posters); /yeniler pages end at the last rel=next link.
        val listing = if (paged) {
            val doc = siteDocument("$mainUrl/yeniler?sayfa=$page", pageHeaders, timeout = 12L)
                ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
            AnizmParser.listing(doc, page)
        } else AnizmParser.Listing(homeRowCards(request.data)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false), hasNext = false)
        val items = listing.cards.map { cardResponse(it) }
        if (page == 1 && items.isEmpty()) logW("site-change warning: main page selectors empty")
        // v40: the request carries the row's card layout (wide for the banner row).
        val result = newHomePageResponse(request, items, hasNext = listing.hasNext)
        if (items.isNotEmpty()) {
            mainPageCache[cacheKey] = result to System.currentTimeMillis()
            synchronized(mainPageCacheLock) {
                if (mainPageCache.size > 20) {
                    mainPageCache.entries.minByOrNull { it.value.second }?.let {
                        mainPageCache.remove(it.key)
                    }
                }
            }
        }
        return result
    }

    // ── Load ──────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val doc = siteDocument(url, pageHeaders, timeout = 12L)
            ?: return newAnimeLoadResponse(name = url.substringAfterLast('/'), url = url, type = TvType.Anime) { addEpisodes(DubStatus.Subbed, emptyList()) }
        val anime = AnizmParser.anime(doc, url)
        if (anime.episodes.isEmpty()) logW("site-change warning: no episodes found on ${url.substringAfterLast('/')}")
        // number 0 = preview or joined episode ("215-216. Bölüm"): watchable, never shifts the real numbers
        val episodes = anime.episodes.map { e -> newEpisode(e.url) { name = e.name }.apply { episode = e.number } }
        // Split aliases into eng/jap + synonyms for AniList/MAL sync matching
        val aliases = anime.aliases.ifEmpty { null }
        val eng = aliases?.firstOrNull { asciiOnlyRe.matches(it) }
        val jap = aliases?.firstOrNull { !asciiOnlyRe.matches(it) }
        val syns = aliases?.filter { it != eng && it != jap }?.ifEmpty { null }
        log("load: '${anime.title}' poster=${anime.poster != null} plot=${(anime.plot?.length ?: 0) > 0} eps=${episodes.size} eng=$eng jap=$jap syns=${syns?.size ?: 0}")
        return newAnimeLoadResponse(anime.title, url, TvType.Anime) {
            posterUrl = anime.poster; this.plot = anime.plot; this.year = anime.year; this.tags = anime.tags.ifEmpty { null }
            // v42: only the site's own banner (featured anime). Left empty otherwise on purpose: CloudStream
            // then fills the details screen with AniList's banner itself, and its home header (tall on phones)
            // shows the poster instead of a stretched wide strip. v41's Kitsu/AniList lookups did the opposite.
            this.backgroundPosterUrl = heroBanners[url] ?: heroBanners[onCurrentHost(url)]
            this.engName = eng
            this.japName = jap
            this.synonyms = syns
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ── Load links ────────────────────────────────────────────────────────────
    // 4.0: split in two — fetchSourceList() (episode page + translator XHRs, now cached
    // briefly) and the resolve/dispatch part below.
    // v15: CloudStream can have two loadLinks calls for the same episode in flight at once
    // (seen in a real log: a reload and the app's own call, ~1s apart). Without this both
    // downloaded the ~300KB episode page and every translator list separately. The second
    // one now waits for the first and reads its result from the cache.
    internal val sourceListLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    internal suspend fun fetchSourceList(data: String): List<VidInfo>? {
        // v48: one lock per episode was kept for ever; idle ones are dropped once there are many.
        if (sourceListLocks.size > 40) sourceListLocks.entries.removeAll { !it.value.isLocked }
        return sourceListLocks.getOrPut(data) { Mutex() }.withLock { fetchSourceListLocked(data) }
    }

    internal suspend fun fetchSourceListLocked(data: String): List<VidInfo>? {
        sourceListCache[data]?.let { (list, t) ->
            if (System.currentTimeMillis() - t < sourceListTtlMs) { log("loadLinks: source list cache hit (${list.size})"); return list }
        }
        val epHtml = try {
            // v48: opened from the anime's page, as a click on an episode there is.
            siteText(data, pageHeadersFrom(AnizmParser.animeUrlFromEpisodeUrl(data))) ?: run { log("loadLinks: page error"); return null }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { log("loadLinks: page error: ${e.message}"); return null }
        log("loadLinks: page len=${epHtml.length}")
        noteCsrfToken(epHtml)

        // v38: AnizmParser.translators reads the fansub buttons straight from the HTML text, then
        // through jsoup (data-sources, then data-translator-id + the episode id), then any
        // /bolum/<ep>/cevirmen/<tr> URL in the HTML.
        // LinkedHashMap: O(1) dedup by URL while preserving on-page order.
        val translators = LinkedHashMap<String, String>()
        AnizmParser.translators(epHtml, onCurrentHost(data)).forEach { translators[it.url] = it.name }
        log("loadLinks: ${translators.size} translators: ${translators.values}")
        if (translators.isEmpty()) {
            if (epHtml.length > 5000) logW("site-change warning: no translators found on a normal-sized episode page")
            return emptyList()
        }

        // Indexed so the final list keeps page order (translator order, then button order)
        // even though translators are fetched concurrently.
        val perTranslator = arrayOfNulls<List<VidInfo>>(translators.size)
        val anyTranslatorHadData = java.util.concurrent.atomic.AtomicBoolean(false)
        val trGate = Semaphore(3)
        coroutineScope {
            translators.entries.forEachIndexed { idx, (trUrl, fansubName) ->
                launch {
                    trGate.withPermit {
                        val trText = try {
                            siteText(trUrl, episodeXhrHeaders(data))
                                ?: run { log("loadLinks: tr error ($fansubName)"); return@withPermit }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { log("loadLinks: tr error ($fansubName): ${e.message}"); return@withPermit }
                        // {"success":true,"videos":[{"id","name"}]}; each id is the /player/<id> number
                        val epId = bolumIdRe.find(trUrl)?.groupValues?.get(1)
                        val list = AnizmParser.videos(trText).map { VidInfo(it.id, it.name, fansubName, epId) }
                        if (list.isEmpty()) return@withPermit
                        anyTranslatorHadData.set(true)
                        // 4.0: no name whitelist here. It silently dropped every player it didn't
                        // recognise by label ("LuluStream", "Sistenn", "BYSE", "FlyF", …). What the
                        // host actually is gets decided from the URL /player/<id> redirects to.
                        log("loadLinks: $fansubName: ${list.map { it.name }}")
                        perTranslator[idx] = list
                    }
                }
            }
        }
        if (!anyTranslatorHadData.get()) {
            logW("site-change warning: translator data empty for all ${translators.size} translators")
            return null // don't cache a failure
        }
        val list = perTranslator.filterNotNull().flatten()
        if (list.isNotEmpty()) {
            sourceListCache[data] = list to System.currentTimeMillis()
            if (sourceListCache.size > 30) {
                val now = System.currentTimeMillis()
                sourceListCache.entries.removeAll { now - it.value.second > sourceListTtlMs }
                while (sourceListCache.size > 30) {
                    sourceListCache.entries.minByOrNull { it.value.second }?.let { sourceListCache.remove(it.key) } ?: break
                }
            }
        }
        return list
    }

    internal val linkRuns = java.util.concurrent.ConcurrentHashMap<String, LinkRun>()

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val mine = LinkRun()
        val running = linkRuns.putIfAbsent(data, mine)
        if (running != null) {
            val replay = synchronized(running) { running.subscribers.add(callback); running.links.toList() }
            log("loadLinks: $data is already loading — joining that run (${replay.size} link(s) so far)")
            replay.forEach(callback)
            val result = try { running.done.await() } finally { synchronized(running) { running.subscribers.remove(callback) } }
            if (result != null) return result
            log("loadLinks: the run it joined was cancelled — loading again")
            return loadLinks(data, isCasting, subtitleCallback, callback)
        }
        synchronized(mine) { mine.subscribers.add(callback) }
        val fanOut: (ExtractorLink) -> Unit = { link ->
            val subs = synchronized(mine) { mine.links.add(link); mine.subscribers.toList() }
            subs.forEach { it(link) }
        }
        return try {
            loadLinksWork(data, isCasting, subtitleCallback, fanOut).also { mine.done.complete(it) }
        } finally {
            linkRuns.remove(data, mine)
            mine.done.complete(null) // no-op if already completed with a result
        }
    }

    internal suspend fun loadLinksWork(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        log("loadLinks: $data")
        trimSniffMaps()
        // v9: tell a real "reload links" apart from the app's own double call.
        // CloudStream calls loadLinks again a second or two after the first (preload, then
        // the actual open) — those should use the caches. A call that arrives much later for
        // the same episode is the user asking again, and answering that from cache is what
        // made "reload links" look like it did nothing. That one re-scrapes everything:
        // episode page, translator lists, every player lookup, and Aincrad's signed URL.
        val callNow = System.currentTimeMillis()
        val sniffSpent = java.util.concurrent.atomic.AtomicLong(0L)
        // v48: for the one-line summary at the end (where the time went).
        val firstLinkAt = java.util.concurrent.atomic.AtomicLong(0L)
        val linkCount = java.util.concurrent.atomic.AtomicInteger(0)
        // Sources handed to step 4 and not finished yet (see the hold in runWave).
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val sinceLast = lastLoadAt[data]?.let { callNow - it } ?: Long.MAX_VALUE
        lastLoadAt[data] = callNow
        if (lastLoadAt.size > 50) lastLoadAt.entries.removeAll { callNow - it.value > reloadWindowMs }
        val forceRefresh = sinceLast in reloadMinGapMs..reloadWindowMs
        if (forceRefresh) {
            log("loadLinks: reload requested (${sinceLast / 1000}s since last) — clearing caches for this episode")
            sourceListCache.remove(data)?.first?.forEach { vi -> dropCached(vi.numId) }
        }
        val listed = fetchSourceList(data) ?: return false
        if (listed.isEmpty()) return false
        val listedAt = System.currentTimeMillis()

        // Snapshot settings once per call so a change mid-load can't half-apply.
        // A reload also ignores the lazy stop: if you asked again, you want the whole list.
        val lazy = settings.lazyResolve && !forceRefresh
        val target = settings.lazyTargetSources
        val lastResort = settings.tryDisabledAsLastResort
        val minQuality = settings.lazyMinQuality
        // v23: deferring browser-only sources is optional. Off = the settings order is
        // followed exactly, so a Sistenn placed high in the list is sniffed in its turn.
        val deferBrowser = lazy && settings.deferBrowserSources
        // A source only counts toward the lazy target if its best link reaches minQuality.
        // Unknown quality never counts (unless minQuality is "Any"): we can't tell a 480p
        // mystery link from a 1080p one, and stopping on it is exactly the failure this avoids.
        fun meetsMin(q: Int) = minQuality <= 0 || (q != Qualities.Unknown.value && q >= minQuality)

        val byNumId = listed.groupBy { it.numId } // never mutated after this — safe to read from any thread
        // Priority = settings list order (Aincrad → Beta → GDrive → known hosts → other);
        // stable sort, so page order (translator, then button) is kept within a group.
        fun ordered(ids: Collection<String>) = ids.distinct()
            .sortedBy { id -> byNumId[id]!!.minOf { settings.priorityOf(it.name) } }
        // A numId counts as enabled if any label pointing at it is enabled.
        val enabledIds = ordered(listed.filter { settings.isEnabled(it.name) }.map { it.numId })
        // Remember a target for the settings screen's connection test, whether or not the
        // direct path ran this time.
        enabledIds.firstOrNull()?.let { lastProbeTarget = it to data }
        val enabledSet = enabledIds.toHashSet()
        val disabledIds = ordered(listed.map { it.numId }.filter { it !in enabledSet })
        log("loadLinks: ${enabledIds.size} enabled, ${disabledIds.size} disabled by settings; lazy=$lazy target=$target minQ=$minQuality lastResort=$lastResort deferBrowser=$deferBrowser")
        // v22: the v21 log had wave 1 = Sistenn, Sistenn1, Aincrad, which is not what the
        // settings order says it should be. Print the order actually used so that's visible.
        log("loadLinks: try order: " + enabledIds.joinToString(", ") { id ->
            val vi = byNumId[id]!!.minByOrNull { settings.priorityOf(it.name) }!!
            "${vi.name}#${settings.priorityOf(vi.name)}"
        })

        val embedMap = java.util.concurrent.ConcurrentHashMap<String, String>()
        val found = java.util.concurrent.atomic.AtomicBoolean(false)
        val okSources = java.util.concurrent.atomic.AtomicInteger(0)       // working AND >= minQuality
        val belowMinSources = java.util.concurrent.atomic.AtomicInteger(0) // working, but too low / unknown
        // Guards the UI-visible symptom of two sources resolving to the same stream.
        val seenLinks = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        val safeCallback: (ExtractorLink) -> Unit = { link ->
            val normalizedUrl = link.url.trim().substringBefore('#')
            val key = "$normalizedUrl|${link.quality}|${link.type}"
            if (seenLinks.add(key)) { firstLinkAt.compareAndSet(0L, System.currentTimeMillis()); linkCount.incrementAndGet(); callback(link) }
        }
        // Cap concurrent step4 work so a 50-source episode doesn't fire 50 simultaneous
        // requests at the same handful of hosts.
        val stepGate = Semaphore(5)
        val tried: MutableSet<String> = java.util.Collections.synchronizedSet(java.util.LinkedHashSet<String>()) // v44: waves overlap
        // v22: sources that can only be read with the in-app browser (Sistenn & co.) are put
        // off while lazy loading. In the v21 log, two of them sat in wave 1 and cost 48s of
        // sniff timeouts, while Aincrad, GDrive and Beta — enough for the target on their
        // own — waited behind them. Now the cheap sources go first, and the sniffs only run
        // if the target still isn't met afterwards.
        val deferredSniffs = java.util.concurrent.ConcurrentLinkedQueue<Pair<VidInfo, String>>()

        suspend fun runOne(vi: VidInfo, embed: String, allowDefer: Boolean) {
            stepGate.withPermit {
                try {
                    // Track this source's best quality on the way through.
                    // CAS loop, not accumulateAndGet (API 24; minSdk is 21).
                    val best = java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)
                    val trackingCallback: (ExtractorLink) -> Unit = { l ->
                        val q = if (l.quality == Qualities.Unknown.value) Int.MIN_VALUE + 1 else l.quality
                        while (true) { val cur = best.get(); if (q <= cur || best.compareAndSet(cur, q)) break }
                        safeCallback(l)
                    }
                    val defer: ((VidInfo, String) -> Unit)? =
                        if (allowDefer) { v, e -> deferredSniffs.add(v to e) } else null
                    if (processSource(vi, embed, data, trackingCallback, subtitleCallback, defer, sniffSpent)) {
                        found.set(true)
                        val b = best.get().let { if (it <= Int.MIN_VALUE + 1) Qualities.Unknown.value else it }
                        if (meetsMin(b)) okSources.incrementAndGet()
                        else { belowMinSources.incrementAndGet(); log("step4: ${vi.fansub}/${vi.name} works but best=$b < min $minQuality, not counted") }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("step4: ${vi.fansub}/${vi.name} error: ${e.message}")
                }
            }
        }

        // Growing waves: 3, 6, 12, … When the first waves come up empty (typical for old
        // episodes where most hosts are dead), the remaining sources are tried in bigger
        // batches instead of crawling 3 at a time. Non-lazy = everything in one wave.
        // v19: the source list in settings is the only thing that decides the order now.
        // (A "pull Google Drive into the first batch" option used to override it.)
        fun waves(ids: List<String>): List<List<String>> {
            if (!lazy) return if (ids.isEmpty()) emptyList() else listOf(ids)
            val out = ArrayList<List<String>>()
            var i = 0; var size = lazyFirstWaveSize
            while (i < ids.size) { out += ids.subList(i, minOf(i + size, ids.size)); i += size; size *= 2 }
            return out
        }

        // onIdDone: an id is finished (its sources ran, or it could not be resolved).
        // v39: stop = the goal is met; lookups still queued behind the resolve gate are then dropped.
        suspend fun runWave(wave: List<String>, label: String, stop: () -> Boolean = { false }, onIdDone: (String) -> Unit = {}) {
            tried += wave
            val uncachedIds = mutableListOf<String>()
            val cachedNow = mutableListOf<Pair<String, String>>()
            for (id in wave) {
                val cached = getCached(id)
                if (cached != null) { embedMap[id] = cached; cachedNow += id to cached } else uncachedIds.add(id)
            }
            log("loadLinks: $label: ${wave.size} ids, ${cachedNow.size} cached, ${uncachedIds.size} to resolve")

            coroutineScope {
                fun dispatch(id: String, embed: String) {
                    val list = byNumId[id] ?: run { onIdDone(id); return }
                    inFlight.incrementAndGet()
                    launch {
                        try { coroutineScope { for (vi in list) launch { runOne(vi, embed, allowDefer = deferBrowser) } } }
                        finally { inFlight.decrementAndGet(); onIdDone(id) }
                    }
                }
                fun accept(id: String, embed: String) {
                    val existing = embedMap.putIfAbsent(id, embed)
                    if (existing == null) { putCache(id, embed); dispatch(id, embed) }
                    else if (existing != embed) log("loadLinks: duplicate resolution for $id, keeping first")
                }

                for ((id, embed) in cachedNow) dispatch(id, embed)

                if (uncachedIds.isNotEmpty()) {
                    // Primary: redirect read, each source dispatched the moment it resolves.
                    // Skipped entirely while direct lookups are being refused.
                    if (System.currentTimeMillis() >= playerHttpBlockedUntil) {
                        try {
                            // v48: while lazy loading, a lookup waits (up to 1.5 s) for the sources already
                            // running to report: the first wave used to look up all three ids before knowing
                            // whether the first two were enough, which they usually are.
                            val hold: suspend () -> Unit = {
                                if (lazy) withTimeoutOrNull(1_500L) { while (inFlight.get() > 0 && !stop()) delay(50) }
                            }
                            httpResolveEmbeds(uncachedIds, data, { id -> byNumId[id]?.firstNotNullOfOrNull { it.epId } }, stop, hold) { id, embed -> accept(id, embed) }
                        }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { log("loadLinks: http resolve error: ${e.message}") }
                    } else log("loadLinks: direct lookups blocked recently, using WebView")

                    // Fallback: WebView, only for recognised players the redirect path missed.
                    val missing = uncachedIds.filter { id ->
                        !embedMap.containsKey(id) && byNumId[id]?.any { settings.isRecognised(it.name) } == true
                    }
                    if (missing.isNotEmpty() && stop()) log("loadLinks: $label: goal met, ${missing.size} unresolved not tried")
                    else if (missing.isNotEmpty()) {
                        log("loadLinks: ${missing.size} unresolved, trying WebView fallback")
                        try { resolveEmbeds(missing, data) { id, embed -> accept(id, embed) } }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { log("loadLinks: webview resolve error: ${e.message}") }
                    }
                    for (id in uncachedIds) if (!embedMap.containsKey(id)) onIdDone(id)
                }
            } // suspends until every source in this wave has finished
        }

        // Phase 1: sources the user has enabled.
        // v44: the next wave starts as soon as this one can no longer reach the target by itself
        // (working + still pending < target), not when its slowest source is done: the next wave
        // would run anyway, so this adds no requests. Build 32 log: wave 2 waited 3 s for a
        // backup-source check and a capacity retry that could add at most one source.
        val w1 = waves(enabledIds)
        coroutineScope {
            for ((i, wave) in w1.withIndex()) {
                val pending = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>()).apply { addAll(wave) }
                val job = launch { runWave(wave, "wave ${i + 1}/${w1.size}", { lazy && okSources.get() >= target }) { id -> pending.remove(id) } }
                while (job.isActive) {
                    if (lazy && okSources.get() >= target) break
                    if (lazy && i < w1.lastIndex && okSources.get() + pending.size < target) {
                        if (pending.isNotEmpty()) log("loadLinks: wave ${i + 1} can't reach $target alone (${okSources.get()} working, ${pending.size} pending) — starting the next")
                        break
                    }
                    delay(100)
                }
                if (lazy && okSources.get() >= target) {
                    if (i < w1.lastIndex) log("loadLinks: lazy stop — ${okSources.get()} working at >= ${minQuality}p (+${belowMinSources.get()} lower), ${enabledIds.size - tried.size} enabled not tried")
                    break
                }
            }
        } // every started wave has finished

        // Phase 1b (v22): the browser-only sources that were put off above, in priority
        // order, only if the cheap ones didn't reach the goal. They run one at a time anyway
        // (sniffGate), so stop as soon as the goal is met.
        suspend fun flushDeferred(goalMet: () -> Boolean, what: String) {
            if (deferredSniffs.isEmpty()) return
            val queue = deferredSniffs.toList().sortedBy { settings.priorityOf(it.first.name) }
            deferredSniffs.clear()
            if (goalMet()) {
                log("loadLinks: $what met without the browser — skipped ${queue.size} sniff source(s): ${queue.joinToString { it.first.name }}")
                return
            }
            log("loadLinks: ${okSources.get()}/$target working, now trying ${queue.size} browser-only source(s)")
            for ((vi, embed) in queue) {
                runOne(vi, embed, allowDefer = false)
                if (goalMet()) break
            }
        }
        flushDeferred({ !lazy || okSources.get() >= target }, "target")

        // Phase 2: only if NOTHING worked. "Found some, just fewer than the target" doesn't
        // count — the user turned those hosts off, and one working source is a result.
        if (!found.get() && lastResort && disabledIds.isNotEmpty()) {
            log("loadLinks: no enabled source worked, trying ${disabledIds.size} disabled as last resort")
            val w2 = waves(disabledIds)
            for ((i, wave) in w2.withIndex()) {
                runWave(wave, "last-resort wave ${i + 1}/${w2.size}", { found.get() })
                if (found.get()) break // last resort: stop at the first thing that plays
            }
            flushDeferred({ found.get() }, "last resort")
        }

        for (vi in listed) if (vi.numId in tried && !embedMap.containsKey(vi.numId)) log("step4: ${vi.fansub}/${vi.name} MISSING")
        log("loadLinks: resolved ${embedMap.size}/${tried.size} tried (${byNumId.size} total), sources ok=${okSources.get()} (+${belowMinSources.get()} below min), found=${found.get()}")
        val endAt = System.currentTimeMillis()
        log("loadLinks: summary — source list ${listedAt - callNow} ms, first link " +
            (firstLinkAt.get().takeIf { it > 0 }?.let { "${it - callNow} ms" } ?: "never") +
            ", done ${endAt - callNow} ms (browser sniffs ${sniffSpent.get()} ms), ${linkCount.get()} link(s) from ${embedMap.size} source(s)")
        return found.get()
    }

    // ── Browser-assisted HLS sniffing (v16) ──────────────────────────────────
    // For players CloudStream has no extractor for. Investigated on the live site
    // (2026-09-20) with Sistenn as the example: sistenn.uns.bio / anizm.rpmvid.com are a
    // Vue app that asks /api/v1/video?id=…&w=…&h=…&r=anizm.net for an AES blob and decrypts
    // it with a key the page builds at runtime (window.__pk, rotating — not in the HTML, not
    // a cookie). Re-implementing that would break on their next deploy, so instead the page
    // is loaded in a hidden WebView, told to play, and the .m3u8 it then requests is taken.
    //
    // Worth the trouble: the stream is a MUXED master (720p ~1.34 Mbps, 1080p ~2.59 Mbps),
    // so unlike Aincrad it splits into per-quality entries and CloudStream can download it.
    // The playlist itself needs no cookies and no Referer (verified), so once sniffed the
    // link works on its own.
    // v17: only the popunder/redirect domains found in their own page script are blocked.
    // Analytics and the IMA ads SDK are deliberately let through: the bundle contains
    // "Please disable AdBlock to watch this video", so blocking the ad machinery is the one
    // thing likely to make the page refuse to produce a stream at all.
    internal val adHostKeywords = listOf(
        "aphacicfable", "prahmnatured", "brigadedelegatesandbox", "gigglemagnetismunaired",
        "cacklegrievingtank", "attirecideryeah", "popads", "propeller", "popcash")

    // v21: Google's ad stack (IMA SDK + its creatives). In the v20 log the Sistenn page loaded
    // /api/v1/info and s0.2mdn.net/instream/video/client.js and then never asked for
    // /api/v1/video — it sits waiting on the preroll. Whether it copes better with the ad
    // failing (script error -> content) or with the ad running is not knowable from here, so
    // the sniffer alternates: a failed attempt flips the mode for the next one, a successful
    // one keeps it. Blocked requests get a real 404 so the page's onerror fallback fires,
    // rather than an empty 200 script that leaves `google.ima` undefined and throws later.
    internal val imaHostKeywords = listOf(
        "imasdk.googleapis.com", "2mdn.net", "doubleclick.net", "googlesyndication.com",
        "googleadservices.com", "adservice.google", "googletagservices.com")
    // v27: headers the page's own player used for the stream, keyed by master URL.
    internal val sniffedHeaders = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    // v29: the exact variant/segment addresses the page's player requested, keyed by master URL.
    internal val sniffedVariantUrls = java.util.concurrent.ConcurrentHashMap<String, String>()
    internal val sniffedSegmentUrls = java.util.concurrent.ConcurrentHashMap<String, String>()
    // v35: every segment (path only) the page's player fetched, per master.
    internal val sniffedSegmentSets = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    internal val variantTagRe = Regex("""index-(f\d+[^/.?]*)""")
    // v35: the page's token refresh URL, per player page (exUrl).
    internal val sniffedRefreshUrls = java.util.concurrent.ConcurrentHashMap<String, String>()
    // v33: playlist texts fetched by the sniff page itself (the WebView's network stack), keyed
    // by URL without query. The device log (2026-09-26) had shky.stellarwebconcepts.store answer
    // the page's player in 0.2s but time out on every request from the app's HTTP client, so
    // the local playlist server could never fetch the playlists it was meant to serve.
    internal val sniffedPlaylistTexts = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Fetches the master and each variant (with the token) from inside the page and parks the
    // texts in window.__anzPl; __anzPlDone flips when all requests have settled.
    internal fun playlistCaptureJs(master: String, query: String) = """
        (function(m, q){
          window.__anzPl = window.__anzPl || {}; window.__anzPlDone = 0;
          function have(u){ return !!window.__anzPl[u.split('?')[0]]; }
          function tok(u){ return (q && u.indexOf('?') < 0) ? u + '?' + q : u; }
          // v34: 3s cap per request — the host stalls some variants (1080p) indefinitely.
          function tf(u){ var c = window.AbortController ? new AbortController() : null;
            if (c) setTimeout(function(){ c.abort(); }, 3000); return fetch(u, c ? { signal: c.signal } : {}); }
          (have(m) ? Promise.resolve(window.__anzPl[m.split('?')[0]]) : tf(m).then(function(r){ return r.text(); })).then(function(t){
            if (t.indexOf('#EXTM3U') === 0) window.__anzPl[m.split('?')[0]] = t;
            var urls = [];
            t.split('\n').forEach(function(l){ l = l.trim(); if (l && l.charAt(0) !== '#') { try { urls.push(tok(new URL(l, m).href)); } catch(e){} } });
            return Promise.all(urls.filter(function(u){ return !have(u); }).map(function(u){
              return tf(u).then(function(r){ return r.ok ? r.text() : ''; })
                .then(function(x){ if (x.indexOf('#EXTM3U') === 0) window.__anzPl[u.split('?')[0]] = x; })
                .catch(function(){});
            }));
          }).catch(function(e){ window.__anzPlErr = String(e); }).then(function(){ window.__anzPlDone = 1; });
          return 'started';
        })(${JSONObject.quote(master)}, ${JSONObject.quote(query)})
    """.trimIndent()
    internal val droppedCaptureHeaders = setOf("range", "accept-encoding", "host", "connection", "content-length", "if-none-match", "if-modified-since")
    internal fun cleanCapturedHeaders(h: Map<String, String>): Map<String, String> =
        h.filterKeys { it.lowercase() !in droppedCaptureHeaders }
    internal val muteJs = """
        (function(){
          function m(root, d){ if (!root || d > 6) return; var els; try { els = root.querySelectorAll('video,audio,media-player'); } catch(e){ return; }
            for (var i=0;i<els.length;i++){ try { els[i].muted = true; els[i].volume = 0; } catch(e){} }
            try { var all = root.querySelectorAll('*'); for (var j=0;j<all.length;j++) if (all[j].shadowRoot) m(all[j].shadowRoot, d+1); } catch(e){} }
          m(document, 0);
        })()
    """.trimIndent()
    internal val playlistFileRe = Regex("""(master|playlist|index)[^/]*\.(m3u8|txt)$""")
    internal fun notFoundResponse() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
        mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(emptyBytes))

    /** evaluateJavascript hands back a JSON value; unwrap a string result properly (escaped quotes, ampersands). */
    internal fun jsResult(r: String?): String {
        if (r.isNullOrEmpty() || r == "null") return ""
        return try { JSONArray("[$r]").optString(0, "") } catch (_: Exception) { r.trim('"') }
    }

    /** CloudStream's current Activity, if it exposes one. Reflection so a rename can't break the build. */
    internal fun currentActivity(): android.app.Activity? = try {
        val c = Class.forName("com.lagradost.cloudstream3.CommonActivity")
        val inst = try { c.getField("INSTANCE").get(null) } catch (_: Throwable) { null }
        (c.getMethod("getActivity").invoke(inst) as? android.app.Activity)
            ?.takeIf { !it.isFinishing && !it.isDestroyed }
    } catch (_: Throwable) { null }

    // Runs as the page starts. A WebView that is not on screen reports document.hidden = true,
    // and players (and the IMA SDK) hold off loading anything for a hidden tab.
    internal val docStartJs = """
        (function(){
          if (window.__anzHook) return; window.__anzHook = 1;
          try { Object.defineProperty(document, 'hidden', { get: function(){ return false; } }); } catch(e) {}
          try { Object.defineProperty(document, 'visibilityState', { get: function(){ return 'visible'; } }); } catch(e) {}
          try { document.hasFocus = function(){ return true; }; } catch(e) {}
          // v35: keep a copy of every playlist the page's own player downloads, so the app never
          // has to fetch it a second time (the stream hosts stall repeat requests; device logs
          // 2026-09-26). Read back through window.__anzPl; toString stays native-looking.
          try {
            window.__anzPl = window.__anzPl || {};
            var isPl = function(u){ return /\.(m3u8|txt)(\?|$)/i.test(String(u)); };
            var keep = function(u, t){ try { if (t && String(t).indexOf('#EXTM3U') === 0) window.__anzPl[String(u).split('?')[0]] = String(t); } catch(e){} };
            var XP = XMLHttpRequest.prototype, xo = XP.open, xs = XP.send;
            XP.open = function(m, u){ try { this.__anzU = u; } catch(e){} return xo.apply(this, arguments); };
            XP.send = function(){
              var x = this;
              try { x.addEventListener('load', function(){ try {
                var u = x.responseURL || x.__anzU;
                if (isPl(u) && (x.responseType === '' || x.responseType === 'text')) keep(u, x.responseText);
              } catch(e){} }); } catch(e){}
              return xs.apply(this, arguments);
            };
            XP.open.toString = function(){ return xo.toString(); };
            XP.send.toString = function(){ return xs.toString(); };
            var fo = window.fetch;
            if (fo) {
              window.fetch = function(i){
                var p = fo.apply(this, arguments);
                try { var u = (typeof i === 'string') ? i : (i && (i.url || i.href));
                  if (u && isPl(u)) p.then(function(r){ try { r.clone().text().then(function(t){ keep(r.url || u, t); }); } catch(e){} }, function(){});
                } catch(e){}
                return p;
              };
              window.fetch.toString = function(){ return fo.toString(); };
            }
          } catch(e) {}
        })()
    """.trimIndent()

    // What the page looked like when the sniff gave up — says whether it is stuck on an
    // AdBlock notice, has a player with an error, or never built a player at all.
    internal val stateJs = """
        (function(){
          var o = [];
          try { o.push('vis=' + document.visibilityState + ' ' + innerWidth + 'x' + innerHeight); } catch(e) {}
          try {
            var p = document.querySelector('media-player');
            if (!p) o.push('no media-player');
            else {
              var st = p.state || {};
              o.push('player src=' + String((st.source && st.source.src) || p.src || '').slice(0, 90));
              if (st.error) o.push('err=' + String(st.error.message || st.error).slice(0, 80));
              o.push('canLoad=' + st.canLoad + ' canPlay=' + st.canPlay + ' started=' + st.started);
            }
          } catch(e) { o.push('perr ' + e); }
          try { o.push('ifr=' + document.querySelectorAll('iframe').length); } catch(e) {}
          try { o.push('pk=' + (typeof window.__pk)); } catch(e) {}
          try { o.push('text="' + (document.body.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 160) + '"'); } catch(e) {}
          return o.join(' | ');
        })()
    """.trimIndent()

    /** Placeholder playlists a player attaches before it has the real source. */
    internal val decoyPlaylistNames = listOf("preload", "blank", "dummy", "placeholder", "empty", "init")

    // One sniff at a time. Each one is a full page load with JavaScript; five at once (the
    // step4 limit) would mean five live WebViews with ad scripts running — the kind of thing
    // that makes a TV box stutter or get killed for memory.
    internal val sniffGate = Semaphore(1)
    internal val localHls = LocalHlsServer({ log(it) }, { u, h -> try { runBlocking { browserGet(u, h, 10L) } } catch (_: Exception) { null } })
    // Sniffed playlists are reused for 20 min: the URL carries its own ?v= stamp and the page
    // load is by far the most expensive thing this extension does.
    internal val sniffCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    // v19: 20 min -> 5. These URLs carry a short-lived token (?v=…); a reused one still
    // resolves to a link in the list but can be dead by the time the user presses play.
    internal val sniffCacheTtlMs = 5 * 60 * 1000L

    // v18: the click script walks shadow roots. Vidstack (the player Sistenn uses) puts its
    // play button inside a shadow DOM, so the old plain querySelector never found it — and
    // players that use load="visible" only start hls.js once the element is on screen, which
    // in a zero-sized offscreen WebView never happens. Hence startLoading() below and the
    // explicit layout() in the sniffer.
    internal val playClickJs = """
        (function(){
          var did = [], clicks = 0, found = '';
          function looksLikePlay(el) {
            var lbl = '';
            try { lbl = (el.getAttribute('aria-label') || el.getAttribute('title') || '') + ' ' +
                        (typeof el.className === 'string' ? el.className : ''); } catch(e) {}
            return /(^|[^a-z])play|vds-play|play-button|btn-play|plyr__control/i.test(lbl);
          }
          function walk(root, depth) {
            if (!root || depth > 6) return;
            var els;
            try { els = root.querySelectorAll('*'); } catch(e) { return; }
            for (var i = 0; i < els.length; i++) {
              var el = els[i];
              var tag = el.tagName;
              if (tag === 'VIDEO') {
                try { el.muted = true; el.preload = 'auto'; el.autoplay = true; } catch(e) {}
                try { var r = el.play(); if (r && r.catch) r.catch(function(){}); did.push('video.play'); } catch(e) {}
              } else if (tag === 'MEDIA-PLAYER' || tag === 'MEDIA-PROVIDER') {
                // v21: if the player already holds the decrypted playlist, just take it.
                try {
                  var st = el.state || {};
                  var s = String((st.source && st.source.src) || el.src || '');
                  // v26: also "cf-master.<stamp>.txt" (rpmvid's real playlist, v25 log).
                  if (/\.m3u8|\/hlsmod\/|master[^\/?]*\.txt/i.test(s)) found = s;
                } catch(e) {}
                try { if (el.startLoading) { el.startLoading(); did.push('startLoading'); } } catch(e) {}
                try { if (el.startLoadingPoster) el.startLoadingPoster(); } catch(e) {}
                try { el.muted = true; } catch(e) {}
                try { if (el.play) { var q = el.play(); if (q && q.catch) q.catch(function(){}); did.push('player.play'); } } catch(e) {}
              } else if (clicks < 3 && looksLikePlay(el)) {
                try { el.click(); clicks++; did.push('click'); } catch(e) {}
              }
              if (el.shadowRoot) walk(el.shadowRoot, depth + 1);
            }
          }
          try { walk(document, 0); } catch(e) { return 'err:' + e; }
          if (found) return 'src:' + found;
          return did.length ? did.join(',') : 'nothing';
        })()
    """.trimIndent()

    // A host that times out twice in a row is parked: otherwise three Sistenn entries on one
    // episode cost three full budgets (~70s) before the episode finishes loading.
    internal val sniffHostFails = java.util.concurrent.ConcurrentHashMap<String, Int>()
    internal val sniffHostBlockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    internal val sniffHostCooldownMs = 15 * 60 * 1000L
    // Whole-episode cap. Three Sistenn entries on one episode must not turn into three full
    // budgets of waiting before the link list appears.
    // v39: counted per load (loadLinksWork's sniffSpent), not in one field every load reset and added to.
    internal val sniffBudgetPerLoadMs = 45_000L

    // v48: a host whose page never gave a stream in four tries in a row is left alone for a day,
    // across restarts. The 15-minute cooldown alone meant an unreadable player (one the app has
    // no extractor for and the browser can't play either) cost a full sniff again every evening.
    internal val sniffDeadHosts by lazy { PersistentCache(settings.prefs, "sniff_dead", 60, 24 * 60 * 60 * 1000L) }
    internal fun sniffHostOnCooldown(url: String): Boolean {
        val host = try { java.net.URI(url).host ?: return false } catch (_: Exception) { return false }
        if (sniffDeadHosts.get(host) != null) return true
        val until = sniffHostBlockedUntil[host] ?: return false
        return System.currentTimeMillis() < until
    }

    /** v33: the page itself is down (5xx / DNS): no point loading it again for a while. */
    internal fun parkSniffHost(url: String, why: String) {
        val host = try { java.net.URI(url).host ?: return } catch (_: Exception) { return }
        sniffHostFails[host] = 2
        sniffHostBlockedUntil[host] = System.currentTimeMillis() + sniffHostCooldownMs
        log("sniff: $host is down ($why) — parked for ${sniffHostCooldownMs / 60000} min")
    }

    internal fun noteSniffResult(url: String, ok: Boolean) {
        val host = try { java.net.URI(url).host ?: return } catch (_: Exception) { return }
        if (ok) { sniffHostFails.remove(host); sniffHostBlockedUntil.remove(host); sniffDeadHosts.remove(host); return }
        val fails = (sniffHostFails[host] ?: 0) + 1
        sniffHostFails[host] = fails
        if (fails >= 4) {
            sniffDeadHosts.put(host, "1")
            log("sniff: $host has not given a stream in $fails tries — not loading it again for a day")
        } else if (fails >= 2) {
            sniffHostBlockedUntil[host] = System.currentTimeMillis() + sniffHostCooldownMs
            log("sniff: $host parked for 15 min after $fails failures")
        }
    }

    // v22: hosts the hidden WebView refused on TLS grounds. The v21 log shows the WebView's
    // own network stack failing a handshake (net_error -202, ERR_CERT_AUTHORITY_INVALID,
    // "Trust anchor for certification path not found") ~2–4s into BOTH Sistenn sniffs —
    // right where the page should have asked for /api/v1/video — while the page's only
    // visible requests were /api/v1/info and a bundle. The WebView cancels such a request
    // silently, so the player just waits. Likely causes: an ISP block page answering for
    // that host (the app's own HTTP client may resolve DNS differently), or a server
    // missing an intermediate certificate. Once a host lands here, later requests to it
    // from the sniffer go through the app's HTTP client instead, which still validates
    // TLS fully — nothing is ever let through with a bad certificate.
    internal val sslBadHosts = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    internal val brokenTrackerHosts = listOf("mc.yandex.", "hdrc.yandex.net", "mdd.yandex.net", "an.yandex.")
    internal val charsetRe = Regex("""charset=([^;\s]+)""", RegexOption.IGNORE_CASE)

    internal fun sslErrorName(e: SslError?): String = when (e?.primaryError) {
        SslError.SSL_UNTRUSTED -> "untrusted CA"
        SslError.SSL_EXPIRED -> "expired"
        SslError.SSL_IDMISMATCH -> "hostname mismatch"
        SslError.SSL_NOTYETVALID -> "not yet valid"
        SslError.SSL_DATE_INVALID -> "date invalid"
        SslError.SSL_INVALID -> "invalid"
        else -> "error ${e?.primaryError}"
    }

    // v40: the per-stream maps the sniffer fills were never emptied — a small leak that grows
    // with every episode in a long session. They only matter for minutes after a sniff.
    internal fun trimSniffMaps() {
        for (m in listOf<MutableMap<String, *>>(sniffedHeaders, sniffedVariantUrls, sniffedSegmentUrls, sniffedSegmentSets, sniffedRefreshUrls))
            if (m.size > 60) m.clear()
        val now = System.currentTimeMillis()
        for (m in listOf(sistennApiOffUntil, driveStreamQuotaUntil, gdriveQuotaUntil)) m.entries.removeAll { it.value < now }
    }

    // One source's worth of step4 work: ex:/ap:/gd: embed -> an actual playable link via
    // callback(). Pulled out of loadLinks so it can be launched concurrently per-source as
    // each numId resolves, instead of running sequentially after every numId is done.
    internal suspend fun processSource(
        vi: VidInfo,
        embed: String,
        data: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        // v22: when set, a source that would need a browser sniff is handed back through
        // this instead of sniffed now (loadLinks retries it later, only if still needed).
        deferSniff: ((VidInfo, String) -> Unit)? = null,
        // v39: this load's time spent in browser sniffs (the per-episode cap).
        sniffSpent: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0L),
        // How many times this source was sent on to another address (a Sistenn resolve, a Beta-shaped host).
        hops: Int = 0,
    ): Boolean {
        // v48: who made it (translator, encoder, uploader), after the player's name; see fetchVideoInfo.
        val credits = if (settings.showCredits) videoCredits[vi.numId]?.let { AnizmParser.creditsFor(it, vi.fansub) }.orEmpty() else ""
        val label = "${vi.fansub} - ${vi.name.replace(adsRe, "").trim()}" + (if (credits.isEmpty()) "" else " · $credits")
        log("step4: $label embed=$embed")
        // An Aincrad or Beta button that no longer lands on its own player means the site moved
        // it; it then silently goes down the generic extractor/sniff path instead.
        val group = settings.groupOf(vi.name).key
        if (group == "aincrad" && !embed.startsWith("ap:"))
            logW("site-change warning: Aincrad source ${vi.fansub}/${vi.name} resolved to $embed, not an anizmplayer /video/ link")
        if (group == "beta" && !embed.startsWith("bp:"))
            logW("site-change warning: Beta Player source ${vi.fansub}/${vi.name} resolved to $embed, not a /watch/<hash> link")

        if (embed.startsWith("ex:")) return processExtractorSource(vi, embed, data, label, callback, subtitleCallback, deferSniff, sniffSpent, hops)
        if (embed.startsWith("ap:")) return processAincrad(embed, label, callback)
        if (embed.startsWith("gd:")) return processGDrive(embed, label, callback)
        if (embed.startsWith("bp:")) {
            if (processBeta(embed, label, callback)) return true
            // A /watch/<hash> address on a host that isn't Beta Player after all (see betaShapeRe).
            val hash = embed.removePrefix("bp:")
            val origin = betaOrigins[hash] ?: return false
            return if (hops == 0 && !origin.endsWith("puffytr.tr"))
                processExtractorSource(vi, "ex:$origin/watch/$hash", data, label, callback, subtitleCallback, deferSniff, sniffSpent, 1)
            else false
        }
        return false
    }

    internal val sistennGone = TtlCache<String, Boolean>(300, 30 * 60_000L)

    // Some Beta Player uploads are separately encoded parts joined into one playlist with no
    // #EXT-X-DISCONTINUITY between them (2026-10-06, faa18cda… 1080p: three ~473.39 s parts; at
    // segment 237 the video PTS drops from 474.83 s back to 1.48 s). ExoPlayer then waits for ever
    // at 7:53. analyseStream finds where the timestamps restart (Hls.findJoins); here the tag goes
    // in before each such segment and the playlist is served from the local server. The local
    // server hands out the same address for the same text, so a repeat load lists the same link.
    internal val betaMasterRe = Regex("""["']([^"']*/stream/[^"']*master[^"']*\.(?:txt|m3u8))["']""")

    /** v32: a 2xx answer that is actually media (by type, or binary content), not an error text. */
    internal fun looksLikeMedia(r: NiceResponse, text: String): Boolean {
        if (r.code !in 200..299 || text.isBlank()) return false
        val ct = (r.headers["Content-Type"] ?: "").lowercase()
        if (ct.startsWith("video/") || ct.startsWith("audio/") || ct.contains("octet-stream") || ct.contains("mp2t")) return true
        if (ct.startsWith("text/") || ct.contains("json") || ct.contains("html") || ct.contains("xml")) return false
        // No usable type: binary content (control bytes in the first 64 chars) counts as media.
        return text.take(64).count { it.code < 9 || (it.code in 14..31) || it == '\uFFFD' } > 4
    }

    internal val sistennKey = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
    internal val sistennIv = "1234567890oiuytr".toByteArray(Charsets.UTF_8)
    internal val sistennPageRe = Regex("""^https://([^/#?]+)/?#([A-Za-z0-9]{4,16})$""")
    internal val sistennApiOffUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    internal val sistennSessionId = java.util.UUID.randomUUID().toString()

    internal fun sistennCipher(mode: Int) = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
        init(mode, javax.crypto.spec.SecretKeySpec(sistennKey, "AES"), javax.crypto.spec.IvParameterSpec(sistennIv))
    }
    internal fun sistennDecrypt(hex: String): String? = try {
        val h = hex.trim()
        if (h.length < 32 || h.length % 2 != 0 || !h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) null
        else String(sistennCipher(javax.crypto.Cipher.DECRYPT_MODE).doFinal(ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }), Charsets.UTF_8)
    } catch (_: Exception) { null }
    internal fun sistennEncrypt(plain: String): String =
        sistennCipher(javax.crypto.Cipher.ENCRYPT_MODE).doFinal(plain.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Set (replace) query parameters on a URL. */
    internal fun withParams(url: String, params: Map<String, String>): String {
        if (params.isEmpty()) return url
        val base = url.substringBefore('?'); val frag = url.substringAfter('#', "")
        val q = LinkedHashMap<String, String>()
        url.substringAfter('?', "").substringBefore('#').split('&').filter { it.isNotEmpty() }.forEach { q[it.substringBefore('=')] = it.substringAfter('=', "") }
        params.forEach { (k, v) -> q[k] = java.net.URLEncoder.encode(v, "UTF-8") }
        return base + "?" + q.entries.joinToString("&") { "${it.key}=${it.value}" } + (if (frag.isNotEmpty()) "#$frag" else "")
    }

    // v41: the phone's screen as window.screen reports it (CSS pixels, current orientation).
    internal fun deviceScreenCss(): Pair<Int, Int> = try {
        val dm = com.lagradost.cloudstream3.CloudStreamApp.context!!.resources.displayMetrics
        val w = (dm.widthPixels / dm.density).toInt(); val h = (dm.heightPixels / dm.density).toInt()
        if (w in 200..4000 && h in 200..4000) w to h else 412 to 915
    } catch (_: Throwable) { 412 to 915 }

    internal val sistennBackupWaitMs = 9_000L
    internal val pkParamRe = Regex("""[?&]k=""")
    internal val tokenQueryRe = Regex("""(?:^|&)(k=[^&]+&kx=\d+)""")

    // ── HLS helpers ──────────────────────────────────────────────────────────
    // The parsing itself is in Hls.kt (no Android or network), which tools/check-hls.sh tests.
    internal fun parseVariants(master: String, masterUrl: String): List<Variant> = Hls.parseVariants(master, masterUrl)

    // ── Sizes and timestamp joins (v48) ──────────────────────────────────────
    // Why measured and not BANDWIDTH x duration: the declared rate is a nominal ceiling. Measured on
    // one episode (2026-11-10, 1422 s): Beta 1080p really 580 MB / 3.4 Mbps against 848 MB declared
    // (+46 %), Aincrad 1080p ~269 MB video against 694 MB declared (+158 %).
    //
    // How: ranged reads of a few segments spread over the video, one in each slice of equal playing
    // time, at places fixed per video (Hls.strata), so the same video always shows the same size.
    // Size = bytes of the samples / their playing time x total time. On the Beta episode above (every
    // segment's real size known, 2000 different sample placements): 16 samples were off by 2.8 % in
    // the middle case and under 7.1 % nine times in ten; 8 samples by 4.4 % and under 10.8 %.
    // Where the bitrate is very uneven (Aincrad: 0.2-4.6 MB per 6 s segment) a second set is taken.
    // Lower qualities are read at the same moments and scaled by their ratio to the top quality's
    // bytes there. On the same episode 8 such reads gave 720p +2.7 % and 480p +1.7 %; 4 reads were
    // off by 8 % and 14 %, so 8 are read (4 with "More accurate sizes" off).
    //
    // For Beta the reads fetch the first 16 KB instead of one byte: the answer's Content-Range still
    // gives the size, and the head gives the segment's first timestamp, so the same reads also find
    // where separately encoded parts were joined (see withJoinsMarked). About 34 reads for a video's
    // three qualities (about 70 for one with two joins), once: the result is kept across restarts,
    // as it never changes for a video.
    internal val mediaStore by lazy { PersistentCache(settings.prefs, "media_info", 300, 30L * 24 * 60 * 60 * 1000) }
    internal fun loadInfo(key: String): StreamInfo? = try {
        mediaStore.get(key)?.let { JSONObject(it) }?.let { o ->
            val s = o.optJSONObject("s"); val j = o.optJSONObject("j")
            StreamInfo(s?.keys()?.asSequence()?.associate { it.toInt() to s.getLong(it) } ?: emptyMap(),
                j?.keys()?.asSequence()?.associate { k -> k.toInt() to j.getJSONArray(k).let { a -> (0 until a.length()).map { a.getInt(it) } } } ?: emptyMap(),
                joinsChecked = o.optBoolean("c"))
        }
    } catch (_: Exception) { null }
    internal fun saveInfo(key: String, info: StreamInfo) {
        if (info.declared || (info.sizes.isEmpty() && !info.joinsChecked)) return // never keep a fallback or a failure
        try {
            mediaStore.put(key, JSONObject().apply {
                put("s", JSONObject().also { s -> info.sizes.forEach { (h, b) -> s.put(h.toString(), b) } })
                put("j", JSONObject().also { j -> info.joins.forEach { (h, at) -> j.put(h.toString(), JSONArray(at)) } })
                put("c", info.joinsChecked)
            }.toString())
        } catch (_: Exception) {}
    }
    internal fun cachedSizes(key: String): Map<Int, Long>? = loadInfo(key)?.sizes?.takeIf { it.isNotEmpty() }
    internal fun storeSizes(key: String, sizes: Map<Int, Long>) {
        if (sizes.isEmpty()) return
        val old = loadInfo(key)
        saveInfo(key, StreamInfo((old?.sizes ?: emptyMap()) + sizes, old?.joins ?: emptyMap(), joinsChecked = old?.joinsChecked ?: false))
    }
    /** "~" for a measured estimate, "≤" for the declared bitrate x duration (an upper bound). */
    internal fun sizeMark(info: StreamInfo?) = if (info?.declared == true) "≤" else "~"
    internal val sizeSamples get() = if (settings.accurateSizes) 16 else 8

    // A playlist read by more than one step is downloaded once and reused for 2 minutes. The URLs are
    // per video (and often signed), and these VOD playlists don't change; only real playlists are kept.
    internal val playlistTexts = TtlCache<String, String>(60, 2 * 60 * 1000L)
    internal fun recentPlaylist(url: String): String? = playlistTexts[url]
    internal suspend fun playlistText(url: String, headers: Map<String, String>, timeoutSec: Long): String {
        recentPlaylist(url)?.let { return it }
        val text = app.get(url, headers = headers, timeout = timeoutSec).text
        if (Hls.isPlaylist(text)) playlistTexts[url] = text
        return text
    }

    internal fun formatSize(bytes: Long?, isEstimate: Boolean = false): String = Hls.formatSize(bytes, if (isEstimate) "~" else "")
    internal fun parseContentRangeTotal(headers: okhttp3.Headers): Long? =
        headers["Content-Range"]?.substringAfterLast('/')?.trim()?.toLongOrNull()
            ?: headers["Content-Length"]?.toLongOrNull()

    internal val resolutionInNameRe = Regex("""(?i)(?<![0-9])(2160|1440|1080|720|576|480|360)[pi](?![a-z0-9])""")
    internal val fourKInNameRe = Regex("""(?i)\b(4k|uhd)\b""")
    internal val ucSizeRe = Regex("""\(\s*([\d.,]+)\s*([KMGT])B?\s*\)""", RegexOption.IGNORE_CASE)

    internal fun heightFromName(name: String?): Int? {
        if (name.isNullOrBlank()) return null
        resolutionInNameRe.find(name)?.let { return it.groupValues[1].toInt() }
        return if (fourKInNameRe.containsMatchIn(name)) 2160 else null
    }
    internal val dispositionExtRe = Regex("""filename\*\s*=\s*[^']*'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
    internal val dispositionRe = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
    internal val htmlTitleRe = Regex("<title>([^<]*)", RegexOption.IGNORE_CASE)
    internal fun filenameFromDisposition(headers: okhttp3.Headers): String? {
        val cd = headers["Content-Disposition"] ?: return null
        dispositionExtRe.find(cd)?.let {
            return try { java.net.URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") } catch (_: Exception) { it.groupValues[1] }
        }
        return dispositionRe.find(cd)?.groupValues?.get(1)?.trim()
    }
    internal fun sizeFromRanged(r: NiceResponse): Long? = when (r.code) {
        206 -> r.headers["Content-Range"]?.substringAfterLast('/')?.trim()?.toLongOrNull()
        200 -> r.headers["Content-Length"]?.toLongOrNull()?.takeIf { it > 65_536 } // server ignored Range
        else -> null
    }
    internal fun bodyBytes(r: NiceResponse): ByteArray? =
        try { r.okhttpResponse.body?.bytes() } catch (_: Exception) { null }

    internal fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
        ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)
    internal fun be64(b: ByteArray, o: Int): Long = (be32(b, o) shl 32) or be32(b, o + 4)
    internal fun boxType(b: ByteArray, o: Int) = String(b, o, 4, Charsets.ISO_8859_1)

    internal fun isHtml(r: NiceResponse) = (r.headers["Content-Type"] ?: "").contains("text/html", ignoreCase = true)

    @Volatile internal var driveApiKey: String? = null
    internal val driveKeyRe = Regex("""AIza[0-9A-Za-z_\-]{35}""")
    internal val driveStreamCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<DriveStream>, Long>>()
    internal val driveStreamTtlMs = 2 * 60 * 60 * 1000L // links live 3 h
    internal val driveStreamQuotaUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // v33: files whose download quota ran out. Google resets it over the day; asking again on
    // every load only adds to the count, so the file is skipped for a few hours.
    internal val gdriveQuotaUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    internal val gdriveQuotaBackoffMs = 3 * 60 * 60 * 1000L

}
