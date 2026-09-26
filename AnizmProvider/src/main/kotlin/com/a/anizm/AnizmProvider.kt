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

class AnizmProvider(private val settings: AnizmSettings) : MainAPI() {

    // v11: lets the settings screen run the connection self-test on demand, instead of the
    // user having to force-stop the app and catch the one automatic run in a logcat.
    companion object {
        @Volatile internal var instance: AnizmProvider? = null
        @Volatile internal var lastProbeTarget: Pair<String, String>? = null // numId, episode url
    }
    init { instance = this }

    /** Called from the settings screen. Returns the report lines (also written to logcat). */
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
    private var playerBase = "https://anizmplayer.com"
    // Derived, not hardcoded — if the site ever moves domains, only mainUrl needs updating;
    // this and every check that uses it follow automatically instead of silently going stale.
    // v40: a getter, not lazy, so it follows a domain move (below).
    private val mainHost: String get() = android.net.Uri.parse(mainUrl).host ?: "anizm.net"

    // v40: domain moves. Turkish sites get blocked and move (anizm.net → anizm.tv, anizm2.net…),
    // usually leaving a redirect behind. When an anizm request ends up on another anizm host, that
    // host becomes mainUrl and is remembered across restarts; URLs saved under an old host
    // (bookmarks, history, episode data) are sent to the current one. Only hosts named
    // "anizm<digits>.<tld>" count — never anizmplayer.com or a block/notice page.
    private val siteHostRe = Regex("""^(?:www\.)?anizm\d*\.[a-z]{2,10}(?:\.[a-z]{2,3})?$""")
    init {
        settings.siteHost?.takeIf { siteHostRe.matches(it) }?.let { mainUrl = "https://$it" }
    }
    private fun onCurrentHost(url: String): String {
        val host = try { java.net.URI(url).host } catch (_: Exception) { null } ?: return url
        if (host == mainHost || !siteHostRe.matches(host)) return url
        return url.replaceFirst("//$host", "//$mainHost")
    }
    private fun noteFinalHost(requested: String, r: NiceResponse) {
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
    private val gdriveGate = Semaphore(1)
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
    private val ua: String by lazy {
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
    private val chromeMajor: String by lazy {
        Regex("""Chrome/(\d+)""").find(ua)?.groupValues?.get(1) ?: "152"
    }
    // Old stub system: CloudflareKiller is directly on the compile classpath again
    // (it's part of the full pre-release APK), no reflection needed.
    private val cfKiller = CloudflareKiller()
    // 4.0: CSRF/session machinery removed. Verified on the live site (2026-09-17):
    // /searchAnime answers identically with or without _token, and nothing else used the
    // token — so every getSession() was a full homepage download (every 5 min, before
    // search/load/loadLinks) for nothing, and search leaked the token into a query string.
    // What replaced it: siteGet() below, which handles the one thing that matters —
    // Cloudflare — and does it on the actual request instead of on the homepage.
    @Volatile private var cfUntil = 0L
    private val cfStickyMs = 10 * 60 * 1000L
    private val cfSolveBudgetMs = 20_000L
    // v5: set when anizm refuses a direct (OkHttp) /player/ lookup. While set, lookups go
    // straight to the in-app WebView resolver — a real browser engine — instead of
    // retrying a request that's being blocked (and adding to whatever triggered the block).
    @Volatile private var playerHttpBlockedUntil = 0L
    private val playerHttpBlockCooldownMs = 15 * 60 * 1000L
    private class PlayerLookupBlocked(val code: Int) : Exception("blocked: http $code")

    // ── Tuning: stealth vs. speed ────────────────────────────────────────────
    // (1) Pacing of /player/{id} lookups. At most `resolveConcurrency` in flight, and
    // successive request STARTS are spaced by a random gap in [min, max] across all of
    // them (a shared pacer, not a per-slot sleep — so 2 slots can't fire back-to-back).
    // resolveConcurrency = 1 is strictly sequential. Worst-case cost for a 16-source
    // episode with lazy resolve off: ~16 × avg gap (≈4.4s at 150–400ms), still faster than
    // the old WebView path (1.5s timeout per dead id + page/iframe load per id).
    // v41: one at a time, 0.5-1.2 s apart (was 2 at a time, 0.15-0.4 s). A person switching
    // players doesn't open three within a second; about 1-2 s more per episode.
    private val resolveConcurrency = 1
    private val resolveGapMinMs = 500L
    private val resolveGapMaxMs = 1200L
    // v44: one gate for the whole provider, so overlapping waves (and loads) share the limit.
    private val resolveGate = Semaphore(resolveConcurrency.coerceAtLeast(1))

    // (2) Which players to use, (4) lazy loading and the size estimate are user settings
    // now — see AnizmSettings (Extensions screen → Anizm → settings). Read on every call.
    // Lazy waves grow: 3 sources, then 6, then 12… so an old episode where only one
    // obscure source still works doesn't crawl through it 3 at a time.
    private val lazyFirstWaveSize = 3

    // 4.0: the size estimate's extra requests (see sampleRendition). User setting.
    private val estimateHlsSizes get() = settings.estimateSizes
    // v7: 4s → 8s. Real-device log: an Aincrad source came up without a size on first load
    // (budget ran out on a cold connection) and with one on the next.
    private val sizeEstimateBudgetMs = 8_000L

    // v7: why Aincrad links doubled (2 → 4) when an episode was loaded again: getVideo hands
    // out a fresh signed URL (…master.m3u8?md5=…&expires=…) on every call, so the second
    // loadLinks emitted the "same" stream under a different URL — and, since the first load
    // had no size, a different name too. CloudStream treats that as a new link. Caching the
    // signed URL (until shortly before it expires) and the size estimate per stream makes a
    // repeat load emit byte-identical links, which the app de-duplicates.
    private data class AincradSource(val videoSource: String, val securedLink: String, val validUntil: Long, val cookie: String = "")
    private val aincradCache = java.util.concurrent.ConcurrentHashMap<String, AincradSource>()
    private val expiresParamRe = Regex("""[?&]expires=(\d{9,13})""")
    private val sizeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Map<Int, Long>, Long>>()
    private val sizeCacheTtlMs = 60 * 60 * 1000L
    private fun cachedSizes(key: String): Map<Int, Long>? =
        sizeCache[key]?.takeIf { System.currentTimeMillis() - it.second < sizeCacheTtlMs }?.first
    private fun storeSizes(key: String, sizes: Map<Int, Long>) {
        if (sizes.isEmpty()) return // never cache a failure: next load gets another try
        sizeCache[key] = sizes to System.currentTimeMillis()
        if (sizeCache.size > 100) sizeCache.entries.minByOrNull { it.value.second }?.let { sizeCache.remove(it.key) }
    }

    // 4.0: short cache of an episode's source list. Prefetch + manual click (or reopening
    // the source picker) used to re-download the ~890KB episode page and every translator
    // XHR each time; embedCache only covered the step after that.
    private val sourceListCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<VidInfo>, Long>>()
    private val sourceListTtlMs = 10 * 60 * 1000L
    // v9: last loadLinks time per episode, for the reload detection in loadLinks.
    private val lastLoadAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val reloadMinGapMs = 5_000L      // below this it's the app's own preload + open
    private val reloadWindowMs = 30 * 60 * 1000L

    // Hash cache — numId → embed string, with timestamps for TTL
    // Hashes are content-based (not session-based) so safe to cache
    private data class CachedEmbed(val embed: String, val time: Long)
    // Was local to loadLinks; now needed at class level so processSource() can take it
    // as a parameter — loadLinks dispatches each source to it as soon as that source's
    // numId resolves, instead of waiting for every source in the episode to resolve first.
    private data class VidInfo(val numId: String, val name: String, val fansub: String)
    // ConcurrentHashMap: loadLinks can run concurrently (prefetch + user click)
    private val embedCache = java.util.concurrent.ConcurrentHashMap<String, CachedEmbed>()
    private val cacheTtlMs = 30 * 60 * 1000L // 30 minutes

    // 2.12: short cache for getMainPage — the home/list rows don't change second to
    // second, and CloudStream re-calls getMainPage on things like tab switches and
    // pull-to-refresh far more often than the underlying page content actually changes.
    private val mainPageCache = java.util.concurrent.ConcurrentHashMap<String, Pair<HomePageResponse, Long>>()
    private val mainPageCacheTtlMs = 3 * 60 * 1000L // 3 minutes — short on purpose, this is a burst-of-taps guard, not a real cache
    private val mainPageCacheLock = Any()

    private fun getCached(numId: String): String? {
        val c = embedCache[numId] ?: return null
        if (System.currentTimeMillis() - c.time > cacheTtlMs) { embedCache.remove(numId); return null }
        return c.embed
    }
    private val cacheEvictionLock = Any()
    private fun putCache(numId: String, embed: String) {
        embedCache[numId] = CachedEmbed(embed, System.currentTimeMillis())
        if (embedCache.size > 200) {
            synchronized(cacheEvictionLock) {
                val now = System.currentTimeMillis()
                embedCache.entries.removeAll { now - it.value.time > cacheTtlMs } // Kotlin stdlib, safe on minSdk 21
                // Hard cap even if everything is fresh: drop oldest down to 150
                if (embedCache.size > 200) {
                    repeat((embedCache.size - 150).coerceAtLeast(0)) {
                        embedCache.entries.minByOrNull { it.value.time }?.let { oldest ->
                            embedCache.remove(oldest.key)
                        }
                    }
                }
            }
        }
    }

    // Pre-compiled regexes — avoid recompilation in hot paths
    private val apRe = Regex("""(https?://[a-z0-9]*player[a-z0-9]*\.[a-z.]+)/(?:video|player)/([a-f0-9]{24,40})""", RegexOption.IGNORE_CASE)
    private val gdRe = Regex("""drive\.google\.com/(?:file/d/|uc\?[^"]*id=|open\?[^"]*id=)([A-Za-z0-9_-]{20,})""")
    // Beta Player: /player/{numId} redirects to pl.puffytr.tr/watch/{hash} — a genuinely
    // new backend, unrelated to Aincrad/anizmplayer.com despite similar button styling.
    private val puffytrRe = Regex("""puffytr\.tr/watch/([a-f0-9]+)""", RegexOption.IGNORE_CASE)
    // Master parsing: resolution + bandwidth + the variant URI on the line after — shared
    // between Aincrad's HLS master and Beta Player's master.txt, same format either way.
    private val streamInfRe = Regex("""#EXT-X-STREAM-INF:([^\n]*)\n\s*([^#\s]\S*)""")
    private val resAttrRe = Regex("""RESOLUTION=\d+x(\d+)""")
    private val bwAttrRe = Regex("""BANDWIDTH=(\d+)""")
    private val extinfRe = Regex("""#EXTINF:([\d.]+)""")
    private val audioMediaUriRe = Regex("""#EXT-X-MEDIA:[^\n]*TYPE=AUDIO[^\n]*URI="([^"]+)"""")
    private val byteRangeRe = Regex("""#EXT-X-BYTERANGE:(\d+)""")
    // Hosts CloudStream ships extractors for. Anything matching gets forwarded to
    // loadExtractor(), so upstream maintains them — new host on anizm = add one keyword.
    private val extractorHostKeywords = listOf(
        "voe", "sibnet", "dood", "vidmoly", "ok.ru", "okru", "odnoklassniki",
        "sendvid", "mp4upload", "uqload", "hdvid", "abyss")
    // csrfRe1/csrfRe2 removed (3.1) — was two regexes to handle attribute-order variation
    // (name-then-content vs content-then-name). Jsoup's selector doesn't care about
    // attribute order at all, so one query replaces both, and can't break the same way
    // if the site ever reorders these attributes again.
    private val numIdRe = Regex("""/video/(\d+)""")
    private val trRe1 = Regex("""translator="([^"]+)"[^>]*data-fansub-name="([^"]*)""")
    private val trRe2 = Regex("""data-fansub-name="([^"]*)"[^>]*translator="([^"]+)""")
    private val vidRe1 = Regex("""video="([^"]+)"[^>]*data-video-name="([^"]*)""")
    private val vidRe2 = Regex("""data-video-name="([^"]*)"[^>]*video="([^"]+)""")
    private val cleanRe = Regex("""\s*[-–]\s*Anizm[.\w]*$""", RegexOption.IGNORE_CASE)
    private val adsRe = Regex("""\([Rr]eklamsız\)""")
    private val qualityTagRe = Regex("""\b\d{3,4}[pP]\b""")
    private val urlRe = Regex("""https?://[^\s"'<>\\]+""")
    // 1.9: remaining inline regexes, hoisted with the plan's requested names
    private val hlsResolutionRe = Regex("""RESOLUTION=\d+x(\d+)""")
    private val emptyParenRe = Regex("""\(\s*\)""")
    private val multiSpaceRe = Regex("""\s{2,}""")
    private val mainPosterCommentRe = Regex("""<!--src="([^"]+)"""")
    private val episodeLabelCleanupRe = Regex("""\s*\d+\.?\s*[Bb][oöô]l[uüû]m.*$""")
    private val episodeUrlCleanupRe = Regex("""-\d+[-.]?bolum[^/?#]*""")
    private val ogAnizmCleanupRe = Regex("""\s*[-|–]\s*Anizm.*$""", RegexOption.IGNORE_CASE)
    private val ogIzleCleanupRe = Regex("""\s+[iİ]zle\b.*$""", RegexOption.IGNORE_CASE)
    private val ogIzleTruncRe = Regex("""\s+[iİ]z?l?e?\.{2,}.*$""", RegexOption.IGNORE_CASE)
    private val asciiOnlyRe = Regex("^[\\x20-\\x7E]+$")
    private val yearRe = Regex("""\d{4}""")
    private val rangeLabelRe = Regex("""\d+\s*-\s*\d+\.?\s*[Bb][oöô]l[uüû]m""")
    private val singleEpLabelRe = Regex("""^(\d+)\.?\s*[Bb][oöô]l[uüû]m""")
    private val singleEpUrlRe = Regex("""-(\d+)-bolum(?:-|$)""")
    // gdriveConfirmRe/gdriveUuidRe removed (3.1) — same reasoning as the CSRF regexes:
    // Jsoup's input[name=...] selector doesn't care about attribute order, and is also
    // more tolerant of the deliberately-truncated HTML this fetch produces (Range-limited).

    // 1.5: single shared cleaner for every path that displays a source name, so quality
    // text can never appear twice (the original "1080p 1080p" bug) or leak through
    // unstripped in whichever path nobody's tested most recently. Intentionally dumb:
    // strips quality tokens and formatting debris only, never touches fansub/title text.
    private fun cleanDisplayName(raw: String): String = raw
        .replace(qualityTagRe, "")
        .replace(emptyParenRe, "")
        .replace(multiSpaceRe, " ")
        .trim().trimEnd('-', ' ').trim()

    // Reusable empty response — avoid allocations in shouldInterceptRequest (called 100s of times)
    private val emptyBytes = ByteArray(0)
    private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(emptyBytes))

    private fun log(msg: String) { android.util.Log.i("Anizm", msg) }
    private fun logW(msg: String) { android.util.Log.w("Anizm", msg) }

    private fun isCf(html: String) = html.contains("Just a moment", true) ||
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
    private fun looksCfBlocked(r: NiceResponse): Boolean {
        val challengeHeader = r.headers["cf-mitigated"]?.contains("challenge", true) == true
        return when (r.code) {
            403, 429, 503 -> challengeHeader || isCf(r.text)
            // 3xx: never read the body of a redirect we deliberately didn't follow
            in 300..399 -> false
            else -> r.code in 200..299 && r.text.length < 60_000 && isCf(r.text)
        }
    }

    // v44: shared small helpers.
    private val acceptLang = "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    private val wsRe = Regex("""\s+""")
    /** A response body (or any text) on one line for a log, without rewriting all of it first. */
    private fun oneLine(text: String, max: Int): String = wsRe.replace(text.take(max * 2), " ").take(max)

    // ── Chrome's network stack (v41) ─────────────────────────────────────────
    // The UA and client hints say "Chrome on Android", but OkHttp's TLS handshake (cipher list,
    // extensions, ALPN) and HTTP/2 settings are plainly not Chrome's: the mismatch Cloudflare's
    // bot scoring (JA3/JA4, HTTP/2 fingerprint) exists to catch. CloudStream already ships
    // Chrome's own network stack (Cronet from Google Play services; the device log shows
    // "Cronet version: 151…", and ExoPlayer plays through it). Requests to the sites that can
    // judge us (anizm, the players' APIs, Google Drive) now go through it. It is reached by
    // reflection, so there is no build dependency on it; if it is missing or fails, the request
    // goes out through OkHttp as before.
    private val cronetEngine: Any? by lazy {
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
    private val cronetOpen: java.lang.reflect.Method? by lazy {
        try { Class.forName("org.chromium.net.CronetEngine").getMethod("openConnection", java.net.URL::class.java) } catch (_: Throwable) { null }
    }
    // Five failures in a row (not timeouts) and the Chrome stack is left alone for this session.
    @Volatile private var cronetFailures = 0

    // Chrome's header order on the wire: one for navigations, one for fetch/XHR.
    private val chromeNavOrder = listOf("sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "upgrade-insecure-requests", "user-agent",
        "accept", "sec-fetch-site", "sec-fetch-mode", "sec-fetch-user", "sec-fetch-dest", "referer", "accept-language", "cookie", "range")
    private val chromeXhrOrder = listOf("sec-ch-ua-platform", "x-requested-with", "user-agent", "accept", "sec-ch-ua", "content-type",
        "sec-ch-ua-mobile", "origin", "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest", "referer", "accept-language", "cookie", "range")
    private fun chromeOrdered(h: Map<String, String>): List<Pair<String, String>> {
        val nav = h.entries.any { it.key.equals("Sec-Fetch-Mode", true) && it.value == "navigate" }
        val order = if (nav) chromeNavOrder else chromeXhrOrder
        return h.entries.map { it.key to it.value }.sortedBy { (k, _) -> order.indexOf(k.lowercase()).let { i -> if (i < 0) order.size else i } }
    }

    /**
     * One request through Chrome's network stack. Null when the stack is unavailable or the
     * request failed for a reason other than a timeout (the caller then uses OkHttp); a timeout
     * throws SocketTimeoutException, as OkHttp would. Redirects are followed here, hop by hop,
     * so the final URL is known. The body is read up to maxBytes.
     */
    private suspend fun chromeFetch(url: String, headers: Map<String, String>, timeoutSec: Long, allowRedirects: Boolean = true,
                                    formBody: Map<String, String>? = null, maxBytes: Int = 6_000_000): NiceResponse? {
        if (cronetFailures >= 5) return null
        val engine = cronetEngine ?: return null
        val open = cronetOpen ?: return null
        val deadline = System.currentTimeMillis() + timeoutSec.coerceAtLeast(1L) * 1000
        var current = url
        var method = if (formBody != null) "POST" else "GET"
        var body = formBody?.entries?.joinToString("&") { "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }?.toByteArray()
        for (hop in 0..5) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw java.net.SocketTimeoutException("timeout")
            val conn = try { open.invoke(engine, java.net.URL(current)) as java.net.HttpURLConnection }
                catch (t: Throwable) { cronetFailures++; log("net: Chrome stack could not open ${current.substringBefore('?').takeLast(60)} (${t.javaClass.simpleName})"); return null }
            var timedOut = false
            val result: Pair<Int, ByteArray>? = coroutineScope {
                val watchdog = launch { delay(left); timedOut = true; try { conn.disconnect() } catch (_: Throwable) {} }
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        conn.requestMethod = method
                        conn.instanceFollowRedirects = false
                        conn.connectTimeout = left.toInt(); conn.readTimeout = left.toInt()
                        for ((k, v) in chromeOrdered(headers))
                            if (!k.equals("Accept-Encoding", true) && !k.equals("Content-Length", true)) conn.setRequestProperty(k, v)
                        body?.let { b ->
                            if (headers.keys.none { it.equals("Content-Type", true) }) conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                            conn.doOutput = true; conn.setFixedLengthStreamingMode(b.size); conn.outputStream.use { it.write(b) }
                        }
                        val code = conn.responseCode
                        // v42: a 3xx has no body to read; Cronet throws if asked (device log: every
                        // /player/ lookup fell back to OkHttp, and five of them switched Cronet off).
                        val bytes = if (code in 300..399 || code == 204 || code == 304) ByteArray(0)
                            else (if (code >= 400) conn.errorStream else conn.inputStream)?.use { readUpTo(it, maxBytes) } ?: ByteArray(0)
                        code to bytes
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    if (timedOut) throw java.net.SocketTimeoutException("timeout")
                    cronetFailures++
                    log("net: Chrome stack failed on ${current.substringBefore('?').takeLast(60)} (${e.javaClass.simpleName}: ${e.message?.take(60)}) — using OkHttp")
                    null
                } finally { watchdog.cancel() }
            }
            if (result == null) { try { conn.disconnect() } catch (_: Throwable) {}; return null }
            val code = result.first
            val loc = conn.getHeaderField("Location")
            if (allowRedirects && code in 300..399 && !loc.isNullOrBlank()) {
                try { conn.disconnect() } catch (_: Throwable) {}
                current = try { java.net.URI(current).resolve(loc.trim()).toString() } catch (_: Exception) { return null }
                if (code == 303 || ((code == 301 || code == 302) && method == "POST")) { method = "GET"; body = null }
                continue
            }
            cronetFailures = 0
            val hb = okhttp3.Headers.Builder()
            conn.headerFields?.forEach { (k, vs) ->
                if (k != null && !k.equals("content-encoding", true) && !k.equals("content-length", true))
                    vs?.forEach { v -> if (v != null) try { hb.addUnsafeNonAscii(k, v) } catch (_: Exception) {} }
            }
            val ctype = conn.contentType
            try { conn.disconnect() } catch (_: Throwable) {}
            val resp = okhttp3.Response.Builder()
                .request(okhttp3.Request.Builder().url(current).build())
                .protocol(okhttp3.Protocol.HTTP_2).code(code).message("")
                .headers(hb.build())
                .body(result.second.toResponseBody(ctype?.toMediaTypeOrNull()))
                .build()
            return NiceResponse(resp, null)
        }
        return null // too many redirects: OkHttp reports it
    }
    private fun readUpTo(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16_384)
        while (out.size() < max) { val n = input.read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) }
        return out.toByteArray()
    }
    /** Chrome's network stack, or OkHttp when it is unavailable. */
    private suspend fun browserGet(url: String, headers: Map<String, String>, timeoutSec: Long, allowRedirects: Boolean = true): NiceResponse =
        chromeFetch(url, headers, timeoutSec, allowRedirects) ?: app.get(url, headers = headers, timeout = timeoutSec, allowRedirects = allowRedirects)
    private fun withQuery(url: String, params: Map<String, String>): String = if (params.isEmpty()) url else
        url + (if ('?' in url) "&" else "?") + params.entries.joinToString("&") { "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }

    // v41: one browser session. anizm requests carry the cookies the site set (and those the
    // in-app WebView picked up, Cloudflare's included) and keep the ones it sets, in the WebView's
    // own cookie store. Before, every request arrived with no cookies, so each one started a
    // fresh server session, which is not what a browser does.
    private fun withSiteCookies(url: String, headers: Map<String, String>): Map<String, String> {
        if (headers.keys.any { it.equals("Cookie", true) }) return headers
        val c = try { CookieManager.getInstance().getCookie(url) } catch (_: Throwable) { null }
        return if (c.isNullOrBlank()) headers else headers + ("Cookie" to c)
    }
    private fun keepSiteCookies(r: NiceResponse) {
        val set = try { r.headers.values("Set-Cookie") } catch (_: Exception) { emptyList() }
        if (set.isEmpty()) return
        try { val cm = CookieManager.getInstance(); val u = r.url; for (c in set) cm.setCookie(u, c) } catch (_: Throwable) {}
    }

    // 4.0: every anizm.net request goes through here. Replaces getSession() + the
    // 403/419 "refresh session and retry" loop, which had a real bug: the refresh ran the
    // homepage through CloudflareKiller, but the retry itself did NOT use the interceptor,
    // and CloudflareKiller only attaches its cf_clearance cookie to requests it
    // intercepts — so once CF turned a challenge on, everything but the homepage kept
    // failing. Now the blocked request itself is retried through cfKiller, and we stay
    // in that mode for a while instead of eating a failed request every time.
    private suspend fun siteGet(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String> = emptyMap(),
        timeout: Long = 12L,
        allowRedirects: Boolean = true,
    ): NiceResponse {
        val sticky = System.currentTimeMillis() < cfUntil
        val target = onCurrentHost(url)
        val h = withSiteCookies(target, headers)
        // v41: Chrome's network stack first; OkHttp (with CloudflareKiller while a solve is fresh) otherwise.
        val first = (if (sticky) null else chromeFetch(withQuery(target, params), h, timeout, allowRedirects))
            ?: app.get(target, headers = h, params = params, timeout = timeout,
                allowRedirects = allowRedirects, interceptor = if (sticky) cfKiller else null)
        keepSiteCookies(first)
        if (sticky || !looksCfBlocked(first)) return first.also { if (allowRedirects) noteFinalHost(target, it) }
        log("cf: challenge on ${target.substringBefore('?')} (${first.code}), retrying via CloudflareKiller")
        // v5: hard cap. CloudflareKiller's own WebView wait is 60s, which alone can eat half
        // of CloudStream's 120s loadLinks budget.
        val solved = withTimeoutOrNull(cfSolveBudgetMs) {
            app.get(target, headers = h, params = params, timeout = timeout,
                allowRedirects = allowRedirects, interceptor = cfKiller)
        }?.also { keepSiteCookies(it) }
        if (solved == null || looksCfBlocked(solved)) { log("cf: solve failed/timed out for ${target.substringBefore('?')}"); return solved ?: first }
        cfUntil = System.currentTimeMillis() + cfStickyMs // only sticky once a solve actually worked
        return solved
    }

    private suspend fun siteText(url: String, headers: Map<String, String>,
                                 params: Map<String, String> = emptyMap(), timeout: Long = 12L): String? {
        val r = siteGet(url, headers, params, timeout)
        if (r.code !in 200..299) { log("http ${r.code} for ${url.substringBefore('?')}"); return null }
        return r.text
    }

    private suspend fun siteDocument(url: String, headers: Map<String, String>, timeout: Long = 12L): org.jsoup.nodes.Document? =
        siteText(url, headers, timeout = timeout)?.let { org.jsoup.Jsoup.parse(it, url) }

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
    private val chromeBrandList: String by lazy {
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
    private val clientHints get() = if ((chromeMajor.toIntOrNull() ?: 152) < 90) emptyMap() else mapOf(
        "sec-ch-ua" to chromeBrandList,
        // v42: from the UA. TV boxes and tablets have no "Mobile" in it and Chrome sends ?0 there.
        "sec-ch-ua-mobile" to (if (ua.contains(" Mobile")) "?1" else "?0"),
        "sec-ch-ua-platform" to "\"Android\"")
    // v41: Chrome's own Accept for a document. The old value (…*/*;q=0.8 with nothing else) is
    // Firefox's, which contradicts a Chrome UA.
    private val chromeDocAccept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
    private val navHints get() = clientHints + mapOf(
        "Sec-Fetch-Dest" to "iframe", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Site" to "same-origin",
        "Upgrade-Insecure-Requests" to "1",
        "Accept" to chromeDocAccept)

    private val baseHeaders get() = clientHints + mapOf(
        "User-Agent" to ua,
        "Accept-Language" to acceptLang, "Referer" to "$mainUrl/")
    // v41: page loads (home, anime and episode pages) as Chrome sends them after a click. They
    // used to go out with no Accept and no Sec-Fetch-* at all, beside a Chrome UA and client hints.
    private val pageHeaders get() = baseHeaders + mapOf(
        "Upgrade-Insecure-Requests" to "1", "Accept" to chromeDocAccept,
        "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-User" to "?1", "Sec-Fetch-Dest" to "document")
    private val xhrHeaders get() = clientHints + mapOf(
        "Sec-Fetch-Dest" to "empty", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Site" to "same-origin",
        "User-Agent" to ua,
        "Accept-Language" to acceptLang, "Origin" to mainUrl, "Referer" to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json, text/javascript, */*; q=0.01")

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
    // page just to learn its URL, and no per-id 1.5s timeouts. Parallel (3 at a time).
    private val playerBaseLock = Any()
    // v31: the domain each "ap:" hash was found on. apRe accepts any *player* host with a
    // /video/<hex> path (FirePlayer clones included), but every ap: source used to be sent to
    // the one global playerBase — whichever such host was seen last. An episode with Aincrad
    // plus another FirePlayer host then asked the wrong server for half its getVideo calls.
    private val apDomains = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun noteApDomain(hash: String, domain: String) {
        apDomains[hash] = domain
        if (apDomains.size > 400) apDomains.clear() // lookups fall back to playerBase
    }

    private fun classifyEmbedUrl(url: String): String? {
        val host = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
        if (host.isBlank() || host.endsWith(mainHost)) return null // self-redirect = not an embed
        puffytrRe.find(url)?.let { return "bp:${it.groupValues[1]}" }
        apRe.find(url)?.let { m ->
            val domain = m.groupValues[1]
            synchronized(playerBaseLock) {
                if (!playerBase.contains(domain.substringAfter("://"))) { playerBase = domain; log("resolve: player domain updated to $domain") }
            }
            noteApDomain(m.groupValues[2], domain)
            return "ap:${m.groupValues[2]}"
        }
        gdRe.find(url)?.let { return "gd:${it.groupValues[1]}" }
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
    private fun webViewCookies(): String? = try {
        CookieManager.getInstance().getCookie(mainUrl)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) { null }

    /**
     * v19: cookies the hidden WebView picked up for one specific host. A sniffed stream often
     * lives behind the same session the page set up; ExoPlayer gets its own cookie jar, so
     * anything the player needs has to be copied onto the link's headers by hand.
     */
    private fun cookiesFor(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) { null }

    // One-shot self-test, run the first time a direct lookup is refused. It replays the same
    // request a few different ways and logs what each one gets back, so a single logcat shows
    // whether the block is about the path, the headers, the missing cookies or the client
    // itself — instead of us guessing one variable at a time across builds.
    @Volatile private var diagnosedThisSession = false
    private suspend fun diagnoseDirectBlock(nid: String, episodeUrl: String, collect: ((String) -> Unit)? = null) {
        if (diagnosedThisSession) return
        diagnosedThisSession = true
        fun report(line: String) { logW(line); collect?.invoke(line) }
        val playerUrl = "$mainUrl/player/$nid"
        val cookies = webViewCookies()
        val chromeHints = navHints
        val cases = listOf<Triple<String, String, Map<String, String>>>(
            Triple("player, minimal", playerUrl, mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")),
            Triple("player, episode referer", playerUrl, baseHeaders + mapOf("Referer" to onCurrentHost(episodeUrl))),
            Triple("player, no user-agent", playerUrl, mapOf("Referer" to "$mainUrl/")),
            Triple("player, chrome hints", playerUrl, baseHeaders + mapOf("Referer" to onCurrentHost(episodeUrl)) + chromeHints),
            Triple("player, webview cookies", playerUrl, baseHeaders + mapOf("Referer" to onCurrentHost(episodeUrl)) +
                (cookies?.let { mapOf("Cookie" to it) } ?: emptyMap())),
            Triple("player, cookies + hints", playerUrl, baseHeaders + mapOf("Referer" to onCurrentHost(episodeUrl)) + chromeHints +
                (cookies?.let { mapOf("Cookie" to it) } ?: emptyMap())),
            Triple("episode page (control)", episodeUrl, baseHeaders),
            Triple("home page (control)", "$mainUrl/", baseHeaders),
        )
        report("diag: self-test (webview cookies: ${cookies?.split(";")?.size ?: 0})")
        for ((name, url, headers) in cases) {
            val line = try {
                val r = app.get(url, headers = headers, timeout = 8L, allowRedirects = false)
                val body = if (r.code in 200..299 && (r.headers["Content-Type"] ?: "").contains("html", true)) r.text.take(200) else ""
                val marker = when {
                    body.contains("Just a moment", true) -> " challenge-page"
                    body.contains("blocked", true) || body.contains("Attention Required", true) -> " block-page"
                    else -> ""
                }
                val loc = r.headers["Location"]?.take(60)?.let { " → $it" } ?: ""
                "${r.code} server=${r.headers["server"]} cf-mitigated=${r.headers["cf-mitigated"]} ray=${r.headers["cf-ray"]}$loc$marker"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { "exception ${e.javaClass.simpleName}: ${e.message}" }
            report("$name → $line")
            delay(700)
        }
        // Same URL through CloudflareKiller, which replays the WebView's own cookies.
        val viaKiller = try {
            val r = app.get(playerUrl, headers = baseHeaders + mapOf("Referer" to onCurrentHost(episodeUrl)),
                timeout = 20L, allowRedirects = false, interceptor = cfKiller)
            "${r.code}${r.headers["Location"]?.take(60)?.let { " → $it" } ?: ""}"
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "exception ${e.javaClass.simpleName}: ${e.message}" }
        report("player, via CloudflareKiller → $viaKiller")
    }

    private suspend fun resolveViaRedirect(nid: String, episodeUrl: String): String? {
        // v5: no Sec-Fetch-* headers. Only the Referer is needed (verified), and Sec-Fetch-*
        // without Chrome's matching sec-ch-ua client hints is an inconsistent fingerprint —
        // a plausible reason Cloudflare singled these requests out.
        val cookieHeader = webViewCookies()?.let { mapOf("Cookie" to it) } ?: emptyMap()
        val headers = baseHeaders + navHints + mapOf("Referer" to onCurrentHost(episodeUrl)) + cookieHeader
        val playerUrl = "$mainUrl/player/$nid"
        lastProbeTarget = nid to episodeUrl
        // Plain request, deliberately not siteGet(): CloudflareKiller can't help here (see
        // looksCfBlocked), and a block should switch strategy, not be retried.
        var r = browserGet(playerUrl, headers, 8L, allowRedirects = false).also { keepSiteCookies(it) }
        if (r.code == 403 && r.headers["cf-mitigated"]?.contains("challenge", true) == true) {
            // One retry with the hint values the self-test proved work, before giving up on
            // the fast path for 15 minutes.
            r.closeQuietly()
            // Retry once without cookies: a stale __cf_bm from the WebView can itself be the
            // thing being challenged, and the self-test's cookieless variant passed.
            r = browserGet(playerUrl, baseHeaders + navHints + mapOf("Referer" to onCurrentHost(episodeUrl)), 8L, allowRedirects = false)
            if (r.code in 300..399) log("resolve: retry without cookies worked for $nid")
        }
        if (r.code == 403 || r.code == 429 || r.code == 503) {
            logW("resolve: /player/$nid refused (http ${r.code}, server=${r.headers["server"]}, cf-mitigated=${r.headers["cf-mitigated"]}, cf-ray=${r.headers["cf-ray"]}) — switching to WebView for ${playerHttpBlockCooldownMs / 60000} min")
            r.closeQuietly()
            playerHttpBlockedUntil = System.currentTimeMillis() + playerHttpBlockCooldownMs
            try { diagnoseDirectBlock(nid, episodeUrl) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { log("diag: failed: ${e.message}") }
            throw PlayerLookupBlocked(r.code)
        }
        if (r.code in 300..399) {
            val loc = r.headers["Location"]?.trim().orEmpty()
            r.closeQuietly()
            if (loc.isBlank()) return null
            val abs = try { java.net.URI(playerUrl).resolve(loc).toString() } catch (_: Exception) { loc }
            return classifyEmbedUrl(abs)
        }
        if (r.code !in 200..299) {
            if (r.code == 404) logW("site-change warning: /player/$nid returned 404 (Referer gate changed?)")
            else log("resolve: /player/$nid http ${r.code}")
            return null
        }
        // 200 instead of a redirect: the site went back to serving a page. Look for the
        // embed in it before giving up (the old body-scraping logic, kept as a fallback).
        val html = r.text
        val doc = org.jsoup.Jsoup.parse(html, playerUrl)
        doc.selectFirst("iframe[src]")?.attr("abs:src")?.let { classifyEmbedUrl(it) }?.let { return it }
        apRe.find(html)?.let { return classifyEmbedUrl(it.value) }
        gdRe.find(html)?.let { return "gd:${it.groupValues[1]}" }
        return urlRe.findAll(html).map { it.value }
            .firstOrNull { u -> extractorHostKeywords.any { u.contains(it, ignoreCase = true) } }
            ?.let { "ex:$it" }
    }

    // Shared across loadLinks calls on purpose: prefetch + a manual click overlapping
    // shouldn't double the request rate to /player/.
    private val resolvePacer = Mutex()
    @Volatile private var lastResolveStart = 0L
    private suspend fun paceResolve() {
        resolvePacer.withLock {
            val span = (resolveGapMaxMs - resolveGapMinMs).coerceAtLeast(0L)
            val gap = resolveGapMinMs + (Math.random() * (span + 1)).toLong()
            val wait = lastResolveStart + gap - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastResolveStart = System.currentTimeMillis()
        }
    }

    private suspend fun httpResolveEmbeds(
        numIds: List<String>,
        episodeUrl: String,
        onResolved: (numId: String, embed: String) -> Unit,
    ) {
        coroutineScope {
            numIds.forEach { nid ->
                launch {
                    resolveGate.withPermit {
                        // One refusal stops the rest of the batch — don't keep knocking.
                        if (System.currentTimeMillis() < playerHttpBlockedUntil) return@withPermit
                        paceResolve()
                        if (System.currentTimeMillis() < playerHttpBlockedUntil) return@withPermit
                        val embed = try { resolveViaRedirect(nid, episodeUrl) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (_: PlayerLookupBlocked) { null }
                            catch (e: Exception) { log("resolve: $nid failed: ${e.message}"); null }
                        if (embed != null) { log("resolve: $embed for numId=$nid"); onResolved(nid, embed) }
                    }
                }
            }
        }
    }

    // 4.0: demoted to a last-resort fallback — only runs for sources the redirect resolver
    // above couldn't resolve, and only for recognised player names. Kept because it's the
    // one path that would survive the site moving the embed back into client-side JS.
    private suspend fun resolveEmbeds(
        numIds: List<String>,
        episodeUrl: String,
        // Fires the instant each numId resolves. Always on the main thread now (called
        // exclusively from handleDetectedEmbed, itself always reached via handler.post) —
        // before the 1.1 threading fix this fired from whatever thread the JS bridge
        // happened to call back on, which was the actual root cause of a real race.
        onResolved: (numId: String, embed: String) -> Unit = { _, _ -> },
    ): Map<String, String> {
        if (numIds.isEmpty()) return emptyMap()
        val results = mutableMapOf<String, String>()

        return suspendCancellableCoroutine { cont ->
            val handler = android.os.Handler(Looper.getMainLooper())
            handler.post {
                var done = false
                // Library-artifact way to get the Android context (CloudStreamApp/AcraApplication
                // are app classes, not visible at compile time anymore)
                // AcraApplication is deprecated-as-ERROR in the current pre-release stub too
                // (confirmed via a real compile failure, not just a warning) — it's not
                // specific to the library system. CloudStreamApp.context is defined directly
                // in the app module itself (verified in source), so it's available here.
                val ctx = try { com.lagradost.cloudstream3.CloudStreamApp.context } catch (_: Throwable) { null }
                if (ctx == null) { log("resolve: no context"); if (cont.isActive) cont.resume(emptyMap()); return@post }

                val wv = WebView(ctx).apply {
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // Only override if our UA differs from the WebView's own (it won't, when the
                    // system UA was readable — see `ua`). Leaving the native string in place is
                    // what keeps the JS-visible identity and the headers telling the same story.
                    if (settings.userAgentString != ua) settings.userAgentString = ua
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                // Stealth: CookieManager shares cookies between OkHttp/cfKiller and WebView
                // automatically within the same app process — no manual sync needed

                fun finish() {
                    if (!done) {
                        done = true
                        handler.removeCallbacksAndMessages(null) // clear all pending timeouts/delays
                        try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(results)
                    }
                }
                // If the caller's coroutine is cancelled (user leaves screen), tear the
                // WebView down instead of leaking it until the 25s global timeout.
                cont.invokeOnCancellation { handler.post { finish() } }

                val globalTimeout = Runnable { log("resolve: global timeout (${results.size}/${numIds.size})"); finish() }
                handler.postDelayed(globalTimeout, 25_000L)

                val currentTarget = java.util.concurrent.atomic.AtomicReference(""); var currentIdx = -1
                var perIdTimeout: Runnable? = null
                var usedFallback = false
                var pageReady = false
                // Some numIds' pages fire their matching request a second time (retry/
                // prefetch/duplicate redirect) shortly after the first. If that echo
                // arrives after resolveNext() has already moved currentTarget on to the
                // next numId, it gets misattributed there — same hash filed under two
                // numIds, which shows up as the same stream listed twice under different
                // labels (e.g. "Aincrad 1080p" and "GDrive 1080p" both playing the same
                // Aincrad source). Fix: only ever act on a given matched value once.
                val seenEmbeds = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

                fun resolveNext() {
                    perIdTimeout?.let { handler.removeCallbacks(it) }
                    currentIdx++
                    if (currentIdx == 1 && results.isEmpty() && !usedFallback) {
                        log("resolve: first failed, falling back to loadUrl")
                        usedFallback = true; currentIdx = -1; pageReady = false
                        wv.loadUrl(onCurrentHost(episodeUrl))
                        return
                    }
                    if (currentIdx >= numIds.size || done) { handler.removeCallbacks(globalTimeout); finish(); return }
                    currentTarget.set(numIds[currentIdx])
                    val nid = numIds[currentIdx]
                    log("resolve: [${currentIdx}/${numIds.size}] numId=$nid")

                    perIdTimeout = Runnable { if (currentTarget.get() == nid && !done) { log("resolve: timeout $nid"); resolveNext() } }
                    // 1.5s, not 3s: across 89 successful resolves in the user's own log, the
                    // slowest was 0.91s (p90 0.81s). A numId still unresolved past ~1.5s is
                    // dead, not slow — the old 3s just doubled the wasted wait on broken ones.
                    handler.postDelayed(perIdTimeout!!, 1_500L)

                    // Stealth: random delay between iframes. Trimmed from the original
                    // 200-600ms — that alone cost ~20s on a 53-source episode. Lower risk
                    // than it looks: this delay is between *our own sequential requests*,
                    // not a CF challenge-response, so it mainly needs to avoid a suspiciously
                    // uniform/instant firing pattern, not hit a specific human-speed target.
                    // If CF blocks return, widen this back first before touching anything else.
                    val delay = if (currentIdx == 0) 0L else (90L + (Math.random() * 140).toLong())
                    handler.postDelayed({
                        if (done) return@postDelayed
                        wv.evaluateJavascript(
                            "(function(){var o=document.getElementById('_pf');if(o)o.remove();" +
                            "var f=document.createElement('iframe');f.id='_pf';" +
                            "f.style.cssText='width:1px;height:1px;position:absolute;left:-9999px';" +
                            "f.src='/player/$nid';document.body.appendChild(f);})()", null)
                    }, delay)
                }

                // JS bridge removed (was addJavascriptInterface(..., "_b")). Nothing calls
                // into JS and back anymore — shouldInterceptRequest already extracts the
                // embed value on the Kotlin side, so evaluateJavascript("_b.h(...)") was a
                // pointless round-trip: post straight to handleDetectedEmbed() below instead.
                // This is also what fixed a real bug, not just a simplification: the JS
                // bridge callback ran on a WebView-internal thread and mutated
                // currentTarget/results with no synchronization, racing against
                // shouldInterceptRequest's own (different) thread.
                fun handleDetectedEmbed(v: String) {
                    if (done) return
                    val tgt = currentTarget.get()
                    if (v.isNotBlank() && tgt.isNotBlank() && !results.containsKey(tgt)) {
                        log("resolve: $v for numId=$tgt"); results[tgt] = v
                        onResolved(tgt, v)
                    }
                    resolveNext()
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        val host = request.url?.host ?: ""

                        // Fast path: skip regex processing for our own site's sub-resources
                        // (CSS, JS, fonts etc.) — we only care about cross-domain embeds.
                        // host.endsWith(mainHost), not a literal domain string, so this
                        // still works correctly if the site is ever accessed via a mirror
                        // domain (e.g. CloudStream's "clone site" feature).
                        if (host.endsWith(mainHost)) {
                            // Only process /player/ paths
                            if (url.contains("/player/")) {
                                val tgt = currentTarget.get()
                                if (tgt.isBlank() || !url.endsWith("/player/$tgt"))
                                    return emptyResponse()
                            }
                            return null // let all other same-site requests through
                        }

                        // Cross-domain: check for embeds we want to intercept
                        if (host.contains("player")) {
                            apRe.find(url)?.let { m ->
                                val domain = m.groupValues[1]
                                synchronized(playerBaseLock) {
                                    if (!playerBase.contains(domain.substringAfter("://"))) {
                                        playerBase = domain; log("resolve: player domain updated to $domain")
                                    }
                                }
                                noteApDomain(m.groupValues[2], domain)
                                if (seenEmbeds.add(m.groupValues[2]))
                                    handler.post { handleDetectedEmbed("ap:${m.groupValues[2]}") }
                                return emptyResponse()
                            }
                        }
                        if (host.contains("puffytr")) {
                            puffytrRe.find(url)?.let { m ->
                                if (seenEmbeds.add(m.groupValues[1]))
                                    handler.post { handleDetectedEmbed("bp:${m.groupValues[1]}") }
                                return emptyResponse()
                            }
                        }
                        if (extractorHostKeywords.any { host.contains(it) }) {
                            // First cross-domain hit for this numId that matches a known
                            // video host = the embed itself (each /player/nid loads one host)
                            if (seenEmbeds.add(url)) {
                                handler.post { handleDetectedEmbed("ex:$url") }
                            }
                            return emptyResponse()
                        }
                        if (host.contains("google")) {
                            gdRe.find(url)?.let { m ->
                                log("resolve: GDrive: ${m.groupValues[1]}")
                                if (seenEmbeds.add(m.groupValues[1]))
                                    handler.post { handleDetectedEmbed("gd:${m.groupValues[1]}") }
                                return emptyResponse()
                            }
                        }

                        // Block ads/analytics
                        if (host.contains("statbest") || host.contains("adservice") || host.contains("doubleclick"))
                            return emptyResponse()
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        log("resolve: pageFinished url=$url ready=$pageReady fallback=$usedFallback")
                        if (!pageReady) {
                            pageReady = true
                            if (usedFallback && url?.contains(mainHost) == true) {
                                log("resolve: fallback loaded, restarting"); resolveNext()
                            }
                        }
                    }
                }

                log("resolve: starting ${numIds.size} resolves")
                wv.loadDataWithBaseURL(episodeUrl, "<html><body></body></html>", "text/html", "utf-8", null)
                handler.post { resolveNext() }
            }
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    // 4.0: no session fetch, no _token (verified unnecessary). Also handles a real miss:
    // the endpoint is a plain substring match, so "re zero" returns nothing for
    // "Re:Zero kara Hajimeru…" while "Re:Zero" or "zero kara" work. On an empty result we
    // retry once with the longest word and filter locally on all words, punctuation-blind.
    private data class SearchHit(val slug: String, val title: String, val poster: String?, val year: Int?, val haystack: String)
    private val nonAlnumRe = Regex("""[^\p{L}\p{N}]+""")
    private fun squash(s: String) = s.lowercase().replace(nonAlnumRe, "")

    private suspend fun searchApi(q: String): List<SearchHit> {
        val params = mapOf("query" to q, "type" to "detailed", "limit" to "20",
            "priorityField" to "info_title", "orderBy" to "info_year", "orderDirection" to "ASC")
        val text = try { siteText("$mainUrl/searchAnime", xhrHeaders, params = params, timeout = 10L) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null } ?: return emptyList()
        return try {
            val arr = JSONObject(text).optJSONArray("data") ?: JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val slug = obj.optString("info_slug", "").ifBlank { return@mapNotNull null }
                val title = obj.optString("info_title", "").ifBlank { return@mapNotNull null }
                val thumb = obj.optString("info_poster", "")
                val poster = if (thumb.isBlank()) null else if (thumb.startsWith("http")) thumb else "$mainUrl/storage/pcovers/$thumb"
                val year = obj.optString("info_year", "").toIntOrNull()
                val hay = squash(listOf("info_title", "info_titleoriginal", "info_titleenglish", "info_othernames")
                    .joinToString(" ") { obj.optString(it, "") })
                SearchHit(slug, title, poster, year, hay)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().ifBlank { return emptyList() }
        var hits = searchApi(q)
        if (hits.isEmpty()) {
            val words = q.split(nonAlnumRe).filter { it.length >= 2 }
            val longest = words.maxByOrNull { it.length }
            if (words.size > 1 && longest != null && longest.length >= 3) {
                val squashedWords = words.map { squash(it) }
                val broad = searchApi(longest)
                hits = broad.filter { h -> squashedWords.all { h.haystack.contains(it) } }.ifEmpty { broad }
                log("search: '$q' empty, retried with '$longest' → ${hits.size}")
            }
        }
        return hits.map { h ->
            newAnimeSearchResponse(h.title, "$mainUrl/${h.slug}", TvType.Anime) { posterUrl = h.poster; year = h.year }
        }
    }

    // ── Home page ─────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf("anime-izle" to "Son Eklenen Bölümler", "" to "Son Eklenen Animeler")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cacheKey = "$page:${request.data}"
        mainPageCache[cacheKey]?.let { (cached, time) ->
            if (System.currentTimeMillis() - time < mainPageCacheTtlMs) return cached
        }
        val url = if (request.data == "anime-izle") "$mainUrl/anime-izle?sayfa=$page" else "$mainUrl?sayfa=$page"
        val doc = siteDocument(url, pageHeaders, timeout = 12L)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        fun toAbs(src: String): String? {
            if (src.isBlank() || src.startsWith("data:")) return null
            return when { src.startsWith("http") -> src; src.startsWith("//") -> "https:$src"; src.startsWith("/") -> "$mainUrl$src"; else -> "$mainUrl/$src" }
        }
        fun clean(raw: String) = raw.replace(cleanRe, "").trim()
        val items = doc.select("a.imgWrapperLink, div.posterBlock > a, a[href*=-bolum]")
            .ifEmpty { doc.select("a[href*=-izle]").filter { it.selectFirst("img") != null } }
            .distinctBy { it.attr("href") }.mapNotNull { el ->
                val href = el.attr("abs:href").ifBlank { return@mapNotNull null }; val isEp = href.contains("-bolum")
                val img = el.selectFirst("img")
                val poster = img?.let { toAbs(it.attr("src")) ?: toAbs(it.attr("data-src")) ?: toAbs(it.attr("data-original")) }
                    ?: el.selectFirst("div.poster")?.let { mainPosterCommentRe.find(it.html())?.groupValues?.get(1)?.let { s -> toAbs(s) } }
                val title = if (isEp) {
                    el.parent()?.select("a[href]:not([href*=-bolum])")?.firstOrNull { it.attr("abs:href") != href }
                        ?.text()?.trim()?.let { clean(it) }?.ifBlank { null }
                        ?: img?.attr("alt")?.replace(episodeLabelCleanupRe, "")?.let { clean(it) }?.ifBlank { null }
                } else {
                    el.selectFirst("div.title, .truncateText, h4, h5, h3, strong, b")
                        ?.clone()?.also { it.select("span.tag, span.label, .tag, .genres").remove() }
                        ?.text()?.trim()?.let { clean(it) }?.ifBlank { img?.attr("alt")?.let { clean(it) } }?.ifBlank { null }
                } ?: return@mapNotNull null
                val animeUrl = if (isEp) href.replace(episodeUrlCleanupRe, "").trimEnd('-', '/') else href
                newAnimeSearchResponse(title, animeUrl, TvType.Anime) { posterUrl = poster }
            }
        if (page == 1 && items.isEmpty()) logW("site-change warning: main page selectors empty")
        val result = newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
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
    private fun absUrl(src: String): String? {
        if (src.isBlank() || src.startsWith("data:")) return null
        return when {
            src.startsWith("http") -> src; src.startsWith("//") -> "https:$src"
            src.startsWith("/") -> "$mainUrl$src"; else -> "$mainUrl/$src"
        }
    }

    private fun findImg(el: org.jsoup.nodes.Element): String? {
        return absUrl(el.attr("src")) ?: absUrl(el.attr("data-src"))
            ?: absUrl(el.attr("data-original")) ?: absUrl(el.attr("data-lazy-src"))
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = siteDocument(url, pageHeaders, timeout = 12L)
            ?: return newAnimeLoadResponse(name = url.substringAfterLast('/'), url = url, type = TvType.Anime) { addEpisodes(DubStatus.Subbed, emptyList()) }

        val title = doc.selectFirst("h2.anizm_pageTitle, h2.page-title, h1, .anime-title")?.text()?.trim()
            ?: url.substringAfterLast("/").replace("-", " ")

        // Extract alternative names for AniList/MAL sync matching
        val aliases = mutableListOf<String>()
        // Method 1: data rows with labels (Japonca, İngilizce, Diğer Adları, etc.)
        doc.select("span.dataTitle, .info-label, .data-title, dt").forEach { labelEl ->
            val label = labelEl.text().lowercase().trim().trimEnd(':')
            if (label.contains("japonca") || label.contains("japanese") ||
                label.contains("ingilizce") || label.contains("english") ||
                label.contains("diğer ad") || label.contains("other name") ||
                label.contains("romaji") || label.contains("orijinal")) {
                // Get the value — usually the next sibling or next element
                val valueEl = labelEl.nextElementSibling()
                    ?: labelEl.parent()?.selectFirst("span.dataValue, .info-value, dd")
                val value = valueEl?.text()?.trim() ?: ""
                if (value.isNotBlank() && value != title) {
                    // Split on comma — "Name1, Name2" → ["Name1", "Name2"]
                    value.split(",", "،", "/").map { it.trim() }.filter { it.isNotBlank() && it != title }
                        .forEach { aliases.add(it) }
                }
            }
        }
        // Method 2: og:title might have a different name
        doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.replace(ogAnizmCleanupRe, "")
            ?.replace(ogIzleCleanupRe, "")
            ?.replace(ogIzleTruncRe, "")?.trim()
            ?.let { if (it.isNotBlank() && it != title && it !in aliases) aliases.add(it) }
        // Method 3 removed — h1/h2 scan grabs footer/nav junk ("Biz Kimiz?", "Hızlı Erişim" etc.)
        // Clean Turkish "izle/İzle" and truncated forms (anizm truncates long titles to "izl...", "iz...", etc.)
        val cleanedAliases = aliases.map { it.replace(ogIzleCleanupRe, "").replace(ogIzleTruncRe, "").trim() }
            .filter { it.isNotBlank() && it != title && it.length > 3 }
        // Deduplicate and limit
        val nameAliases = cleanedAliases.distinct().take(10).ifEmpty { null }
        log("load: aliases=${nameAliases?.size ?: 0}: ${nameAliases?.joinToString(" | ") ?: "none"}")

        // Poster: try multiple selectors + fallback to og:image
        val poster = sequenceOf(
            doc.selectFirst("div.infoPosterImg img"),
            doc.selectFirst("img[src*=pcovers]"),
            doc.selectFirst("img[data-src*=pcovers]"),
            doc.selectFirst(".posterBlock img, .poster img, .cover img"),
        ).filterNotNull().map { findImg(it) }.firstOrNull { it != null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { absUrl(it) }

        // Plot: try content selectors + fallback to meta description
        val plot = doc.selectFirst("div.infoDesc, .anime-description, .description, .synopsis")
            ?.also { it.select("h1,h2,h3,h4,a,script,style").remove() }?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")?.trim()?.ifBlank { null }

        val year = doc.select("span.dataValue, .info-value, li, td").map { it.text().trim() }
            .firstOrNull { it.matches(yearRe) && it.toInt() in 1950..2040 }?.toIntOrNull()
        // 4.0: fallbacks are now only used when the primary selector finds nothing. They used
        // to be comma-joined into one query, and a[href*=/kategoriler/] also matches the
        // site-wide category menu — so e.g. Re:Zero S4 got tagged "Aksiyon, Arabalar"
        // (Action, Cars) right after its real genres.
        // v5: the genre/theme rows are links, not span.label — v4's selector matched nothing
        // (tags vanished). Read the "Türler" (genres) and "Temalar" (themes) rows directly,
        // the same way the page lays them out: <span class=dataTitle>Türler</span> + links.
        val tagRows = doc.select("span.dataTitle").filter { t ->
            val l = t.text().lowercase()
            l.contains("tür") || l.contains("tema") || l.contains("genre") || l.contains("theme")
        }
        val tags = tagRows.flatMap { it.parent()?.select("a, span.label")?.toList() ?: emptyList() }
            .ifEmpty { doc.select("span.dataValue > span.tag > span.label, span.dataValue a[href*=/kategoriler/]") }
            .map { it.text().trim() }.filter { it.isNotBlank() && it.length < 30 }.distinct().take(10).ifEmpty { null }

        val allLinks = doc.select("div#episodesMiddle a[href]")
            .ifEmpty { doc.select("div.episodeListTabContent a[href]") }
            .ifEmpty { doc.select(".episode-list a[href], a[href*=-bolum]") }
            .distinctBy { it.attr("abs:href") }
        // Preview/teaser entries (e.g. "1-2. Bölüm (Ön Gösterim)") sit before "1. Bölüm" in
        // the list. Naive position-based numbering (i+1) shifts every real episode after it
        // by one — confirmed on a real page. Naive number-parsing doesn't fix this either:
        // "1-2. Bölüm" parses to "2" from either the label or the URL, colliding with the
        // real episode 2. So: detect range-style/preview entries first and give them
        // episode=0 (visible, watchable, never collides with or shifts real numbers);
        // parse real episodes' numbers directly from label or URL instead of trusting
        // position at all, so a missing/reordered entry elsewhere can't shift anything.
        data class EpEntry(val href: String, val label: String, val isSpecial: Boolean)
        // rangeLabelRe/singleEpLabelRe/singleEpUrlRe now hoisted to class level (1.8)
        val entries = allLinks.mapNotNull { el ->
            val href = el.attr("abs:href").ifBlank { return@mapNotNull null }
            val label = el.text().trim().ifBlank { return@mapNotNull null }
            val ll = label.lowercase()
            if (href.contains("fragman") || ll.contains("fragman")) return@mapNotNull null
            if (href.contains("-pv-") || ll == "pv" || ll.endsWith(" pv")) return@mapNotNull null
            val isSpecial = rangeLabelRe.containsMatchIn(label) ||
                ll.contains("önizleme") || ll.contains("ön gösterim") ||
                href.contains("onizleme") || href.contains("on-gosterim")
            EpEntry(href, label, isSpecial)
        }
        var regularCount = 0
        val episodes = entries.map { e ->
            newEpisode(e.href) { name = e.label }.apply {
                episode = if (e.isSpecial) {
                    0
                } else {
                    regularCount++
                    singleEpLabelRe.find(e.label)?.groupValues?.get(1)?.toIntOrNull()
                        ?: singleEpUrlRe.find(e.href)?.groupValues?.get(1)?.toIntOrNull()
                        ?: regularCount // fall back to a count of regular episodes only,
                                        // never raw list position — specials don't skew this
                }
            }
        }
        // Split aliases into eng/jap + synonyms for AniList/MAL sync matching
        val eng = nameAliases?.firstOrNull { asciiOnlyRe.matches(it) }
        val jap = nameAliases?.firstOrNull { !asciiOnlyRe.matches(it) }
        val syns = nameAliases?.filter { it != eng && it != jap }?.ifEmpty { null }
        log("load: '$title' poster=${poster != null} plot=${(plot?.length ?: 0) > 0} eps=${episodes.size} eng=$eng jap=$jap syns=${syns?.size ?: 0}")
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags
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
    private val sourceListLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private suspend fun fetchSourceList(data: String): List<VidInfo>? =
        sourceListLocks.getOrPut(data) { Mutex() }.withLock { fetchSourceListLocked(data) }

    private suspend fun fetchSourceListLocked(data: String): List<VidInfo>? {
        sourceListCache[data]?.let { (list, t) ->
            if (System.currentTimeMillis() - t < sourceListTtlMs) { log("loadLinks: source list cache hit (${list.size})"); return list }
        }
        val epHtml = try {
            siteText(data, pageHeaders) ?: run { log("loadLinks: page error"); return null }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { log("loadLinks: page error: ${e.message}"); return null }
        log("loadLinks: page len=${epHtml.length}")

        // LinkedHashMap: O(1) dedup by URL while preserving on-page order.
        val translators = LinkedHashMap<String, String>()
        trRe1.findAll(epHtml).forEach { m ->
            val u = m.groupValues[1]; if (u.isNotBlank() && u !in translators) translators[u] = m.groupValues[2].ifBlank { "Fansub" }
        }
        if (translators.isEmpty()) trRe2.findAll(epHtml).forEach { m ->
            val u = m.groupValues[2]; if (u.isNotBlank() && u !in translators) translators[u] = m.groupValues[1].ifBlank { "Fansub" }
        }
        // v40: an HTML-parser fallback that doesn't care about attribute order or quoting, so a
        // template tweak on the site doesn't empty every episode.
        if (translators.isEmpty()) try {
            org.jsoup.Jsoup.parse(epHtml, onCurrentHost(data)).select("[translator]").forEach { e ->
                val u = e.absUrl("translator").ifBlank { e.attr("translator") }
                if (u.isNotBlank() && u !in translators) translators[u] = e.attr("data-fansub-name").ifBlank { e.text().trim().ifBlank { "Fansub" } }
            }
            if (translators.isNotEmpty()) logW("site-change warning: translators found only by the HTML parser (attribute layout changed)")
        } catch (_: Exception) {}
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
                            siteText(trUrl, xhrHeaders + mapOf("Referer" to onCurrentHost(data)))
                                ?: run { log("loadLinks: tr error ($fansubName)"); return@withPermit }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { log("loadLinks: tr error ($fansubName): ${e.message}"); return@withPermit }
                        val trHtml = try { JSONObject(trText).optString("data", "") } catch (_: Exception) { "" }
                        if (trHtml.isBlank()) return@withPermit
                        anyTranslatorHadData.set(true)
                        val videos = mutableListOf<Pair<String, String>>()
                        vidRe1.findAll(trHtml).forEach { m -> videos += m.groupValues[1] to m.groupValues[2].ifBlank { "Player" } }
                        if (videos.isEmpty()) vidRe2.findAll(trHtml).forEach { m -> videos += m.groupValues[2] to m.groupValues[1].ifBlank { "Player" } }
                        if (videos.isEmpty()) try {
                            org.jsoup.Jsoup.parse(trHtml).select("[video]").forEach { e ->
                                val u = e.attr("video"); if (u.isNotBlank()) videos += u to e.attr("data-video-name").ifBlank { e.text().trim().ifBlank { "Player" } }
                            }
                            if (videos.isNotEmpty()) logW("site-change warning: players found only by the HTML parser (attribute layout changed)")
                        } catch (_: Exception) {}
                        log("loadLinks: $fansubName: ${videos.map { it.second }}")
                        // 4.0: no name whitelist here anymore. It silently dropped every
                        // player it didn't recognise by label (live example: "LuluStream" on
                        // Frieren ep 1; "Sistenn", "BYSE", "FlyF", "FireStream", "UpBolt",
                        // "Vids.ST" on Re:Zero S4 ep 17). Resolution is now a cheap redirect
                        // read, and what the host actually is gets decided from the URL.
                        perTranslator[idx] = videos.mapNotNull { (videoUrl, videoName) ->
                            numIdRe.find(videoUrl)?.groupValues?.get(1)?.let { VidInfo(it, videoName, fansubName) }
                        }
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

    // v33: one run per episode at a time. CloudStream calls loadLinks twice for the same
    // episode ~0.5s apart (preload + open); the device logs (2026-09-26) show both calls doing
    // everything — two WebView sniffs per Sistenn source, two Drive resolves (each one counting
    // against the file's download quota), two Beta size estimates. The second call now joins
    // the first: it gets every link found so far at once, then the rest as they arrive.
    private class LinkRun {
        val links = ArrayList<ExtractorLink>()
        val subscribers = ArrayList<(ExtractorLink) -> Unit>()
        // null = the run was cancelled before finishing (a joined caller then runs its own)
        val done = kotlinx.coroutines.CompletableDeferred<Boolean?>()
    }
    private val linkRuns = java.util.concurrent.ConcurrentHashMap<String, LinkRun>()

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

    private suspend fun loadLinksWork(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        log("loadLinks: $data")
        trimSniffMaps()
        // v9: tell a real "reload links" apart from the app's own double call.
        // CloudStream calls loadLinks again a second or two after the first (preload, then
        // the actual open) — those should use the caches. A call that arrives much later for
        // the same episode is the user asking again, and answering that from cache is what
        // made "reload links" look like it did nothing. That one re-scrapes everything:
        // episode page, translator lists, every player lookup, and Aincrad's signed URL.
        val callNow = System.currentTimeMillis()
        sniffSpentThisLoad = 0L
        val sinceLast = lastLoadAt[data]?.let { callNow - it } ?: Long.MAX_VALUE
        lastLoadAt[data] = callNow
        if (lastLoadAt.size > 50) lastLoadAt.entries.removeAll { callNow - it.value > reloadWindowMs }
        val forceRefresh = sinceLast in reloadMinGapMs..reloadWindowMs
        if (forceRefresh) {
            log("loadLinks: reload requested (${sinceLast / 1000}s since last) — clearing caches for this episode")
            sourceListCache.remove(data)?.first?.forEach { vi ->
                embedCache.remove(vi.numId)?.embed?.takeIf { it.startsWith("ap:") }?.let { aincradCache.remove(it.removePrefix("ap:")) }
            }
        }
        val listed = fetchSourceList(data) ?: return false
        if (listed.isEmpty()) return false

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
            if (seenLinks.add(key)) callback(link)
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
                    if (processSource(vi, embed, data, trackingCallback, subtitleCallback, defer)) {
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
        suspend fun runWave(wave: List<String>, label: String, onIdDone: (String) -> Unit = {}) {
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
                    launch {
                        try { coroutineScope { for (vi in list) launch { runOne(vi, embed, allowDefer = deferBrowser) } } }
                        finally { onIdDone(id) }
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
                        try { httpResolveEmbeds(uncachedIds, data) { id, embed -> accept(id, embed) } }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { log("loadLinks: http resolve error: ${e.message}") }
                    } else log("loadLinks: direct lookups blocked recently, using WebView")

                    // Fallback: WebView, only for recognised players the redirect path missed.
                    val missing = uncachedIds.filter { id ->
                        !embedMap.containsKey(id) && byNumId[id]?.any { settings.isRecognised(it.name) } == true
                    }
                    if (missing.isNotEmpty()) {
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
                val job = launch { runWave(wave, "wave ${i + 1}/${w1.size}") { id -> pending.remove(id) } }
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
                runWave(wave, "last-resort wave ${i + 1}/${w2.size}")
                if (found.get()) break // last resort: stop at the first thing that plays
            }
            flushDeferred({ found.get() }, "last resort")
        }

        for (vi in listed) if (vi.numId in tried && !embedMap.containsKey(vi.numId)) log("step4: ${vi.fansub}/${vi.name} MISSING")
        log("loadLinks: resolved ${embedMap.size}/${tried.size} tried (${byNumId.size} total), sources ok=${okSources.get()} (+${belowMinSources.get()} below min), found=${found.get()}")
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
    private val adHostKeywords = listOf(
        "aphacicfable", "prahmnatured", "brigadedelegatesandbox", "gigglemagnetismunaired",
        "cacklegrievingtank", "attirecideryeah", "popads", "propeller", "popcash")

    // v21: Google's ad stack (IMA SDK + its creatives). In the v20 log the Sistenn page loaded
    // /api/v1/info and s0.2mdn.net/instream/video/client.js and then never asked for
    // /api/v1/video — it sits waiting on the preroll. Whether it copes better with the ad
    // failing (script error -> content) or with the ad running is not knowable from here, so
    // the sniffer alternates: a failed attempt flips the mode for the next one, a successful
    // one keeps it. Blocked requests get a real 404 so the page's onerror fallback fires,
    // rather than an empty 200 script that leaves `google.ima` undefined and throws later.
    private val imaHostKeywords = listOf(
        "imasdk.googleapis.com", "2mdn.net", "doubleclick.net", "googlesyndication.com",
        "googleadservices.com", "adservice.google", "googletagservices.com")
    // v27: headers the page's own player used for the stream, keyed by master URL.
    private val sniffedHeaders = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    // v29: the exact variant/segment addresses the page's player requested, keyed by master URL.
    private val sniffedVariantUrls = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val sniffedSegmentUrls = java.util.concurrent.ConcurrentHashMap<String, String>()
    // v35: every segment (path only) the page's player fetched, per master.
    private val sniffedSegmentSets = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val variantTagRe = Regex("""index-(f\d+[^/.?]*)""")
    // v35: the page's token refresh URL, per player page (exUrl).
    private val sniffedRefreshUrls = java.util.concurrent.ConcurrentHashMap<String, String>()
    // v33: playlist texts fetched by the sniff page itself (the WebView's network stack), keyed
    // by URL without query. The device log (2026-09-26) had shky.stellarwebconcepts.store answer
    // the page's player in 0.2s but time out on every request from the app's HTTP client, so
    // the local playlist server could never fetch the playlists it was meant to serve.
    private val sniffedPlaylistTexts = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Fetches the master and each variant (with the token) from inside the page and parks the
    // texts in window.__anzPl; __anzPlDone flips when all requests have settled.
    private fun playlistCaptureJs(master: String, query: String) = """
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
    private val droppedCaptureHeaders = setOf("range", "accept-encoding", "host", "connection", "content-length", "if-none-match", "if-modified-since")
    private fun cleanCapturedHeaders(h: Map<String, String>): Map<String, String> =
        h.filterKeys { it.lowercase() !in droppedCaptureHeaders }
    private val muteJs = """
        (function(){
          function m(root, d){ if (!root || d > 6) return; var els; try { els = root.querySelectorAll('video,audio,media-player'); } catch(e){ return; }
            for (var i=0;i<els.length;i++){ try { els[i].muted = true; els[i].volume = 0; } catch(e){} }
            try { var all = root.querySelectorAll('*'); for (var j=0;j<all.length;j++) if (all[j].shadowRoot) m(all[j].shadowRoot, d+1); } catch(e){} }
          m(document, 0);
        })()
    """.trimIndent()
    private val playlistFileRe = Regex("""(master|playlist|index)[^/]*\.(m3u8|txt)$""")
    private fun notFoundResponse() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
        mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(emptyBytes))

    /** evaluateJavascript hands back a JSON value; unwrap a string result properly (escaped quotes, ampersands). */
    private fun jsResult(r: String?): String {
        if (r.isNullOrEmpty() || r == "null") return ""
        return try { JSONArray("[$r]").optString(0, "") } catch (_: Exception) { r.trim('"') }
    }

    /** CloudStream's current Activity, if it exposes one. Reflection so a rename can't break the build. */
    private fun currentActivity(): android.app.Activity? = try {
        val c = Class.forName("com.lagradost.cloudstream3.CommonActivity")
        val inst = try { c.getField("INSTANCE").get(null) } catch (_: Throwable) { null }
        (c.getMethod("getActivity").invoke(inst) as? android.app.Activity)
            ?.takeIf { !it.isFinishing && !it.isDestroyed }
    } catch (_: Throwable) { null }

    // Runs as the page starts. A WebView that is not on screen reports document.hidden = true,
    // and players (and the IMA SDK) hold off loading anything for a hidden tab.
    private val docStartJs = """
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
    private val stateJs = """
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
    private val decoyPlaylistNames = listOf("preload", "blank", "dummy", "placeholder", "empty", "init")

    // One sniff at a time. Each one is a full page load with JavaScript; five at once (the
    // step4 limit) would mean five live WebViews with ad scripts running — the kind of thing
    // that makes a TV box stutter or get killed for memory.
    private val sniffGate = Semaphore(1)
    private val localHls = LocalHlsServer({ log(it) }, { u, h -> try { runBlocking { browserGet(u, h, 10L) } } catch (_: Exception) { null } })
    // Sniffed playlists are reused for 20 min: the URL carries its own ?v= stamp and the page
    // load is by far the most expensive thing this extension does.
    private val sniffCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    // v19: 20 min -> 5. These URLs carry a short-lived token (?v=…); a reused one still
    // resolves to a link in the list but can be dead by the time the user presses play.
    private val sniffCacheTtlMs = 5 * 60 * 1000L

    // v18: the click script walks shadow roots. Vidstack (the player Sistenn uses) puts its
    // play button inside a shadow DOM, so the old plain querySelector never found it — and
    // players that use load="visible" only start hls.js once the element is on screen, which
    // in a zero-sized offscreen WebView never happens. Hence startLoading() below and the
    // explicit layout() in the sniffer.
    private val playClickJs = """
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
    private val sniffHostFails = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val sniffHostBlockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val sniffHostCooldownMs = 15 * 60 * 1000L
    // Whole-episode cap. Three Sistenn entries on one episode must not turn into three full
    // budgets of waiting before the link list appears.
    @Volatile private var sniffSpentThisLoad = 0L
    private val sniffBudgetPerLoadMs = 45_000L

    private fun sniffHostOnCooldown(url: String): Boolean {
        val host = try { java.net.URI(url).host ?: return false } catch (_: Exception) { return false }
        val until = sniffHostBlockedUntil[host] ?: return false
        return System.currentTimeMillis() < until
    }

    /** v33: the page itself is down (5xx / DNS): no point loading it again for a while. */
    private fun parkSniffHost(url: String, why: String) {
        val host = try { java.net.URI(url).host ?: return } catch (_: Exception) { return }
        sniffHostFails[host] = 2
        sniffHostBlockedUntil[host] = System.currentTimeMillis() + sniffHostCooldownMs
        log("sniff: $host is down ($why) — parked for ${sniffHostCooldownMs / 60000} min")
    }

    private fun noteSniffResult(url: String, ok: Boolean) {
        val host = try { java.net.URI(url).host ?: return } catch (_: Exception) { return }
        if (ok) { sniffHostFails.remove(host); sniffHostBlockedUntil.remove(host); return }
        val fails = (sniffHostFails[host] ?: 0) + 1
        sniffHostFails[host] = fails
        if (fails >= 2) {
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
    private val sslBadHosts = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val brokenTrackerHosts = listOf("mc.yandex.", "hdrc.yandex.net", "mdd.yandex.net", "an.yandex.")
    private val charsetRe = Regex("""charset=([^;\s]+)""", RegexOption.IGNORE_CASE)

    private fun sslErrorName(e: SslError?): String = when (e?.primaryError) {
        SslError.SSL_UNTRUSTED -> "untrusted CA"
        SslError.SSL_EXPIRED -> "expired"
        SslError.SSL_IDMISMATCH -> "hostname mismatch"
        SslError.SSL_NOTYETVALID -> "not yet valid"
        SslError.SSL_DATE_INVALID -> "date invalid"
        SslError.SSL_INVALID -> "invalid"
        else -> "error ${e?.primaryError}"
    }

    /**
     * Fetch one sniffer GET through the app's HTTP client (runs on the WebView's IO thread,
     * so blocking is fine). Returns null — i.e. "let the WebView try itself" — on any failure.
     */
    private fun proxyViaApp(req: WebResourceRequest, note: (String) -> Unit): WebResourceResponse? {
        val url = req.url?.toString() ?: return null
        val host = req.url?.host ?: ""
        return try {
            val h = HashMap<String, String>(req.requestHeaders ?: emptyMap())
            h["User-Agent"] = ua
            cookiesFor(url)?.let { h["Cookie"] = it }
            val r = runBlocking { app.get(url, headers = h, timeout = 10L) }
            val code = r.code
            // The app client follows redirects; a 3xx here or a 1xx can't be expressed in a
            // WebResourceResponse, so hand those back to the WebView.
            if (code < 200 || code in 300..399) { note("sniff: via app $host -> http $code, giving it back"); return null }
            val ct = r.headers["Content-Type"] ?: "application/octet-stream"
            val mime = ct.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
            val charset = charsetRe.find(ct)?.groupValues?.get(1)
            val bytes = bodyBytes(r) ?: ByteArray(0)
            val headers = LinkedHashMap<String, String>()
            for (name in r.okhttpResponse.headers.names()) {
                // Already decoded by the client; a stale length/encoding would corrupt the body.
                if (name.equals("content-encoding", true) || name.equals("content-length", true) ||
                    name.equals("transfer-encoding", true)) continue
                if (name.equals("set-cookie", true)) {
                    r.okhttpResponse.headers.values(name).forEach { c ->
                        try { CookieManager.getInstance().setCookie(url, c) } catch (_: Throwable) {}
                    }
                    continue
                }
                headers[name] = r.okhttpResponse.headers.values(name).joinToString(", ")
            }
            note("sniff: via app $host${req.url?.path?.take(40) ?: ""} -> http $code, ${bytes.size}B $mime")
            val reason = r.okhttpResponse.message.ifBlank { if (code < 400) "OK" else "Error" }
            WebResourceResponse(mime, charset, code, reason, headers, ByteArrayInputStream(bytes))
        } catch (e: Throwable) {
            // If the app's client fails too, that says the host itself is broken (not just
            // the WebView's view of it) — which is worth knowing for the next step.
            note("sniff: via app $host failed too: ${e.javaClass.simpleName}: ${e.message?.take(90)}")
            null
        }
    }

    // v40: the per-stream maps the sniffer fills were never emptied — a small leak that grows
    // with every episode in a long session. They only matter for minutes after a sniff.
    private fun trimSniffMaps() {
        for (m in listOf<MutableMap<String, *>>(sniffedHeaders, sniffedVariantUrls, sniffedSegmentUrls, sniffedSegmentSets, sniffedRefreshUrls))
            if (m.size > 60) m.clear()
        val now = System.currentTimeMillis()
        for (m in listOf(sistennApiOffUntil, driveStreamQuotaUntil, gdriveQuotaUntil)) m.entries.removeAll { it.value < now }
    }

    private suspend fun sniffHlsViaWebView(pageUrl: String, referer: String, budgetMs: Long = 24_000L): String? =
        suspendCancellableCoroutine { cont ->
            val handler = android.os.Handler(Looper.getMainLooper())
            handler.post {
                val ctx = try { com.lagradost.cloudstream3.CloudStreamApp.context } catch (_: Throwable) { null }
                if (ctx == null) { log("sniff: no context"); if (cont.isActive) cont.resume(null); return@post }
                var done = false
                // Anything that could plausibly be the stream request, kept so a timeout says
                // *why* it failed instead of just "timeout".
                val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
                val decoysSeen = java.util.concurrent.atomic.AtomicInteger(0)
                // v26: always block the ad stack. The v25 log settled it: the ads-blocked run
                // (with real taps) got the stream, the ads-allowed run never did. Alternating
                // wasted every other attempt.
                val blockAds = true
                val adsBlocked = java.util.concurrent.atomic.AtomicInteger(0)
                // v22: capped diagnostic lines per sniff (console errors, failed requests,
                // TLS refusals), so the next log says what the page tripped over.
                val diagLines = java.util.concurrent.atomic.AtomicInteger(0)
                val infoLogged = java.util.concurrent.atomic.AtomicBoolean(false) // page asked /api/v1/info (Sistenn family)
                val videoAsked = java.util.concurrent.atomic.AtomicBoolean(false)
                fun diag(msg: String) { if (diagLines.incrementAndGet() <= 30) log(msg) }
                fun isAdHost(h: String) = adHostKeywords.any { h.contains(it, true) } || imaHostKeywords.any { h.contains(it, true) }
                var reloadedForTls = false
                var syntheticTouch = false
                // v25: set below; lets a blocked pop-under schedule a follow-up tap.
                var tapAgain: (() -> Unit)? = null
                val pageHost = try { java.net.URI(pageUrl).host ?: "" } catch (_: Exception) { "" }
                val navsBlocked = java.util.concurrent.atomic.AtomicInteger(0)
                // v27: the master playlist, once seen. The page is then left running until its
                // player asks for a variant playlist, so we can copy exactly what it sent.
                val masterRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
                // v29: the first variant the player opened, then wait for its first segment.
                val variantRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val act = currentActivity()
                val wv = WebView(act ?: ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Ads blocked: nothing but our own muted play() can make sound, so allow it.
                    // Ads allowed: keep the gesture rule so a preroll can't blare through the
                    // user's speakers; the player still fetches its source without playing.
                    settings.mediaPlaybackRequiresUserGesture = !blockAds
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    if (settings.userAgentString != ua) settings.userAgentString = ua
                    // v25: no automatic window.open (pop-unders); with multiple windows off,
                    // a gesture-driven one would load here and is caught by shouldOverrideUrlLoading.
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                }
                // Give the offscreen WebView a real viewport. Without this it is 0x0, so
                // IntersectionObserver never fires and a lazy player never loads its source.
                try {
                    wv.measure(
                        // v35: 1920x1080, not 1280x720. The page reports its size to /api/v1/video
                        // (w=,h=) and its player caps quality at the element size, so at 720 it never
                        // tried 1080p — which the variant filter would then read as "1080p broken".
                        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                    wv.layout(0, 0, 1920, 1080)
                } catch (_: Throwable) {}
                // v21: actually attach it to the window. A detached WebView never gets vsync, so
                // requestAnimationFrame never fires and timers are throttled — enough to stall a
                // player (and the IMA SDK) forever, which matches the v20 log exactly. It goes in
                // behind the app's own UI, nearly transparent, and can't take touches or focus.
                var attachedTo: android.view.ViewGroup? = null
                try {
                    val content = act?.findViewById<android.view.ViewGroup>(android.R.id.content)
                    if (content != null) {
                        wv.alpha = 0.01f
                        wv.isFocusable = false
                        wv.isFocusableInTouchMode = false
                        wv.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        wv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        // v24: our own synthetic taps (see realTap) pass; real touches don't.
                        wv.setOnTouchListener { _, _ -> !syntheticTouch }
                        content.addView(wv, 0, android.widget.FrameLayout.LayoutParams(1920, 1080))
                        attachedTo = content
                    }
                } catch (e: Throwable) { log("sniff: could not attach webview: ${e.message}") }
                log("sniff: mode ads=${if (blockAds) "blocked" else "allowed"}, ${if (attachedTo != null) "attached" else "detached"}")
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                fun finish(result: String?) {
                    if (done) return
                    done = true
                    handler.removeCallbacksAndMessages(null)
                    try { attachedTo?.removeView(wv) } catch (_: Throwable) {}
                    try { wv.stopLoading(); wv.loadUrl("about:blank"); wv.destroy() } catch (_: Exception) {}
                    // Alternate the ad mode after a failure; keep whatever worked.
                    // v26: no alternating any more (see blockAds).
                    if (cont.isActive) cont.resume(result)
                }
                // v33: before closing the page, have it fetch the master and variant playlists
                // itself (see sniffedPlaylistTexts). At most ~3.6s; finishes with m either way.
                var capturing = false
                fun finishCapturing(m: String) {
                    if (done || capturing) return
                    capturing = true
                    val q = (variantRef.get() ?: m).substringAfter('?', "")
                    try { wv.evaluateJavascript(playlistCaptureJs(m, q), null) } catch (_: Throwable) { finish(m); return }
                    var polls = 0
                    fun store(json: String, partial: Boolean) {
                        try {
                            val o = JSONObject(json)
                            if (sniffedPlaylistTexts.size > 40) sniffedPlaylistTexts.clear()
                            for (k in o.keys()) sniffedPlaylistTexts[k] = o.getString(k)
                            log("sniff: page fetched ${o.length()} playlist(s) itself${if (partial) " (the rest did not answer in time)" else ""}: " +
                                o.keys().asSequence().joinToString { it.substringAfterLast('/') })
                        } catch (e: Exception) { log("sniff: playlist capture unreadable: ${e.message}") }
                    }
                    fun poll() {
                        if (done) return
                        // v34: on the last poll take whatever arrived. v33 waited for ALL playlists,
                        // and one stalled 1080p variant meant nothing was kept, not even the 720p.
                        val last = ++polls >= 12
                        val js = if (last) "JSON.stringify(window.__anzPl || {})" else "window.__anzPlDone ? JSON.stringify(window.__anzPl) : ''"
                        try {
                            wv.evaluateJavascript(js) { r ->
                                val json = jsResult(r)
                                if (json.isNotEmpty()) { store(json, partial = last); finish(m) }
                                else if (last) { log("sniff: page fetched no playlists in time"); finish(m) }
                                else handler.postDelayed({ poll() }, 300L)
                            }
                        } catch (_: Throwable) { finish(m) }
                    }
                    handler.postDelayed({ poll() }, 300L)
                }
                cont.invokeOnCancellation { handler.post { finish(null) } }
                handler.postDelayed({
                    val tail = synchronized(seen) { seen.takeLast(8).joinToString(" | ") }
                    log("sniff: timeout for ${pageUrl.substringBefore('#')}; saw ${seen.size} candidate requests${if (tail.isEmpty()) "" else ": $tail"}" +
                        (if (adsBlocked.get() > 0) "; blocked ${adsBlocked.get()} ad requests" else "") +
                        (if (navsBlocked.get() > 0) "; blocked ${navsBlocked.get()} pop-under(s)" else ""))
                    // One last look at the page before it is torn down (capped at 1.5s).
                    handler.postDelayed({ finish(null) }, 1_500L)
                    try {
                        wv.evaluateJavascript(stateJs) { r ->
                            if (!done) log("sniff: page state: ${jsResult(r)}")
                            finish(null)
                        }
                    } catch (_: Throwable) { finish(null) }
                }, budgetMs)

                fun poke(view: WebView?, tag: String) {
                    if (done) return
                    try {
                        view?.evaluateJavascript(playClickJs) { r ->
                            val v = jsResult(r)
                            if (done) return@evaluateJavascript
                            if (v.startsWith("src:")) {
                                val src = v.removePrefix("src:")
                                val file = src.lowercase().substringBefore('?').substringAfterLast('/')
                                if (src.startsWith("http") && decoyPlaylistNames.none { file.startsWith(it) } && masterRef.get() == null) {
                                    log("sniff: player holds ${src.substringBefore('?').takeLast(60)}")
                                    finish(src)
                                    return@evaluateJavascript
                                }
                            }
                            if (v.isNotEmpty() && v != "nothing") log("sniff: $tag -> $v")
                        }
                    } catch (_: Exception) {}
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        val host = request.url?.host ?: ""
                        if (adHostKeywords.any { host.contains(it, ignoreCase = true) }) return emptyResponse()
                        // v24: Yandex Metrica. Its hosts fail TLS on this network for the app's
                        // client too (v22 log), so answer at once instead of a doomed handshake.
                        if (brokenTrackerHosts.any { host.contains(it, ignoreCase = true) }) return notFoundResponse()
                        if (blockAds && imaHostKeywords.any { host.contains(it, ignoreCase = true) }) {
                            adsBlocked.incrementAndGet()
                            return notFoundResponse()
                        }
                        // The master playlist, whatever path it lives under. Some of these
                        // players serve it as .txt or with no extension at all.
                        val u = url.lowercase()
                        // v27: after the master, the first other request to the stream's host
                        // (variant playlist, whatever it is called — or a segment) is what the
                        // player sends for media. Its headers are the ones the link needs.
                        val m0 = masterRef.get()
                        val pathLc = (request.url?.path ?: "").lowercase()
                        // v35: when the stream is served from the page's own host (Sistenn "hlsmod"), that
                        // host also carries the page's API calls, icons and thumbnails — the v34 log took a
                        // PNG for the variant and /api/v1/player for a segment. Those are not media.
                        val notMedia = pathLc.contains("/api/") || pathLc.contains("/cdn-cgi/") || pathLc.endsWith(".ico") ||
                            pathLc.contains("thumbnail") || pathLc.endsWith(".js") || pathLc.endsWith(".css") || pathLc.endsWith(".html") ||
                            pathLc == "/" || pathLc.endsWith("/anizm.net")
                        if (m0 != null && url != m0 && !notMedia && host.isNotEmpty() && host == (try { java.net.URI(m0).host } catch (_: Exception) { null })) {
                            val kept = cleanCapturedHeaders(request.requestHeaders ?: emptyMap())
                            val looksPlaylist = url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".txt") }
                            if (!looksPlaylist) sniffedSegmentSets.getOrPut(m0) { java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>()) }
                                .let { if (it.size < 40) it.add(url.substringBefore('?')) }
                            // v29: v28's log had every header set refused on the variant playlist
                            // 200ms after the page's player opened the same one, while the master
                            // from the same host was fine. Headers aren't it, so log the FULL
                            // addresses (v27/v28 cut them at '?') — a token in the query is the
                            // likely difference — and wait for the first segment too.
                            if (looksPlaylist && variantRef.compareAndSet(null, url)) {
                                sniffedHeaders[m0] = kept
                                sniffedVariantUrls[m0] = url
                                log("sniff: player opened variant ${url.take(260)}")
                                log("sniff:   with headers: " + kept.entries.joinToString("; ") { "${it.key}=${it.value.take(60)}" })
                                handler.postDelayed({ if (!done) { log("sniff: no segment request within 4s"); finishCapturing(m0) } }, 4_000L)
                            } else if (!looksPlaylist) {
                                if (sniffedSegmentUrls.put(m0, url) == null) {
                                    log("sniff: player fetched segment ${url.take(260)}")
                                    if (kept != sniffedHeaders[m0]) log("sniff:   segment headers: " + kept.entries.joinToString("; ") { "${it.key}=${it.value.take(60)}" })
                                    if (sniffedHeaders[m0] == null || variantRef.get() == null) sniffedHeaders[m0] = kept
                                    // v35: a second more lets the player load its other playlists and a few
                                    // more segments, which is what the variant filter below goes by.
                                    handler.postDelayed({ finishCapturing(m0) }, 1_000L)
                                }
                            } else log("sniff: player opened another playlist ${url.take(200)}")
                            return null
                        }
                        // v26: the v25 log's real stream was …/v4/nc9/<id>/cf-master.1789396075.txt,
                        // which none of the old patterns matched — the player played it while the
                        // sniffer waited for its timeout. Match any master/playlist/index file
                        // ending .m3u8 or .txt, whatever prefix or stamp it carries.
                        val isPlaylist = u.contains(".m3u8") || u.contains("/master.") || u.contains("playlist.txt") ||
                            playlistFileRe.containsMatchIn(u.substringBefore('?').substringAfterLast('/')) ||
                            (u.contains("/hlsmod/") && u.contains("/tt/"))
                        if (isPlaylist) {
                            // v20: Sistenn's player attaches a placeholder source (…/preload.m3u8 on
                            // the page's own host) the moment it initialises, and only asks the API
                            // for the real stream afterwards. v18 took that first hit and killed the
                            // page 1.6s in, so the link in the list was a 404. Let the decoy through
                            // untouched (blocking it makes the player give up) and keep listening.
                            val file = u.substringBefore('?').substringAfterLast('/')
                            val decoy = decoyPlaylistNames.any { file.startsWith(it) }
                            if (decoy) {
                                if (decoysSeen.incrementAndGet() == 1) log("sniff: ignoring placeholder $file, waiting for the real one")
                                return null
                            }
                            // v27: the v26 log fetched the master fine but every variant came back
                            // 403 with our headers. So don't stop at the master: let the page's own
                            // player load it and then ask for one variant, record the headers it
                            // used, and give those to the link. At most 5s more.
                            val master = masterRef.get()
                            val reqHeaders = request.requestHeaders ?: emptyMap()
                            if (master == null) {
                                masterRef.set(url)
                                log("sniff: found ${url.take(260)} — waiting for the player to open a variant")
                                sniffedHeaders[url] = cleanCapturedHeaders(reqHeaders)
                                handler.postDelayed({ if (!done) { log("sniff: no variant request within 5s, using master headers"); finishCapturing(url) } }, 5_000L)
                                return null
                            }
                            if (url != master) {
                                val kept = cleanCapturedHeaders(reqHeaders)
                                sniffedHeaders[master] = kept
                                log("sniff: player opened variant ${url.substringBefore('?').takeLast(50)} with headers: " +
                                    kept.entries.joinToString("; ") { "${it.key}=${it.value.take(60)}" })
                                handler.post { finishCapturing(master) }
                            }
                            return null
                        }
                        // Media segments mean we missed the playlist — nothing useful to take.
                        if (u.contains(".ts?") || u.endsWith(".ts")) return emptyResponse()
                        // Diagnostics only: the handful of requests that could have been it.
                        if (u.contains("/api/") || u.contains("video") || u.contains(".mp4") || u.contains("stream"))
                            if (seen.size < 40) seen.add(url.substringBefore('?').takeLast(70))
                        // v34: the v24 diagnostic that re-fetched /api/v1/info through the app's client
                        // is gone (one extra request per sniff, and its answer is encrypted anyway).
                        // The flags drive the early give-up in onPageFinished.
                        if (u.contains("/api/v1/info")) infoLogged.set(true)
                        if (u.contains("/api/v1/video")) { videoAsked.set(true); log("sniff: page asked for the video: ${url.substringBefore('?').takeLast(60)}") }
                        // v35: the page's token refresh (window.__refreshPlayToken → /api/v1/player?t=…, every
                        // ~10s; answers {k, kx=now+1800}). Its t stays valid for hours without cookies, so
                        // the local server can renew the stream token itself mid-playback.
                        if (u.contains("/api/v1/player?t=") && sniffedRefreshUrls.put(pageUrl, url) == null)
                            log("sniff: captured the token refresh call (${url.substringBefore('?').takeLast(40)}, t=${url.substringAfter("t=").length} chars)")
                        // v22: a host the WebView refused on TLS goes through the app's client.
                        if (host in sslBadHosts && request.method.equals("GET", true))
                            return proxyViaApp(request) { diag(it) }
                        return null
                    }

                    // v25: the v24 log showed the first real tap replacing the player page with
                    // another one (viewport 465 -> 980 wide, no media-player): an invisible ad
                    // layer that opens a pop-under on the first click. In a browser that goes to
                    // a new tab and the next click reaches the player; here it navigated the only
                    // window away. Keep the main frame on the player's own site, then tap again.
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        if (request?.isForMainFrame != true) return false
                        val target = request.url?.host ?: return false
                        if (pageHost.isEmpty() || target.equals(pageHost, true) || target.endsWith(".$pageHost", true)) return false
                        val n = navsBlocked.incrementAndGet()
                        log("sniff: blocked navigation to $target (pop-under #$n)")
                        if (n <= 4) handler.postDelayed({ tapAgain?.invoke() }, 800L)
                        return true
                    }

                    // v22: default behaviour is to cancel silently. Still cancel (never proceed
                    // on a bad certificate), but log it, remember the host, and reload once so
                    // the page's requests to it can go through the app's client instead.
                    override fun onReceivedSslError(view: WebView?, h: SslErrorHandler?, error: SslError?) {
                        try { h?.cancel() } catch (_: Throwable) {}
                        val badHost = try { android.net.Uri.parse(error?.url ?: "").host } catch (_: Throwable) { null } ?: return
                        // Ad hosts: nothing to rescue, and not worth routing through the app.
                        if (isAdHost(badHost)) { diag("sniff: TLS refused for ad host $badHost"); return }
                        val isNew = sslBadHosts.add(badHost)
                        diag("sniff: TLS refused for $badHost (${sslErrorName(error)})${if (isNew) " — will route it through the app" else ""}")
                        if (isNew && !reloadedForTls && !done) {
                            reloadedForTls = true
                            handler.postDelayed({
                                if (!done) { log("sniff: reloading page once with $badHost routed through the app"); try { view?.reload() } catch (_: Throwable) {} }
                            }, 300L)
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        val h = request?.url?.host ?: return
                        if (isAdHost(h)) return
                        diag("sniff: request failed ${h}${request.url?.path?.take(50) ?: ""}: ${error?.errorCode} ${error?.description}")
                        // v33: the player page itself did not load (DNS, refused, timeout): stop now.
                        if (request.isForMainFrame && !done && h.equals(pageHost, true)) {
                            parkSniffHost(pageUrl, "${error?.description}")
                            handler.post { finish(null) }
                        }
                    }

                    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        val h = request?.url?.host ?: return
                        if (isAdHost(h)) return
                        diag("sniff: http ${errorResponse?.statusCode} for ${h}${request.url?.path?.take(50) ?: ""}")
                        // v33: the player page itself answered 5xx (rpmvid/strp2p: 530 + Cloudflare
                        // "Error 1016", device logs 2026-09-26). Each of those used to sit out the
                        // whole 24s budget before giving up.
                        val code = errorResponse?.statusCode ?: 0
                        if (request.isForMainFrame && code >= 500 && !done && h.equals(pageHost, true)) {
                            parkSniffHost(pageUrl, "http $code")
                            handler.post { finish(null) }
                        }
                        // v36: the stream's own master playlist answered 5xx (rpmvid hlsmod: 530 on the
                        // master and every segment, device log 2026-09-26) — the page loaded, the stream is
                        // down. Stop now instead of waiting out the budget; park the host only when the
                        // stream lives on the page's own host (a separate stream host rotates).
                        val master = masterRef.get()
                        if (master != null && code >= 500 && !done && request.url?.toString() == master) {
                            if (h.equals(pageHost, true)) parkSniffHost(pageUrl, "stream master http $code")
                            else log("sniff: stream master answered http $code — giving up on this one")
                            handler.post { finish(null) }
                        }
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        try { view?.evaluateJavascript(docStartJs, null) } catch (_: Throwable) {}
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (done) return
                        log("sniff: page ready")
                        poke(view, "poke@ready")
                        // v34: a Sistenn-family page (it loads /api/v1/info) asks /api/v1/video about
                        // 3-4s after load once tapped. strp2p's page loaded, never asked, and sat out
                        // the full 24s budget (device log 2026-09-26). Give such a page 12s.
                        handler.postDelayed({
                            if (!done && infoLogged.get() && !videoAsked.get() && masterRef.get() == null) {
                                log("sniff: page never asked for a video within 12s of loading — giving up")
                                finish(null)
                            }
                        }, 12_000L)
                    }
                }
                // v22: console errors from the page (a thrown exception in the key/decrypt step
                // would otherwise be invisible).
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                        if (m == null) return true
                        val lvl = m.messageLevel()
                        if (lvl == ConsoleMessage.MessageLevel.ERROR || lvl == ConsoleMessage.MessageLevel.WARNING) {
                            val src = try { android.net.Uri.parse(m.sourceId() ?: "").let { "${it.host}${it.path?.takeLast(30) ?: ""}" } } catch (_: Throwable) { "" }
                            diag("sniff: console ${lvl.name.lowercase()} $src:${m.lineNumber()} ${m.message().take(160)}")
                        }
                        return true
                    }
                }
                // v22: log the full URL. The id is in the #fragment and the page needs it; v21
                // trimmed it from this line only, which made the log look like it was dropped.
                log("sniff: loading $pageUrl${if (sslBadHosts.isNotEmpty()) " (via app: ${sslBadHosts.joinToString()})" else ""}")
                wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                // Fixed timeline, not tied to onPageFinished: with ad scripts in the page that
                // callback can arrive after the whole budget has run out.
                // v24: a real tap. JS click() is an untrusted event; if the player only asks
                // for the video on a genuine user gesture, this is what it's waiting for.
                // Only with ads blocked: with them allowed a real tap could start a preroll
                // with sound. Aimed at the centre (vidstack's big play button), then a
                // little off-centre in case the centre is covered.
                fun realTap(tag: String, fx: Float, fy: Float) {
                    if (done || !blockAds || masterRef.get() != null) return
                    try {
                        val x = wv.width * fx; val y = wv.height * fy
                        if (wv.width <= 0 || wv.height <= 0) return
                        val t = android.os.SystemClock.uptimeMillis()
                        val down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                        val up = android.view.MotionEvent.obtain(t, t + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
                        syntheticTouch = true
                        try { wv.dispatchTouchEvent(down); wv.dispatchTouchEvent(up) } finally { syntheticTouch = false }
                        down.recycle(); up.recycle()
                        log("sniff: $tag real tap at ${x.toInt()},${y.toInt()}")
                        // v27: the page now plays for a moment while we wait for a variant;
                        // a real tap counts as a gesture, so make sure it plays silently.
                        try { wv.evaluateJavascript(muteJs, null) } catch (_: Throwable) {}
                        handler.postDelayed({ if (!done) try { wv.evaluateJavascript(muteJs, null) } catch (_: Throwable) {} }, 400L)
                    } catch (e: Throwable) { log("sniff: tap failed: ${e.message}") }
                }
                tapAgain = { realTap("tap after pop-under", 0.5f, 0.5f) }
                handler.postDelayed({ realTap("tap@2s", 0.5f, 0.5f) }, 2_000L)
                handler.postDelayed({ realTap("tap@5s", 0.5f, 0.5f) }, 5_000L)
                handler.postDelayed({ realTap("tap@9s", 0.5f, 0.6f) }, 9_000L)
                handler.postDelayed({ realTap("tap@14s", 0.5f, 0.5f) }, 14_000L)
                for (delay in listOf(1_500L, 3_000L, 5_000L, 7_500L, 10_000L, 13_000L, 17_000L, 21_000L)) {
                    handler.postDelayed({ poke(wv, "poke@${delay / 1000}s") }, delay)
                }
            }
        }

    // One source's worth of step4 work: ex:/ap:/gd: embed -> an actual playable link via
    // callback(). Pulled out of loadLinks so it can be launched concurrently per-source as
    // each numId resolves, instead of running sequentially after every numId is done.
    private suspend fun processSource(
        vi: VidInfo,
        embed: String,
        data: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        // v22: when set, a source that would need a browser sniff is handed back through
        // this instead of sniffed now (loadLinks retries it later, only if still needed).
        deferSniff: ((VidInfo, String) -> Unit)? = null,
    ): Boolean {
        val label = "${vi.fansub} - ${vi.name.replace(adsRe, "").trim()}"
        log("step4: $label embed=$embed")
        // v31: an Aincrad button that no longer lands on anizmplayer (apRe) means the site moved
        // the player; it then silently goes down the generic extractor/sniff path instead.
        if (settings.groupOf(vi.name).key == "aincrad" && !embed.startsWith("ap:"))
            logW("site-change warning: Aincrad source ${vi.fansub}/${vi.name} resolved to $embed, not an anizmplayer /video/ link")

        if (embed.startsWith("ex:")) return processExtractorSource(vi, embed, data, label, callback, subtitleCallback, deferSniff)
        if (embed.startsWith("ap:")) return processAincrad(vi, embed, data, label, callback, subtitleCallback, deferSniff)
        if (embed.startsWith("gd:")) return processGDrive(vi, embed, data, label, callback, subtitleCallback, deferSniff)
        if (embed.startsWith("bp:")) return processBeta(vi, embed, data, label, callback, subtitleCallback, deferSniff)
        return false
    }

    // v38: split out of processSource, which had outgrown ART's compiler limit (17494 instructions,
    // "Method exceeds compiler instruction limit" in the device log) and so always ran interpreted.
    private suspend fun processExtractorSource(
        vi: VidInfo, embed: String, data: String, label: String,
        callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit,
        deferSniff: ((VidInfo, String) -> Unit)?,
    ): Boolean {
        val exUrl = embed.removePrefix("ex:")
        log("step4: $label -> loadExtractor $exUrl")
        var found = false
        try {
            // Collect, then re-emit with the fansub in the name — otherwise these
            // links show only the extractor name ("Voe") with no fansub attribution
            val collected = java.util.concurrent.CopyOnWriteArrayList<ExtractorLink>()
            kotlinx.coroutines.withTimeoutOrNull(15_000) {
                loadExtractor(exUrl, data, subtitleCallback) { collected.add(it) }
            }
            for (l in collected) {
                // Some built-in extractors (StreamLare, Voe, etc.) already bake a
                // quality tag onto the end of their own .name — e.g. "Voe 1080p".
                // Strip it before appending ours below, or it shows as "Voe 1080p 1080p".
                val cleanName = cleanDisplayName(l.name)
                callback(newExtractorLink(source = "${vi.fansub} - $cleanName", name = "${vi.fansub} - $cleanName", url = l.url, type = l.type) {
                    referer = l.referer; quality = l.quality; headers = l.headers; extractorData = l.extractorData })
                found = true
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { log("step4: loadExtractor failed: ${e.message}") }

        // v16: CloudStream had no extractor for this host (or it produced nothing).
        // Load the player page in a hidden WebView and take the playlist it requests.
        // v37: Sistenn-family pages (sistenn.uns.bio, rpmvid, strp2p: https://host/#id) are asked
        // directly through their own API before any WebView is loaded — one to three small
        // requests instead of a full page with ads, taps and a 10-25 s wait.
        if (!found) {
            val direct = try { trySistennApi(exUrl, cleanDisplayName(label), callback) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { log("sistenn-api: error ${e.javaClass.simpleName}: ${e.message?.take(100)}"); SistennResult.UNAVAILABLE }
            when (direct) {
                SistennResult.EMITTED -> return true
                SistennResult.CAPACITY -> return false // the page would hit the same wall
                SistennResult.UNAVAILABLE -> {}
            }
        }
        if (!found && settings.browserSniff) {
            val cached = sniffCache[exUrl]?.takeIf { System.currentTimeMillis() - it.second < sniffCacheTtlMs }?.first
            if (cached != null) log("step4: reusing sniffed playlist for $label")
            if (cached == null && deferSniff != null) {
                log("step4: $label needs the browser — deferred until the cheap sources are done")
                deferSniff(vi, embed)
                return false
            }
            val overBudget = sniffSpentThisLoad >= sniffBudgetPerLoadMs
            val skip = cached == null && (sniffHostOnCooldown(exUrl) || overBudget)
            if (skip) log("step4: skipping sniff for $label (${if (overBudget) "episode sniff budget spent" else "host on cooldown"})")
            val sniffed = cached ?: if (skip) null else try {
                val t0 = System.currentTimeMillis()
                sniffGate.withPermit {
                    // v33: another source (or load) may have sniffed this page while we waited.
                    sniffCache[exUrl]?.takeIf { System.currentTimeMillis() - it.second < sniffCacheTtlMs }?.first
                        ?.also { log("step4: $label was sniffed meanwhile — reusing it") }
                        ?: sniffHlsViaWebView(exUrl, "$mainUrl/")
                }
                    .also {
                        sniffSpentThisLoad += System.currentTimeMillis() - t0
                        noteSniffResult(exUrl, it != null)
                    }
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { log("step4: sniff failed: ${e.message}"); noteSniffResult(exUrl, false); null }
            if (sniffed != null) found = emitSniffed(exUrl, sniffed, label, callback)
        }
        return found
    }

    // v38: split out of processExtractorSource (compiler instruction limit, see processSource).
    private suspend fun emitSniffed(exUrl: String, sniffed: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        sniffCache[exUrl] = sniffed to System.currentTimeMillis()
        if (sniffCache.size > 60) {
            val now = System.currentTimeMillis()
            sniffCache.entries.removeAll { now - it.value.second > sniffCacheTtlMs }
        }
        // v19: the player gets its own HTTP stack, so everything the page had must
        // be spelled out on the link — UA, Referer, Origin, and any cookie the
        // stream host handed the hidden WebView.
        val pageUrl = exUrl.substringBefore('#')
        val origin = try { java.net.URI(pageUrl).let { "${it.scheme}://${it.host}" } } catch (_: Exception) { pageUrl }
        val sniffHeaders = mutableMapOf("User-Agent" to ua, "Referer" to pageUrl, "Origin" to origin).also { h ->
            // v27: whatever the page's own player sent for the stream wins (custom
            // headers included). Cookies aren't in that list, so they're added after.
            sniffedHeaders[sniffed]?.forEach { (k, v) ->
                h.keys.firstOrNull { it.equals(k, true) }?.let { h.remove(it) }
                h[k] = v
            }
            (cookiesFor(sniffed) ?: cookiesFor(pageUrl))?.let { h["Cookie"] = it }
        }.toMap()
        log("sniff: link headers = ${sniffHeaders.keys.joinToString()}${sniffedHeaders[sniffed]?.let { " (copied from the page's player)" } ?: ""}")
        val fetched = try { app.get(sniffed, headers = sniffHeaders, timeout = 6L).text }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { log("step4: sniffed playlist fetch failed: ${e.message}"); "" }
        // v33: the host may ignore this client entirely; the page's own copy is as good.
        val body = if (fetched.trimStart().startsWith("#EXTM3U")) fetched
            else sniffedPlaylistTexts[sniffed.substringBefore('?')]?.also { log("sniff: using the master the page fetched itself") } ?: fetched
        val cleanLabel = cleanDisplayName(label)
        if (body.trimStart().startsWith("#EXTM3U")) {
            var variants = parseVariants(body, sniffed)
            var tokenQuery = ""
            // v29: if the page's player asked for its variant with a query string that
            // the master's own variant lines don't carry, the page's script added it —
            // most likely an access token. Put the same query on every variant.
            sniffedVariantUrls[sniffed]?.let { pv ->
                val q = pv.substringAfter('?', "")
                val sameFile = variants.firstOrNull { it.url.substringBefore('?') == pv.substringBefore('?') }
                log("sniff: master lists ${variants.joinToString { it.url.substringAfterLast('/').take(80) }}; player used ${pv.substringAfterLast('/').take(120)}" +
                    (if (sameFile == null) " (not one of the listed variants)" else ""))
                if (q.isNotEmpty() && variants.none { it.url.contains('?') }) {
                    variants = variants.map { it.copy(url = it.url + "?" + q) }
                    tokenQuery = q
                    log("sniff: copied the player's query onto the variants: ?${q.take(120)}")
                }
            }
            sniffedSegmentUrls[sniffed]?.let { log("sniff: player's first segment was ${it.substringAfterLast('/').take(160)}") }
            log("sniff: master has ${variants.size} variant(s): ${variants.joinToString(", ") { "${it.height}p/${(it.bandwidth ?: 0) / 1000}k" }}")
            // v28: the link's referer used to be exUrl — with its #fragment. CloudStream
            // sends that as the Referer header, which no browser ever does, and the
            // stream host answered the player with 403 (v27 log). Use the player's
            // origin, exactly what the page's own player sent.
            val linkReferer = sniffHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: "$origin/"
            // v28: find a header set the stream host accepts for a variant playlist
            // AND its first segment (the v27 log: segments 403 while playlists passed).
            val secFetch = mapOf("Sec-Fetch-Dest" to "empty", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Site" to "cross-site",
                "Accept-Language" to (try { java.util.Locale.getDefault().toLanguageTag() } catch (_: Throwable) { "en-US" }) + ",en;q=0.8")
            val minimal = mapOf("User-Agent" to ua, "Referer" to linkReferer, "Origin" to origin)
            val candidates = listOf(
                "player + browser fetch headers" to sniffHeaders + secFetch,
                "player" to sniffHeaders,
                "player, no cookies" to sniffHeaders.filterKeys { !it.equals("Cookie", true) } + secFetch,
                "minimal + fetch headers" to minimal + secFetch,
                "minimal" to minimal,
            )
            // v34: token links go through the local playlist server and the player fetches
            // segments itself, so this client's view of the host decides nothing; probing
            // only cost time (8s timeouts in the device logs).
            val picked = if (tokenQuery.isNotEmpty()) null
                else variants.lastOrNull()?.let { pickStreamHeaders(it.url, candidates, tokenQuery) }
            val linkHeaders = picked ?: candidates.first().second
            if (picked == null && tokenQuery.isEmpty()) log("sniff: no header set got a segment from this app's client — listing the links anyway (the player's own network stack may still get through)")
            // v34: if the page could fetch some variants but not others, the missing ones are
            // the ones the host stalls (1080p index-f2 in every log so far): don't list them.
            // v35: "hlsmod" streams (Sistenn serving a TikTok-CDN stream from its own host) disguise
            // every segment as an image; the page's player strips that, ExoPlayer can't
            // (UnrecognizedInputFormat, device log 2026-09-26). Those go through the local
            // server, which fetches each segment and cuts it down to the real media.
            val hlsmod = sniffed.contains("/hlsmod/", ignoreCase = true)
            val useProxy = tokenQuery.isNotEmpty() || hlsmod
            val captured = variants.filter { sniffedPlaylistTexts.containsKey(it.url.substringBefore('?')) }
            if (useProxy && captured.isNotEmpty() && captured.size < variants.size) {
                log("sniff: leaving out ${variants.filter { it !in captured }.joinToString { "${it.height}p" }} — the page could not fetch it")
                variants = captured
            }
            // v35: and only the qualities the page's player actually got segments for. The stream
            // hosts stall 1080p (index-f2) segments for the app as well (v34 log: Cronet
            // SocketTimeoutException) while the page only ever played f1.
            val segsSeen = sniffedSegmentSets[sniffed].orEmpty()
            val played = variants.filter { v -> variantTagRe.find(v.url)?.groupValues?.get(1)?.let { t -> segsSeen.any { it.contains("-$t.") } } == true }
            if (useProxy && played.isNotEmpty() && played.size < variants.size) {
                log("sniff: leaving out ${variants.filter { it !in played }.joinToString { "${it.height}p" }} — the page's player never loaded its segments")
                variants = played
            }
            // v32: segments need the token too. The variant playlists list them relative
            // and without it, so ExoPlayer asked for bare init/seg URLs and got Cloudflare
            // 403s (device log 2026-09-26: 35s of buffering, no error). Serve the player a
            // rewritten copy of each variant from 127.0.0.1 in which every segment is an
            // absolute URL carrying the token; the segments themselves still come
            // straight from the stream host.
            // v35: with the page's refresh call known, segments are served as redirects that carry a
            // token the local server keeps fresh (the one in the playlist expires 30 min after the sniff).
            // v41: the page's own fetch headers for its refresh / playing call.
            fun refreshHeaders(ru: String) = clientHints + mapOf("User-Agent" to ua, "Accept" to "*/*",
                "Referer" to (try { java.net.URI(ru).let { "${it.scheme}://${it.host}/" } } catch (_: Exception) { "$origin/" }),
                "Accept-Language" to acceptLang,
                "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Dest" to "empty")
            val tokenState = if (tokenQuery.isNotEmpty() && !hlsmod) sniffedRefreshUrls[exUrl]?.let { ru ->
                LocalHlsServer.TokenState(tokenQuery, ru, refreshHeaders(ru))
            } else null
            val beat = sniffedRefreshUrls[exUrl]?.let { ru -> LocalHlsServer.Heartbeat(ru, refreshHeaders(ru), tokenState) }
            if (tokenState != null) log("sniff: token will be renewed through the page's refresh call when it nears expiry")
            val proxied = if (useProxy && variants.isNotEmpty())
                variants.mapNotNull { v -> localHls.register(v.url, tokenQuery, linkHeaders, sniffedPlaylistTexts[v.url.substringBefore('?')],
                    stripSegments = hlsmod, token = tokenState, beat = beat)?.let { v to it } } else emptyList()
            if (proxied.isNotEmpty()) log("sniff: serving ${proxied.size} tokenised playlist(s) via ${proxied.first().second.substringBeforeLast('/')}, " +
                "${variants.count { sniffedPlaylistTexts.containsKey(it.url.substringBefore('?')) }} from the page's own fetch")
            found = if (proxied.isNotEmpty()) {
                for ((v, local) in proxied) callback(newExtractorLink(source = cleanLabel, name = "$cleanLabel ${v.height}p",
                    url = local, type = ExtractorLinkType.M3U8) { quality = v.height; referer = linkReferer; headers = linkHeaders })
                true
            } else if (variants.isNotEmpty())
                emitVariants(variants, cleanLabel, linkReferer, linkHeaders, callback, "sniff",
                    "sn:${sniffed.substringBefore('?').takeLast(40)}", allowDeclaredFallback = true,
                    trustPlaylists = picked == null)
            else {
                callback(newExtractorLink(source = cleanLabel, name = cleanLabel, url = sniffed, type = ExtractorLinkType.M3U8) {
                    quality = Qualities.Unknown.value; referer = linkReferer; headers = linkHeaders })
                true
            }
        } else {
            // v20: this used to hand the URL to the player anyway. That is how a dead
            // link ended up in the list — ExoPlayer got a 404 and the episode looked
            // broken. If we cannot read it as a playlist ourselves, neither can the
            // player, so drop it and let the next source have its turn.
            log("sniff: ${sniffed.substringBefore('?').takeLast(50)} is not a playlist (${body.take(40).replace('\n', ' ')}) — discarding")
            sniffCache.remove(exUrl)
        }
        return found
    }

    // v38: split out of processSource, which had outgrown ART's compiler limit (17494 instructions,
    // "Method exceeds compiler instruction limit" in the device log) and so always ran interpreted.
    private suspend fun processAincrad(
        vi: VidInfo, embed: String, data: String, label: String,
        callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit,
        deferSniff: ((VidInfo, String) -> Unit)?,
    ): Boolean {
        val hash = embed.removePrefix("ap:")
        // 4.0: the real player page is /video/{hash} — that's where /player/{numId}
        // redirects, and it's what a browser has as Referer for everything after.
        // The old warm-up hit $playerBase/player/{hash}, which is a 404 on
        // anizmplayer.com (verified), so every Aincrad play started with a request
        // no browser ever makes, and then used that 404 URL as the Referer.
        val apBase = apDomains[hash] ?: playerBase
        val playerRef = "$apBase/video/$hash"
        // v41: what the player page's own $.ajax sends (client hints, Sec-Fetch-*, language).
        val aHeaders = clientHints + mapOf("User-Agent" to ua,
            "X-Requested-With" to "XMLHttpRequest", "Accept" to "*/*",
            "Referer" to playerRef, "Origin" to apBase, "Accept-Language" to acceptLang,
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
        if (cachedSource != null) {
            videoSource = cachedSource.videoSource; securedLink = cachedSource.securedLink
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
                (chromeFetch(gvUrl, gvHeaders, 10L, formBody = gvData) ?: app.post(gvUrl, headers = gvHeaders, data = gvData, timeout = 10))
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
                    cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" })
                if (aincradCache.size > 200) aincradCache.entries.removeAll { it.value.validUntil <= now }
            }
        }
        // Single entry per source, chosen by CONTENT, not by JSON field name — see git
        // history for the 3003 error this avoids. NOTE ON DOWNLOADS: split-audio masters
        // stream fine but CloudStream's downloader can't mux them (app limitation).
        val hlsHeaders = mapOf("User-Agent" to ua, "Origin" to apBase, "Referer" to playerRef) +
            (if (cookieJar.isEmpty()) emptyMap() else mapOf("Cookie" to cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }))

        var resolved = false
        // v32: securedLink first. Until 2026-09 both fields held the same URL; now videoSource
        // is a bare master.txt behind a session check ("security error"), and securedLink is
        // the md5/expires-signed master.m3u8, which carries its own authorisation.
        for (cand in listOf(securedLink, videoSource).filter { it.isNotBlank() }.distinct()) {
            if (resolved) break
            val head = try {
                app.get(cand, headers = hlsHeaders + mapOf("Range" to "bytes=0-4095"), timeout = 8)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { log("aincrad: probe failed: ${e.message}"); continue }
            val t = head.text.trimStart()
            log("aincrad: probe http ${head.code}, ${head.headers["Content-Type"]}, ${t.length}B, starts '${t.take(24).replace('\n', ' ')}'")
            when {
                t.startsWith("#EXTM3U") -> {
                    val cleanLabel = cleanDisplayName(label)
                    val variants = parseVariants(t, cand)
                    if (t.contains("TYPE=AUDIO") || variants.isEmpty()) {
                        // Split audio (every Aincrad sample checked, 2026-09) or an
                        // unparseable master: one master link, ExoPlayer does ABR + audio.
                        // 4.0: this branch never showed a size before. Now it estimates the
                        // top rendition (video + audio) by sampling real segment sizes.
                        val h = variants.maxOfOrNull { it.height }
                            ?: hlsResolutionRe.findAll(t).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
                        val sizeKey = "ap:$hash"
                        val est = if (estimateHlsSizes && variants.isNotEmpty()) {
                            cachedSizes(sizeKey)?.get(0)
                                ?: withTimeoutOrNull(sizeEstimateBudgetMs) { estimateSplitAudioBytes(t, cand, variants, hlsHeaders) }
                                    ?.also { storeSizes(sizeKey, mapOf(0 to it)) }
                        } else null
                        callback(newExtractorLink(source = cleanLabel, name = cleanLabel + formatSize(est, isEstimate = true), url = cand, type = ExtractorLinkType.M3U8) {
                            quality = h ?: Qualities.Unknown.value; referer = playerRef; headers = hlsHeaders })
                        resolved = true
                    } else {
                        resolved = emitVariants(variants, cleanLabel, playerRef, hlsHeaders, callback, "aincrad", "ap:$hash")
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
                        quality = Qualities.Unknown.value; referer = playerRef; headers = hlsHeaders })
                    resolved = true
                }
            }
        }
        return resolved
    }

    // v38: split out of processSource, which had outgrown ART's compiler limit (17494 instructions,
    // "Method exceeds compiler instruction limit" in the device log) and so always ran interpreted.
    private suspend fun processGDrive(
        vi: VidInfo, embed: String, data: String, label: String,
        callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit,
        deferSniff: ((VidInfo, String) -> Unit)?,
    ): Boolean {
        val fileId = embed.removePrefix("gd:")
        log("gdrive: fileId=$fileId for $label")
        // v35: stream first, the way Drive's own embedded player does. The download route below
        // hits the file's download quota ("Quota exceeded") long before the streaming one runs
        // out; the website kept playing files the app could not (2026-09-26).
        val streams = try { gdriveGate.withPermit { resolveGDriveStreams(fileId) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { log("gdrive: stream lookup error: ${e.message}"); emptyList() }
        if (streams.isNotEmpty()) {
            val cleanLabel = cleanDisplayName(label)
            for (st in streams) callback(newExtractorLink(source = cleanLabel, name = "$cleanLabel ${st.height}p" + formatSize(st.sizeBytes),
                url = st.url, type = ExtractorLinkType.VIDEO) {
                quality = st.height; referer = "https://drive.google.com/"; headers = mapOf("User-Agent" to ua) })
            return true
        }
        gdriveQuotaUntil[fileId]?.let { until ->
            if (System.currentTimeMillis() < until) {
                log("gdrive: $fileId hit its download quota recently — not asking Google again for ${(until - System.currentTimeMillis()) / 60000} min")
                return false
            }
        }
        val resolved = try {
            gdriveGate.withPermit { resolveGDrive(fileId) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (e: Exception) { log("gdrive: resolve error: ${e.message}"); null }
        if (resolved != null && !gdriveReusable(resolved, fileId)) return false
        if (resolved != null) {
            val cleanLabel = cleanDisplayName(label)
            val displayName = cleanLabel + formatSize(resolved.sizeBytes)
            callback(newExtractorLink(source = cleanLabel, name = displayName, url = resolved.url, type = ExtractorLinkType.VIDEO) {
                quality = resolved.height ?: Qualities.Unknown.value; referer = "https://drive.google.com/"; headers = resolved.headers })
            return true
        }
        log("gdrive: could not resolve a playable link for $fileId")
        return false
    }

    // v38: split out of processSource, which had outgrown ART's compiler limit (17494 instructions,
    // "Method exceeds compiler instruction limit" in the device log) and so always ran interpreted.
    private suspend fun processBeta(
        vi: VidInfo, embed: String, data: String, label: String,
        callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit,
        deferSniff: ((VidInfo, String) -> Unit)?,
    ): Boolean {
        // Beta Player — puffytr.tr, unrelated to Aincrad. master.txt is a plain,
        // unauthenticated standard HLS master.
        val hash = embed.removePrefix("bp:")
        log("beta: hash=$hash for $label")
        val watchRef = "https://pl.puffytr.tr/watch/$hash"
        val betaHeaders = mapOf("User-Agent" to ua, "Referer" to watchRef)
        val masterUrl = "https://pl.puffytr.tr/stream/$hash/master.txt"
        val body = try {
            app.get(masterUrl, headers = betaHeaders, timeout = 10L).text
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { log("beta: master.txt fetch failed: ${e.message}"); return false }
        if (!body.trimStart().startsWith("#EXTM3U")) { log("beta: master.txt not a valid playlist"); return false }
        val variants = parseVariants(body, masterUrl)
        if (variants.isEmpty()) { log("beta: no parseable variants in master.txt"); return false }
        log("beta: master lists ${variants.joinToString { "${it.height}p ${it.url.substringAfterLast('/').substringBefore('?').take(40)}${if (it.url.contains('?')) "?…" else ""}" }}")
        return emitVariants(variants, cleanDisplayName(label), watchRef, betaHeaders, callback, "beta", "bp:$hash")
    }

    /** v32: a 2xx answer that is actually media (by type, or binary content), not an error text. */
    private fun looksLikeMedia(r: NiceResponse, text: String): Boolean {
        if (r.code !in 200..299 || text.isBlank()) return false
        val ct = (r.headers["Content-Type"] ?: "").lowercase()
        if (ct.startsWith("video/") || ct.startsWith("audio/") || ct.contains("octet-stream") || ct.contains("mp2t")) return true
        if (ct.startsWith("text/") || ct.contains("json") || ct.contains("html") || ct.contains("xml")) return false
        // No usable type: binary content (control bytes in the first 64 chars) counts as media.
        return text.take(64).count { it.code < 9 || (it.code in 14..31) || it == '\uFFFD' } > 4
    }

    // ── Sistenn API, no WebView (v37) ────────────────────────────────────────
    // Worked out from the player's own bundle (index-DqFBtoPY.js, 2026-09-26) and checked against
    // captured responses: /api/v1/info and /api/v1/video answer hex text that is AES-128-CBC with
    // a fixed key and IV the script assembles at runtime ("kiemtienmua911ca" / "1234567890oiuytr";
    // both depend only on location.protocol and the '#' of the hash). /api/v1/player?t= takes the
    // same encryption of a small JSON {website, playing, sessionId, userId, playerId, videoId,
    // country, platform, browser, os} and answers {k, kx}. The video answer lists the stream as
    // cfNative (a Sistenn-served playlist carrying its own ~3.5 h token — made for players that
    // cannot add tokens, i.e. exactly this one), cf / source (the /v4/ hosts, k&kx appended from
    // "pk"), hlsVideoTiktok / hlsVideoGoogle (image-disguised segments), ordered and adjusted by
    // streamingConfig. When the site is full it answers no sources plus delivery.capacityToken.
    enum class SistennResult { EMITTED, CAPACITY, UNAVAILABLE }
    private data class SistennSource(val kind: String, val url: String, val strip: Boolean)
    private val sistennKey = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
    private val sistennIv = "1234567890oiuytr".toByteArray(Charsets.UTF_8)
    private val sistennPageRe = Regex("""^https://([^/#?]+)/?#([A-Za-z0-9]{4,16})$""")
    private val sistennApiOffUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val sistennSessionId = java.util.UUID.randomUUID().toString()

    private fun sistennCipher(mode: Int) = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
        init(mode, javax.crypto.spec.SecretKeySpec(sistennKey, "AES"), javax.crypto.spec.IvParameterSpec(sistennIv))
    }
    private fun sistennDecrypt(hex: String): String? = try {
        val h = hex.trim()
        if (h.length < 32 || h.length % 2 != 0 || !h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) null
        else String(sistennCipher(javax.crypto.Cipher.DECRYPT_MODE).doFinal(ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }), Charsets.UTF_8)
    } catch (_: Exception) { null }
    private fun sistennEncrypt(plain: String): String =
        sistennCipher(javax.crypto.Cipher.ENCRYPT_MODE).doFinal(plain.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Set (replace) query parameters on a URL. */
    private fun withParams(url: String, params: Map<String, String>): String {
        if (params.isEmpty()) return url
        val base = url.substringBefore('?'); val frag = url.substringAfter('#', "")
        val q = LinkedHashMap<String, String>()
        url.substringAfter('?', "").substringBefore('#').split('&').filter { it.isNotEmpty() }.forEach { q[it.substringBefore('=')] = it.substringAfter('=', "") }
        params.forEach { (k, v) -> q[k] = java.net.URLEncoder.encode(v, "UTF-8") }
        return base + "?" + q.entries.joinToString("&") { "${it.key}=${it.value}" } + (if (frag.isNotEmpty()) "#$frag" else "")
    }

    /** The stream candidates, best first: cfNative, then the site's own order (streamingConfig). */
    private fun sistennSources(o: JSONObject, origin: String): List<SistennSource> {
        fun abs(u: String): String? = u.trim().takeIf { it.isNotEmpty() }?.let {
            if (it.startsWith("//")) "https:$it" else try { java.net.URI("$origin/").resolve(it).toString() } catch (_: Exception) { null }
        }
        val cfg = try { JSONObject(o.optString("streamingConfig", "")) } catch (_: Exception) { null }
        val order = cfg?.optJSONArray("order")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            ?: listOf("Tiktok", "Google", "Cloudflare", "In-House")
        val adjust = cfg?.optJSONObject("adjust")
        val raw = mapOf("Cloudflare" to o.optString("cf"), "Tiktok" to o.optString("hlsVideoTiktok"),
            "Google" to o.optString("hlsVideoGoogle"), "In-House" to o.optString("source"))
        val out = ArrayList<SistennSource>()
        // v38: the site's own order, with cfNative (Cloudflare for native players) just ahead of
        // Cloudflare. v37 tried cfNative first; its segments stalled for the app's player (device
        // log 2026-09-26: Cronet SocketTimeoutException for 70 s) while the site's own first choice,
        // Tiktok, is the source that already played in the app.
        val cfNative = abs(o.optString("cfNative"))
        var cfNativeAdded = false
        for (kind in order) {
            if (kind == "Cloudflare" && cfNative != null) { out += SistennSource("cfNative", cfNative, false); cfNativeAdded = true }
            var url = abs(raw[kind] ?: "") ?: continue
            val adj = adjust?.optJSONObject(kind)
            if (adj?.optBoolean("disabled") == true) continue
            adj?.optJSONObject("params")?.let { p -> url = withParams(url, p.keys().asSequence().associateWith { p.optString(it) }) }
            val dom = adj?.optString("domain").orEmpty()
            if (dom.isNotEmpty() && url.contains("/hls/")) url = url.replaceFirst("/hls/", "/hlsmod/$dom/")
            out += SistennSource(kind, url, kind == "Tiktok" || kind == "Google" || url.contains("/hlsmod/"))
        }
        if (cfNative != null && !cfNativeAdded) out += SistennSource("cfNative", cfNative, false)
        return out
    }

    // v41: the phone's screen as window.screen reports it (CSS pixels, current orientation).
    private fun deviceScreenCss(): Pair<Int, Int> = try {
        val dm = com.lagradost.cloudstream3.CloudStreamApp.context!!.resources.displayMetrics
        val w = (dm.widthPixels / dm.density).toInt(); val h = (dm.heightPixels / dm.density).toInt()
        if (w in 200..4000 && h in 200..4000) w to h else 412 to 915
    } catch (_: Throwable) { 412 to 915 }

    /**
     * v41: what a browser fetches before the player's API calls: the page (an iframe opened from
     * anizm) at most every 30 min per host, and its script and stylesheet once per session (a
     * browser caches them). A renamed script means Sistenn shipped a new player, which is when
     * its API key could change, so that is logged.
     */
    private val sistennPageAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val moduleScriptRe = Regex("""<script[^>]+type="module"[^>]+src="([^"]+)"""")
    private val stylesheetRe = Regex("""<link[^>]+rel="stylesheet"[^>]+href="([^"]+)"""")
    private val sistennAssetsSeen = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private suspend fun visitSistennPage(origin: String) {
        val now = System.currentTimeMillis()
        if (now - (sistennPageAt[origin] ?: 0L) < 30 * 60_000L) return
        sistennPageAt[origin] = now
        val page = try {
            browserGet("$origin/", clientHints + mapOf("Upgrade-Insecure-Requests" to "1", "User-Agent" to ua, "Accept" to chromeDocAccept,
                "Sec-Fetch-Site" to "cross-site", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Dest" to "iframe",
                "Referer" to "$mainUrl/", "Accept-Language" to acceptLang), 8L).takeIf { it.code in 200..299 }?.text
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        val html = page ?: return
        val script = moduleScriptRe.find(html)?.groupValues?.get(1)
        val css = stylesheetRe.find(html)?.groupValues?.get(1)
        // v42: remembered per host (rpmvid and strp2p run their own builds); only a change is news.
        if (script != null) {
            val name = script.substringAfterLast('/')
            val host = origin.substringAfter("://")
            val known = settings.playerScript(host) ?: if (host == "sistenn.uns.bio") "index-DqFBtoPY.js" else null
            if (known != null && known != name)
                logW("sistenn-api: $host has a new player script ($name, was $known) — if its links stop, its API key may have changed")
            if (known != name) settings.setPlayerScript(host, name)
        }
        for ((path, dest) in listOf(script to "script", css to "style")) {
            if (path == null || !sistennAssetsSeen.add("$origin$path")) continue
            val u = try { java.net.URI("$origin/").resolve(path).toString() } catch (_: Exception) { continue }
            try {
                browserGet(u, clientHints + mapOf("User-Agent" to ua, "Accept" to (if (dest == "style") "text/css,*/*;q=0.1" else "*/*"),
                    "Origin" to origin, "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Dest" to dest,
                    "Referer" to "$origin/", "Accept-Language" to acceptLang), 15L).closeQuietly()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
        }
    }

    suspend fun trySistennApi(exUrl: String, label: String, callback: (ExtractorLink) -> Unit): SistennResult {
        val m = sistennPageRe.find(exUrl) ?: return SistennResult.UNAVAILABLE
        val host = m.groupValues[1]; val id = m.groupValues[2]
        val now = System.currentTimeMillis()
        if ((sistennApiOffUntil[host] ?: 0L) > now) return SistennResult.UNAVAILABLE
        val origin = "https://$host"
        val apiHeaders = clientHints + mapOf("User-Agent" to ua, "Referer" to "$origin/", "Accept" to "*/*",
            "Accept-Language" to acceptLang,
            "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Dest" to "empty")
        fun off(why: String) { sistennApiOffUntil[host] = System.currentTimeMillis() + 60 * 60_000L; log("sistenn-api: $host not usable ($why) — WebView for 1 h") }
        // v41: a visit starts with the page and its script, as it does in a browser.
        visitSistennPage(origin)
        // The page asks /info first; so do we (it also says early if the id is gone).
        try { browserGet("$origin/api/v1/info?id=$id", apiHeaders, 8L).closeQuietly() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
        // v41: window.screen, as the page reports it (the server logs it beside the OS it reads
        // from the UA). The phone's real screen in CSS pixels; 1920x1080 contradicted the Android
        // UA. The server does not pick qualities by it (a 1280x720 browser got 1080p too); the
        // page's player caps quality to its own size, which this app's player doesn't.
        val (screenW, screenH) = deviceScreenCss()
        var capacity: Pair<String, String>? = null
        var video: JSONObject? = null
        var sources: List<SistennSource> = emptyList()
        // v39: one capacity retry, not two. rpmvid and strp2p stayed full through both (device
        // log 2026-09-26), which cost 9 s per host at the end of the episode's link loading.
        for (attempt in 0 until 2) {
            val capQ = capacity?.let { "&capacityToken=${java.net.URLEncoder.encode(it.first, "UTF-8")}&capacityTokenExpire=${java.net.URLEncoder.encode(it.second, "UTF-8")}" }.orEmpty()
            val r = browserGet("$origin/api/v1/video?id=$id&w=$screenW&h=$screenH&r=$mainHost$capQ", apiHeaders, 10L)
            val body = r.text
            if (r.code !in 200..299) {
                val plainErr = sistennDecrypt(body) ?: body
                log("sistenn-api: video http ${r.code}: ${oneLine(plainErr, 160)}")
                if (r.code in 500..599) off("http ${r.code}")
                return SistennResult.UNAVAILABLE
            }
            val plain = sistennDecrypt(body) ?: run { off("answer not decryptable: ${body.take(40)}"); return SistennResult.UNAVAILABLE }
            val o = try { JSONObject(plain) } catch (_: Exception) { off("answer not JSON"); return SistennResult.UNAVAILABLE }
            sources = sistennSources(o, origin)
            if (sources.isNotEmpty()) { video = o; break }
            val d = o.optJSONObject("delivery") ?: JSONObject()
            val saturated = d.optString("inHouse") == "saturated"
            val capToken = d.optString("capacityToken")
            if (saturated && capToken.isNotEmpty() && attempt < 1) {
                val wait = d.optInt("retryAfter", 3).coerceIn(2, 4)
                log("sistenn-api: $host is at capacity — retrying once in ${wait}s")
                capacity = capToken to d.optString("capacityTokenExpire")
                delay(wait * 1000L)
                continue
            }
            log("sistenn-api: $id has no stream${if (saturated) " — streaming capacity is full" else ""} (keys ${o.keys().asSequence().take(12).joinToString(",")})")
            return if (saturated) SistennResult.CAPACITY else SistennResult.UNAVAILABLE
        }
        val v = video ?: return SistennResult.UNAVAILABLE
        val pk = v.optJSONObject("pk")
        val pkQuery = pk?.optString("k")?.takeIf { it.isNotEmpty() }?.let { "k=$it&kx=${pk.optLong("kx")}" }.orEmpty()
        // Our own refresh call, the way the page builds it.
        val metric = v.optJSONObject("metric")
        // v41: built for every stream (it is also the playing signal), not only for pk tokens.
        val refreshUrl = try {
            "$origin/api/v1/player?t=" + sistennEncrypt(JSONObject().apply {
                put("website", mainHost); put("playing", true); put("sessionId", sistennSessionId)
                for (k in listOf("userId", "playerId", "videoId", "country", "platform", "browser", "os")) put(k, metric?.optString(k) ?: "")
            }.toString())
        } catch (_: Exception) { null }
        log("sistenn-api: ${v.optString("title").take(60)} — sources ${sources.joinToString { it.kind }}")
        val streamHeaders = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin, "Accept" to "*/*")
        // v39: every source is checked at once. One after another, the backup search took 16 s
        // after Tiktok was already found (device log 2026-09-26, build 31), and held up the
        // episode's next wave of sources. The first working source is still awaited in full;
        // a second one only if it is ready within sistennBackupWaitMs of the start.
        val t0 = System.currentTimeMillis()
        var sourcesEmitted = 0
        coroutineScope {
            val checks = sources.map { src -> src to async {
                try { checkSistennSource(src, pkQuery, streamHeaders) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { log("sistenn-api: ${src.kind} check failed: ${e.javaClass.simpleName}"); null }
            } }
            for ((src, job) in checks) {
                if (sourcesEmitted >= 2) break
                val c = if (sourcesEmitted == 0) job.await()
                    else withTimeoutOrNull((t0 + sistennBackupWaitMs - System.currentTimeMillis()).coerceAtLeast(1L)) { job.await() }
                if (c == null) {
                    if (!job.isCompleted) log("sistenn-api: ${src.kind} still checking — not waiting for it as a backup")
                    continue
                }
                val tokenState = if (c.tokenQuery.isNotEmpty() && c.tokenQuery.startsWith("k=${pk?.optString("k")}&") && refreshUrl != null)
                    LocalHlsServer.TokenState(c.tokenQuery, refreshUrl, apiHeaders) else null
                val beat = refreshUrl?.let { LocalHlsServer.Heartbeat(it, apiHeaders, tokenState) }
                // v39: sizes, like Beta and Aincrad. Same episode, same length, so a bigger size
                // means more data per second of video.
                val sizeKey = "sistenn:$host:$id:${src.kind}"
                val sizes = if (estimateHlsSizes) cachedSizes(sizeKey)
                    ?: (withTimeoutOrNull(5_000L) { sistennSizes(src.kind, c, streamHeaders) } ?: emptyMap()).also { storeSizes(sizeKey, it) }
                    else emptyMap()
                var emitted = 0
                // v38: a second working source is listed as an alternative ("· 2"), so the app's
                // automatic next-link fallback has another Sistenn route to try.
                val tag = if (sourcesEmitted == 0) "" else " · ${sourcesEmitted + 1}"
                for ((vv, text) in c.loaded.sortedByDescending { it.first.height }) {
                    val local = localHls.register(vv.url, c.tokenQuery, streamHeaders, text, stripSegments = src.strip, token = tokenState, beat = beat) ?: continue
                    callback(newExtractorLink(source = label,
                        name = (if (vv.height > 0) "$label ${vv.height}p" else label) + tag + formatSize(sizes[vv.height], isEstimate = true),
                        url = local, type = ExtractorLinkType.M3U8) { quality = vv.height; referer = "$origin/"; headers = streamHeaders })
                    emitted++
                }
                if (emitted > 0) {
                    val segHost = try { java.net.URI(c.loaded.first().second.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                        ?.let { java.net.URI(c.loaded.first().first.url).resolve(it).toString() } ?: "").host } catch (_: Exception) { null }
                    log("sistenn-api: ${src.kind} → ${c.loaded.joinToString { "${it.first.height}p" }}${if (c.variantCount > c.loaded.size) " (${c.variantCount - c.loaded.size} variant(s) did not load)" else ""}, segments on ${segHost ?: "?"}, no WebView needed")
                    sourcesEmitted++
                }
            }
            checks.forEach { it.second.cancel() }
        }
        if (sourcesEmitted > 0) return SistennResult.EMITTED
        log("sistenn-api: no source of $id was readable from here — falling back to the WebView")
        return SistennResult.UNAVAILABLE
    }

    private val sistennBackupWaitMs = 9_000L
    private val pkParamRe = Regex("""[?&]k=""")
    private val tokenQueryRe = Regex("""(?:^|&)(k=[^&]+&kx=\d+)""")
    private class SistennChecked(val tokenQuery: String, val variantCount: Int, val loaded: List<Pair<Variant, String>>)

    /** One Sistenn source: its master, and the variants whose playlist loads and whose first segment answers. */
    private suspend fun checkSistennSource(src: SistennSource, pkQuery: String, streamHeaders: Map<String, String>): SistennChecked? {
        // /v4/ hosts want the token on every request (the page's own rule); cfNative carries its own.
        val needsPk = src.url.contains("/v4/") && !pkParamRe.containsMatchIn(src.url) && pkQuery.isNotEmpty()
        val masterUrl = if (needsPk) src.url + (if (src.url.contains('?')) "&" else "?") + pkQuery else src.url
        val tokenQuery = tokenQueryRe.find(masterUrl.substringAfter('?', ""))?.groupValues?.get(1).orEmpty()
        val master = try { app.get(masterUrl, headers = streamHeaders, timeout = 6L).takeIf { it.code in 200..299 }?.text }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        if (master == null || !master.trimStart().startsWith("#EXTM3U")) { log("sistenn-api: ${src.kind} master not readable, next"); return null }
        val variants = parseVariants(master, masterUrl).map { vv ->
            if (tokenQuery.isNotEmpty() && !vv.url.contains('?')) vv.copy(url = vv.url + "?" + tokenQuery) else vv
        }.ifEmpty { listOf(Variant(Qualities.Unknown.value, null, masterUrl)) } // a media playlist itself
        // Only qualities whose playlist actually loads (the /v4/ hosts stall some 1080p variants).
        val loaded = coroutineScope {
            variants.map { vv -> async {
                val t = if (vv.url == masterUrl) master else try {
                    withTimeoutOrNull(5_000L) { app.get(vv.url, headers = streamHeaders, timeout = 5L).takeIf { it.code in 200..299 }?.text }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                val text = t?.takeIf { it.trimStart().startsWith("#EXTM3U") } ?: return@async null
                // v38: and whose first segment answers. A playlist can load while its segments
                // stall (cfNative, the /v4/ hosts' 1080p), which hangs the player for a minute.
                val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    ?: return@async (vv to text)
                val segUrl = (try { java.net.URI(vv.url).resolve(first).toString() } catch (_: Exception) { first })
                    .let { u -> if (tokenQuery.isNotEmpty() && !u.contains('?')) "$u?$tokenQuery" else u }
                val segOk = try {
                    withTimeoutOrNull(5_000L) {
                        val r = app.get(segUrl, headers = streamHeaders + mapOf("Range" to "bytes=0-0"), timeout = 5L)
                        val ok = r.code == 200 || r.code == 206
                        r.closeQuietly()
                        if (!ok) log("sistenn-api: ${src.kind} ${vv.height}p segment answered http ${r.code}")
                        ok
                    } ?: false.also { log("sistenn-api: ${src.kind} ${vv.height}p segment did not answer in 5 s (${try { java.net.URI(segUrl).host } catch (_: Exception) { "?" }})") }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
                if (segOk) vv to text else null
            } }.mapNotNull { it.await() }
        }
        if (loaded.isEmpty()) { log("sistenn-api: ${src.kind} variants not readable, next"); return null }
        return SistennChecked(tokenQuery, variants.size, loaded)
    }

    /** Measured size per quality of a checked Sistenn source, from the playlists already in hand. */
    private suspend fun sistennSizes(kind: String, c: SistennChecked, headers: Map<String, String>): Map<Int, Long> {
        val out = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        coroutineScope {
            for ((vv, text) in c.loaded) launch {
                val measured = try { sampleSegments(text, vv.url, headers, 8, c.tokenQuery) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                val s = measured ?: return@launch
                out[vv.height] = (s.bytesPerSec * s.durationSec).toLong()
                log("size: sistenn $kind ${vv.height}p ≈ ${out[vv.height]!! / 1_048_576}MB (${(s.bytesPerSec * 8 / 1000).toLong()} kbps measured, ${(s.durationSec / 60).toInt()} min)")
            }
        }
        return out
    }

    // ── HLS helpers (4.0) ────────────────────────────────────────────────────
    private data class Variant(val height: Int, val bandwidth: Long?, val url: String)

    private fun parseVariants(master: String, masterUrl: String): List<Variant> =
        streamInfRe.findAll(master).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val height = resAttrRe.find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val bw = bwAttrRe.find(attrs)?.groupValues?.get(1)?.toLongOrNull()
            val u = try { java.net.URI(masterUrl).resolve(m.groupValues[2]).toString() } catch (_: Exception) { return@mapNotNull null }
            Variant(height, bw, u)
        }.distinctBy { it.height }.sortedByDescending { it.height }.toList()

    // Shared by Aincrad (muxed masters) and Beta Player: one selectable link per resolution,
    // each validated by content before it's emitted.
    private suspend fun emitVariants(
        variants: List<Variant>, cleanLabel: String, referer: String, headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit, tag: String, sizeKey: String,
        allowDeclaredFallback: Boolean = false,
        // v28: emit every variant as HLS without the probe (used when our own client is
        // refused but the player's network stack may not be).
        trustPlaylists: Boolean = false,
    ): Boolean {
        if (trustPlaylists) {
            for (v in variants) callback(newExtractorLink(source = cleanLabel, name = "$cleanLabel ${v.height}p",
                url = v.url, type = ExtractorLinkType.M3U8) { quality = v.height; this.referer = referer; this.headers = headers })
            return variants.isNotEmpty()
        }
        val sizes = if (estimateHlsSizes) {
            cachedSizes(sizeKey)
                ?: (withTimeoutOrNull(sizeEstimateBudgetMs) { estimateVariantSizes(variants, headers, allowDeclaredFallback) } ?: emptyMap())
                    .also { storeSizes(sizeKey, it) }
        } else emptyMap()
        val any = java.util.concurrent.atomic.AtomicBoolean(false)
        val gate = Semaphore(3)
        coroutineScope {
            for (v in variants) {
                launch {
                    gate.withPermit {
                        val probe = try {
                            app.get(v.url, headers = headers + mapOf("Range" to "bytes=0-4095"), timeout = 8L)
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { log("$tag: variant ${v.height}p probe failed: ${e.message}"); return@withPermit }
                        val pt = probe.text.trimStart()
                        when {
                            // v28: a refusal is a refusal, whatever its body looks like. v27 listed a
                            // 403 answer as a plain video file ("Sistenn1 1080p", type=VIDEO).
                            probe.code !in 200..299 ->
                                log("$tag: variant ${v.height}p refused (http ${probe.code})")
                            pt.startsWith("#EXTM3U") -> {
                                callback(newExtractorLink(source = cleanLabel, name = "$cleanLabel ${v.height}p" + formatSize(sizes[v.height], isEstimate = true),
                                    url = v.url, type = ExtractorLinkType.M3U8) { quality = v.height; this.referer = referer; this.headers = headers })
                                any.set(true)
                            }
                            pt.startsWith("<") || pt.contains("<html", ignoreCase = true) || pt.isBlank() ->
                                log("$tag: variant ${v.height}p unusable")
                            else -> {
                                callback(newExtractorLink(source = cleanLabel, name = "$cleanLabel ${v.height}p" + formatSize(parseContentRangeTotal(probe.headers)),
                                    url = v.url, type = ExtractorLinkType.VIDEO) { quality = v.height; this.referer = referer; this.headers = headers })
                                any.set(true)
                            }
                        }
                    }
                }
            }
        }
        return any.get()
    }

    /**
     * v28: try header sets in order against one variant playlist and its first segment (one
     * byte). Returns the first set that gets both; logs what each refused one got.
     */
    private suspend fun pickStreamHeaders(variantUrl: String, sets: List<Pair<String, Map<String, String>>>, tokenQuery: String = ""): Map<String, String>? {
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

    // Why sampling instead of BANDWIDTH × duration (what 3.x did): Aincrad's BANDWIDTH is a
    // fixed nominal ladder (276k/750k/2048k/4096k on every video checked), not a measured
    // rate. Measured on Re:Zero S4 ep 17, 1080p: nominal estimate 721MB, real ≈ 330MB.
    // Anime bitrate is also very scene-dependent (single 6s segments ranged 164KB–3.4MB),
    // so a few samples aren't enough either: 12 evenly spaced samples landed within ~7%
    // of a 24-sample reference; 3–8 samples were off by up to 60%.
    //
    // Each sample is a `Range: bytes=0-0` GET — the server answers 206 with
    // Content-Range: bytes 0-0/<total>, i.e. one byte of body. (HEAD is not usable: it
    // returns text/html with no length.) Cost: ~15 tiny requests per Aincrad source, run
    // 4 at a time under a 4s budget. Can be turned off in the extension settings.
    private data class RenditionStats(val bytesPerSec: Double, val durationSec: Double)
    private data class Seg(val dur: Double, val url: String, val byteLen: Long?)

    private suspend fun sampleRendition(playlistUrl: String, headers: Map<String, String>, samples: Int): RenditionStats? {
        val text = try { app.get(playlistUrl, headers = headers, timeout = 8L).text }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { return null }
        return sampleSegments(text, playlistUrl, headers, samples)
    }

    /** As sampleRendition, for a playlist already in hand; segQuery is added to query-less segment URLs. */
    private suspend fun sampleSegments(text: String, playlistUrl: String, headers: Map<String, String>, samples: Int, segQuery: String = ""): RenditionStats? {
        if (!text.trimStart().startsWith("#EXTM3U")) return null
        val segs = ArrayList<Seg>()
        var dur: Double? = null
        var byteLen: Long? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTINF:") -> dur = extinfRe.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                line.startsWith("#EXT-X-BYTERANGE:") -> byteLen = byteRangeRe.find(line)?.groupValues?.get(1)?.toLongOrNull()
                line.startsWith("#") -> {}
                else -> {
                    val d = dur
                    if (d != null) {
                        val u = (try { java.net.URI(playlistUrl).resolve(line).toString() } catch (_: Exception) { line })
                            .let { if (segQuery.isNotEmpty() && !it.contains('?')) "$it?$segQuery" else it }
                        segs += Seg(d, u, byteLen)
                    }
                    dur = null; byteLen = null
                }
            }
        }
        val total = segs.sumOf { it.dur }
        if (segs.isEmpty() || total <= 0) return null
        // Byte-range playlists carry exact sizes — no requests needed.
        if (segs.all { it.byteLen != null }) return RenditionStats(segs.sumOf { it.byteLen!! } / total, total)

        val k = samples.coerceAtMost(segs.size)
        val idx = (0 until k).map { ((it + 0.5) * segs.size / k).toInt().coerceIn(0, segs.size - 1) }.distinct()
        val sizes = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        val gate = Semaphore(4)
        coroutineScope {
            for (i in idx) launch {
                gate.withPermit {
                    try {
                        val r = app.get(segs[i].url, headers = headers + mapOf("Range" to "bytes=0-0"), timeout = 6L)
                        val size = when (r.code) {
                            206 -> r.headers["Content-Range"]?.substringAfterLast('/')?.trim()?.toLongOrNull()
                            200 -> r.headers["Content-Length"]?.toLongOrNull()
                            else -> null
                        }
                        r.closeQuietly()
                        if (size != null && size > 1) sizes[i] = size
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) {}
                }
            }
        }
        if (sizes.size < maxOf(2, (idx.size + 1) / 2)) { log("size: only ${sizes.size}/${idx.size} samples, skipping"); return null }
        val sampledDur = sizes.keys.sumOf { segs[it].dur }
        if (sampledDur <= 0) return null

        // v8 diagnostics. Ground truth from a real 1DM+ download (Enen ep 6, Aincrad 1080p):
        // 341MB / 141 segments = ~2.4MB per segment, while this estimator implied ~9.3MB —
        // so either the sizes the server reports in its headers are inflated, or this
        // playlist has far more segments than the stream really uses. These two lines say
        // which, without needing another test download.
        val meanKb = sizes.values.average() / 1024
        log("size: ${segs.size} segs, ${(total / 60).toInt()}m${(total % 60).toInt()}s, ${sizes.size} sampled, mean ${meanKb.toLong()}KB/seg → " +
            "${(sizes.values.sum() / sampledDur * 8 / 1000).toLong()} kbps; samples=" +
            sizes.entries.sortedBy { it.key }.take(4).joinToString(" ") { "[#${it.key} ${"%.1f".format(segs[it.key].dur)}s ${it.value / 1024}KB]" })

        // v8: verify one reported size is real. Ask for the LAST byte the server claims the
        // segment has: a truthful size answers 206 with one byte, an inflated one answers
        // 416 (Range Not Satisfiable). Costs one request and no meaningful data.
        val check = sizes.entries.minByOrNull { it.value }
        if (check != null) {
            val claimed = check.value
            val ok = try {
                val rr = app.get(segs[check.key].url, headers = headers + mapOf("Range" to "bytes=${claimed - 1}-${claimed - 1}"), timeout = 6L)
                val code = rr.code
                val reported = rr.headers["Content-Range"]?.substringAfterLast('/')?.trim()?.toLongOrNull()
                rr.closeQuietly()
                if (code == 416 || (reported != null && reported != claimed))
                    log("size: reported size looks wrong (claimed $claimed, tail request → $code, total=$reported)")
                code != 416 && (reported == null || reported == claimed)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { true } // network hiccup: don't throw the estimate away over it
            if (!ok) return null
        }
        return RenditionStats(sizes.values.sum() / sampledDur, total)
    }

    // v7: plausibility check. Real-device log: one Aincrad 1080p episode came out at ~1306MB,
    // i.e. ~7.5 Mbps average — nearly 2× the 4096 kbps the playlist declares as its ceiling,
    // and 2.5–4× the other episodes (179–530MB). A measured AVERAGE above the declared
    // BANDWIDTH doesn't happen with this encoder, so it means the 12 samples were unlucky
    // (or a server quirk). Re-sample with 24; if it's still implausible, show no size rather
    // than a wrong one.
    private suspend fun sampleVideoChecked(top: Variant, headers: Map<String, String>): RenditionStats? {
        val declaredBps = top.bandwidth?.takeIf { it > 0 }?.let { it / 8.0 }
        fun plausible(r: RenditionStats) = declaredBps == null || r.bytesPerSec <= declaredBps * 1.3
        val first = sampleRendition(top.url, headers, 12) ?: return null
        if (plausible(first)) return first
        log("size: ${top.height}p measured ${(first.bytesPerSec * 8 / 1000).toLong()} kbps > declared ${top.bandwidth!! / 1000} kbps, re-sampling with 24")
        val second = sampleRendition(top.url, headers, 24) ?: return null
        if (plausible(second)) return second
        log("size: ${top.height}p still ${(second.bytesPerSec * 8 / 1000).toLong()} kbps, not showing a size")
        return null
    }

    // Split-audio master (Aincrad): top video rendition + the audio rendition.
    private suspend fun estimateSplitAudioBytes(master: String, masterUrl: String, variants: List<Variant>, headers: Map<String, String>): Long? {
        val top = variants.firstOrNull() ?: return null
        val video = sampleVideoChecked(top, headers) ?: return null
        val audioUrl = audioMediaUriRe.find(master)?.groupValues?.get(1)
            ?.let { try { java.net.URI(masterUrl).resolve(it).toString() } catch (_: Exception) { null } }
        // Audio is near-constant bitrate AAC, so 3 samples is plenty.
        val audio = audioUrl?.let { sampleRendition(it, headers, 3) }
        val bytes = (video.bytesPerSec + (audio?.bytesPerSec ?: 0.0)) * video.durationSec
        log("size: split-audio ${top.height}p ≈ ${(bytes / 1_048_576).toLong()}MB (video ${(video.bytesPerSec * 8 / 1000).toLong()} kbps + audio ${audio?.let { "${(it.bytesPerSec * 8 / 1000).toLong()} kbps" } ?: "missing"}, ${(video.durationSec / 60).toInt()} min)")
        return bytes.toLong()
    }

    /**
     * v19: last-resort size from the playlist's own BANDWIDTH x duration, used only where the
     * CDN won't answer ranged segment requests (Sistenn's proxy is one) and only when the
     * caller opts in. Deliberately NOT used for Aincrad: its BANDWIDTH is a fixed nominal
     * ladder and this arithmetic overstates it by ~2x. Sistenn declares near-measured rates
     * (720p ~1.34 Mbps, 1080p ~2.59 Mbps), so here it lands close.
     */
    private suspend fun declaredSizes(variants: List<Variant>, headers: Map<String, String>): Map<Int, Long> {
        val top = variants.firstOrNull() ?: return emptyMap()
        if (variants.none { (it.bandwidth ?: 0) > 0 }) return emptyMap()
        val dur = playlistDurationSec(top.url, headers) ?: return emptyMap()
        if (dur <= 0) return emptyMap()
        val out = variants.mapNotNull { v ->
            val bw = v.bandwidth?.takeIf { it > 0 } ?: return@mapNotNull null
            v.height to (bw / 8.0 * dur).toLong()
        }.toMap()
        log("size: segments not measurable, using declared bitrate x ${(dur / 60).toInt()} min -> " +
            out.entries.sortedByDescending { it.key }.joinToString(", ") { "${it.key}p ~${it.value / 1_048_576}MB" })
        return out
    }

    /** Total runtime of a media playlist, from its EXTINF lines. One request. */
    private suspend fun playlistDurationSec(url: String, headers: Map<String, String>): Double? {
        val text = try { app.get(url, headers = headers, timeout = 8L).text }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { return null }
        if (!text.trimStart().startsWith("#EXTM3U")) return null
        val total = text.lineSequence().filter { it.startsWith("#EXTINF:") }
            .mapNotNull { extinfRe.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }.sum()
        return total.takeIf { it > 0 }
    }

    // Muxed masters: sample only the top variant, then scale the others by their BANDWIDTH
    // relative to it. The ladder is nominal, so this is rougher for lower rungs than for
    // the top one, but it doesn't multiply the request count by the number of variants.
    private suspend fun estimateVariantSizes(
        variants: List<Variant>, headers: Map<String, String>, allowDeclaredFallback: Boolean = false,
    ): Map<Int, Long> {
        val top = variants.firstOrNull() ?: return emptyMap()
        val s = sampleVideoChecked(top, headers)
            ?: return if (allowDeclaredFallback) declaredSizes(variants, headers) else emptyMap()
        val out = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        out[top.height] = (s.bytesPerSec * s.durationSec).toLong()
        log("size: ${top.height}p ≈ ${out[top.height]!! / 1_048_576}MB (${(s.bytesPerSec * 8 / 1000).toLong()} kbps measured vs ${(top.bandwidth ?: 0) / 1000} kbps declared, ${(s.durationSec / 60).toInt()} min)")

        // v14: the lower variants are measured too, with fewer samples. They used to be scaled
        // from the declared BANDWIDTH ladder, which is nominal — that's how a source could
        // advertise a bigger file than one that actually looks better.
        val topBw = top.bandwidth
        val bytesPerBw = if (topBw != null && topBw > 0) s.bytesPerSec / topBw else null
        val gate = Semaphore(2)
        coroutineScope {
            for (v in variants.drop(1)) {
                launch {
                    gate.withPermit {
                        val measured = try { sampleRendition(v.url, headers, 8) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { null }
                        if (measured != null) {
                            out[v.height] = (measured.bytesPerSec * measured.durationSec).toLong()
                            log("size: ${v.height}p ≈ ${out[v.height]!! / 1_048_576}MB (${(measured.bytesPerSec * 8 / 1000).toLong()} kbps measured)")
                        } else if (bytesPerBw != null) {
                            v.bandwidth?.let { out[v.height] = (it * bytesPerBw * s.durationSec).toLong() }
                        }
                    }
                }
            }
        }
        return out
    }

    // Appends " [1.3GB]" style suffix to a name. isEstimate prefixes with ~ since HLS
    // variant sizes are computed from bitrate×duration, not a server-reported value.
    private fun formatSize(bytes: Long?, isEstimate: Boolean = false): String {
        if (bytes == null || bytes <= 0) return ""
        val gb = bytes / 1_073_741_824.0
        val tilde = if (isEstimate) "~" else ""
        return if (gb >= 1) " [$tilde%.1fGB]".format(gb) else " [$tilde%.0fMB]".format(bytes / 1_048_576.0)
    }
    private fun parseContentRangeTotal(headers: okhttp3.Headers): Long? =
        headers["Content-Range"]?.substringAfterLast('/')?.trim()?.toLongOrNull()
            ?: headers["Content-Length"]?.toLongOrNull()

    // GDrive's `confirm=t` is not a real token — it only happens to work when Google skips
    // the virus-scan interstitial (small/short files). Anything past that threshold serves
    // an HTML confirmation page instead of video, which the player then fails to open
    // ("setDataSource failed"). Real fix: fetch that page, scrape the actual `confirm` +
    // `uuid` values Google issues, and replay them — this is Google's own two-step flow,
    // not a workaround. Every request uses a Range so a multi-GB file is never pulled
    // through just to read its headers.
    //
    // v4: also works out size + resolution, so Drive links show "[1.4GB]" and a real
    // quality instead of "unknown" (which never counted toward the minimum-quality stop).
    // In order of cost, each step only if the previous one didn't answer:
    //   size:    Content-Range total → Content-Length (only on a non-ranged 200) →
    //            the "(1.4G)" text on Google's interstitial page
    //   quality: resolution in the file name (Content-Disposition, or the interstitial's
    //            file link) → the MP4 header in the bytes we already downloaded → one more
    //            64KB ranged read at the moov box, for files whose header sits at the end.
    // Checked on the live site: file names are hit and miss ("…1080p.WEB-DL…mp4" vs
    // "[GachaFlexSubs] Sousou no Frieren S1E1.mp4"), hence the header fallback.
    private data class GDriveResolution(val url: String, val headers: Map<String, String>, val sizeBytes: Long?, val height: Int?)
    private val resolutionInNameRe = Regex("""(?i)(?<![0-9])(2160|1440|1080|720|576|480|360)[pi](?![a-z0-9])""")
    private val fourKInNameRe = Regex("""(?i)\b(4k|uhd)\b""")
    private val ucSizeRe = Regex("""\(\s*([\d.,]+)\s*([KMGT])B?\s*\)""", RegexOption.IGNORE_CASE)

    private fun heightFromName(name: String?): Int? {
        if (name.isNullOrBlank()) return null
        resolutionInNameRe.find(name)?.let { return it.groupValues[1].toInt() }
        return if (fourKInNameRe.containsMatchIn(name)) 2160 else null
    }
    private fun filenameFromDisposition(headers: okhttp3.Headers): String? {
        val cd = headers["Content-Disposition"] ?: return null
        Regex("""filename\*\s*=\s*[^']*'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.let {
            return try { java.net.URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") } catch (_: Exception) { it.groupValues[1] }
        }
        return Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)?.trim()
    }
    private fun sizeFromRanged(r: NiceResponse): Long? = when (r.code) {
        206 -> r.headers["Content-Range"]?.substringAfterLast('/')?.trim()?.toLongOrNull()
        200 -> r.headers["Content-Length"]?.toLongOrNull()?.takeIf { it > 65_536 } // server ignored Range
        else -> null
    }
    private fun bodyBytes(r: NiceResponse): ByteArray? =
        try { r.okhttpResponse.body?.bytes() } catch (_: Exception) { null }

    // ── Minimal MP4 reader: video track height from tkhd, or where moov is ───────
    private sealed class Mp4Probe {
        data class Height(val height: Int) : Mp4Probe()
        data class MoovAt(val offset: Long, val size: Long) : Mp4Probe()
        object Unknown : Mp4Probe()
    }
    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
        ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)
    private fun be64(b: ByteArray, o: Int): Long = (be32(b, o) shl 32) or be32(b, o + 4)
    private fun boxType(b: ByteArray, o: Int) = String(b, o, 4, Charsets.ISO_8859_1)

    /** Walks top-level boxes of the bytes starting at file offset `base`. */
    private fun probeMp4(b: ByteArray, base: Long = 0L): Mp4Probe {
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
    private fun findVideoHeight(b: ByteArray, start: Int, end: Int): Int? {
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

    private suspend fun gdriveHeight(name: String?, firstBytes: ByteArray?, url: String, headers: Map<String, String>, total: Long?): Int? {
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

    // Finishes a resolve once we have a response that IS the video (not an HTML page).
    private suspend fun gdriveFromMedia(r: NiceResponse, earlierCookies: Map<String, String>, nameHint: String?, sizeHint: Long?, how: String): GDriveResolution {
        val allCookies = (earlierCookies + r.cookies).entries.joinToString("; ") { "${it.key}=${it.value}" }
        val headers = mapOf("User-Agent" to ua) + (if (allCookies.isNotBlank()) mapOf("Cookie" to allCookies) else emptyMap())
        val size = sizeFromRanged(r) ?: sizeHint
        val bytes = if (r.code == 206) bodyBytes(r) else { r.closeQuietly(); null }
        val height = gdriveHeight(filenameFromDisposition(r.headers) ?: nameHint, bytes, r.url, headers, size)
        log("gdrive: resolved ($how), size=$size height=$height")
        return GDriveResolution(r.url, headers, size, height)
    }
    private fun isHtml(r: NiceResponse) = (r.headers["Content-Type"] ?: "").contains("text/html", ignoreCase = true)

    /**
     * v32: the device log (2026-09-26) had a Drive link resolve fine here (form submit ->
     * 830MB, 1080p) and then fail in ExoPlayer 22s later with UnrecognizedInputFormat, i.e. the
     * same URL no longer returned the video. Ask for it once more the way the player will (from
     * byte 0, same headers) and only list it if that is still the file. Logs what Google sent
     * otherwise, which says whether the confirm/uuid link is single-use, quota-limited, etc.
     */
    // ── Drive streaming (v35) ────────────────────────────────────────────────
    // What drive.google.com/file/d/<id>/preview itself does (browser capture, 2026-09-26):
    //   GET content-workspacevideo-pa.googleapis.com/v1/drive/media/<id>/playback?key=<AIza…>
    // Cookie-free; 200 with mediaStreamingData.formatStreamingData.progressiveTranscodes =
    // muxed MP4s (itag 18/22/37 → 360/720/1080p) on *.c.drive.google.com/videoplayback, valid
    // 3 h and signed to the requesting IP (the phone, same as the player). Without key → 403;
    // a file over its signed-out play limit → 429. The key is Drive's public web key: it is read
    // from the preview page (every AIza… string there is tried once) and then remembered.
    private data class DriveStream(val height: Int, val url: String, val sizeBytes: Long?)
    @Volatile private var driveApiKey: String? = null
    private val driveKeyRe = Regex("""AIza[0-9A-Za-z_\-]{35}""")
    private val driveStreamCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<DriveStream>, Long>>()
    private val driveStreamTtlMs = 2 * 60 * 60 * 1000L // links live 3 h
    private val driveStreamQuotaUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun resolveGDriveStreams(fileId: String): List<DriveStream> {
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
        val json = body?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: return emptyList()
        val progressive = json.optJSONObject("mediaStreamingData")?.optJSONObject("formatStreamingData")?.optJSONArray("progressiveTranscodes")
            ?: run { log("gdrive: playback answer has no progressive formats (state=${json.optJSONObject("mediaStreamingData")?.optJSONObject("transcodeAvailabilityState")?.optString("state")})"); return emptyList() }
        val found = (0 until progressive.length()).mapNotNull { i ->
            val o = progressive.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").ifBlank { return@mapNotNull null }
            val h = o.optJSONObject("transcodeMetadata")?.optInt("height", 0)?.takeIf { it > 0 } ?: return@mapNotNull null
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

    // v33: files whose download quota ran out. Google resets it over the day; asking again on
    // every load only adds to the count, so the file is skipped for a few hours.
    private val gdriveQuotaUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val gdriveQuotaBackoffMs = 3 * 60 * 60 * 1000L

    private suspend fun gdriveReusable(res: GDriveResolution, fileId: String): Boolean {
        return try {
            val r = app.get(res.url, headers = res.headers + mapOf("Range" to "bytes=0-"), timeout = 15)
            val ct = r.headers["Content-Type"] ?: "?"
            val head = try { r.okhttpResponse.body?.byteStream()?.use { s -> ByteArray(512).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } } } catch (_: Exception) { null } ?: ByteArray(0)
            r.closeQuietly()
            val html = ct.contains("text/html", true) || String(head, Charsets.ISO_8859_1).trimStart().startsWith("<")
            if (html || r.code !in 200..299) {
                val page = String(head, Charsets.UTF_8)
                val title = Regex("<title>([^<]*)", RegexOption.IGNORE_CASE).find(page)?.groupValues?.get(1)?.trim()
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

    private suspend fun resolveGDrive(fileId: String): GDriveResolution? {
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
}

/**
 * v32: serves rewritten HLS media playlists to the app's own player on 127.0.0.1.
 *
 * Sistenn-type hosts want their access token (?k=…&kx=…) on every playlist AND segment
 * request, but list segments relative and without it; ExoPlayer resolves them against the
 * playlist URL, drops the query, and gets 403. An ExtractorLink can't carry a rewritten
 * playlist, so each one is registered here and the player is given a local URL. Every
 * request re-fetches the real playlist (with the link's headers) and returns it with each
 * URI made absolute and tokenised. Only playlists pass through here: segment bytes go from
 * the stream host to the player directly. CloudStream allows cleartext traffic
 * (usesCleartextTraffic="true"), so http://127.0.0.1 is permitted.
 */
internal class LocalHlsServer(
    private val log: (String) -> Unit,
    // v41: how the server talks to the stream's site (Chrome's network stack when available).
    private val fetch: (String, Map<String, String>) -> NiceResponse? = { u, h ->
        try { kotlinx.coroutines.runBlocking { app.get(u, headers = h, timeout = 10L) } } catch (_: Exception) { null } },
) {
    private data class Entry(val url: String, val query: String, val headers: Map<String, String>, val created: Long,
                             val cached: String? = null, val strip: Boolean = false, val token: TokenState? = null,
                             val beat: Heartbeat? = null)

    /**
     * v41: the page's "playing" signal. Sistenn's player calls /api/v1/player?t=… every 10 s
     * while the video plays (the same t each time; the answer is a fresh k/kx) and stops while
     * it is paused. Streams listed from the API now do the same: the signal runs while the app's
     * player is fetching this stream (playlist or segments within the last 30 s), pauses when
     * it stops, and ends after 5 idle minutes. A fresh k/kx also renews the stream token.
     */
    class Heartbeat(val url: String, val headers: Map<String, String>, val token: TokenState?) {
        @Volatile var lastUse = 0L
        @Volatile var running = false
    }
    private fun touch(b: Heartbeat) {
        b.lastUse = System.currentTimeMillis()
        synchronized(b) { if (b.running) return; b.running = true }
        Thread({
            var beats = 0
            try {
                while (true) {
                    Thread.sleep(10_000)
                    val idle = System.currentTimeMillis() - b.lastUse
                    if (idle > 5 * 60_000L) break
                    if (idle > 30_000L) continue
                    val r = fetch(b.url, b.headers) ?: continue
                    beats++
                    val o = try { org.json.JSONObject(r.text) } catch (_: Exception) { null }
                    val k = o?.optString("k").orEmpty(); val kx = o?.optLong("kx") ?: 0L
                    val t = b.token
                    if (t != null && r.code in 200..299 && k.isNotBlank() && kx > 0) { t.query = "k=$k&kx=$kx"; t.expiresAtMs = TokenState.expiry(t.query) }
                    if (beats == 1) log("hls-proxy: playing signal sent (every 10 s while playing)")
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) { log("hls-proxy: playing signal stopped: ${e.javaClass.simpleName}") }
            finally { b.running = false }
        }, "anizm-hls-beat").apply { isDaemon = true }.start()
    }

    /** v35: a stream token shared by all qualities of one stream, renewed via the page's refresh URL. */
    class TokenState(initialQuery: String, val refreshUrl: String, val headers: Map<String, String>) {
        @Volatile var query: String = initialQuery
        @Volatile var expiresAtMs: Long = expiry(initialQuery)
        @Volatile var lastTryMs: Long = 0L
        companion object {
            private val kxRe = Regex("""(?:^|&)kx=(\d{9,13})""")
            fun expiry(q: String): Long = kxRe.find(q)?.groupValues?.get(1)?.toLongOrNull()
                ?.let { if (it < 100_000_000_000L) it * 1000 else it } ?: (System.currentTimeMillis() + 25 * 60_000L)
        }
    }

    /** Renew the token if it expires within 5 min (at most one attempt per 30 s). */
    private fun ensureFresh(t: TokenState) {
        val now = System.currentTimeMillis()
        if (t.expiresAtMs - now > 5 * 60_000L) return
        synchronized(t) {
            if (t.expiresAtMs - System.currentTimeMillis() > 5 * 60_000L || now - t.lastTryMs < 30_000L) return
            t.lastTryMs = now
            try {
                val r = fetch(t.refreshUrl, t.headers) ?: throw java.io.IOException("no answer")
                val o = org.json.JSONObject(r.text)
                val k = o.optString("k"); val kx = o.optLong("kx")
                if (r.code in 200..299 && k.isNotBlank() && kx > 0) {
                    t.query = "k=$k&kx=$kx"; t.expiresAtMs = TokenState.expiry(t.query)
                    log("hls-proxy: stream token renewed, valid ${(t.expiresAtMs - System.currentTimeMillis()) / 60000} more min")
                } else log("hls-proxy: token refresh answered http ${r.code}: ${r.text.take(120)}")
            } catch (e: Exception) { log("hls-proxy: token refresh failed: ${e.message?.take(80)}") }
        }
    }
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    @Volatile private var socket: java.net.ServerSocket? = null
    private val uriAttrRe = Regex("""URI="([^"]+)"""")
    private val entryTtlMs = 6 * 60 * 60 * 1000L

    /** Returns the local URL for this playlist, or null if the server could not start. */
    fun register(url: String, query: String, headers: Map<String, String>, cachedText: String? = null, stripSegments: Boolean = false,
                 token: TokenState? = null, beat: Heartbeat? = null): String? {
        val port = ensureStarted() ?: return null
        val now = System.currentTimeMillis()
        entries.entries.removeAll { now - it.value.created > entryTtlMs }
        rewritten.keys.retainAll(entries.keys)
        val key = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        entries[key] = Entry(url, query, headers, now, cachedText?.takeIf { it.trimStart().startsWith("#EXTM3U") }, stripSegments, token, beat)
        return "http://127.0.0.1:$port/hls/$key.m3u8"
    }

    // v43: pooled threads. One new thread per request (the player makes one per 2-6 s segment)
    // is churn a TV box's CPU notices; idle threads are reused, and dropped after 60 s.
    private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "anizm-hls-conn").apply { isDaemon = true } }
    // v43: a VOD playlist the page fetched never changes, so it is rewritten once per entry.
    private val rewritten = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, ByteArray>>() // key -> (port, body)

    @Synchronized private fun ensureStarted(): Int? {
        socket?.takeIf { !it.isClosed }?.let { return it.localPort }
        return try {
            val ss = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
            socket = ss
            Thread({
                while (!ss.isClosed) {
                    val c = try { ss.accept() } catch (_: Exception) { break }
                    try { pool.execute { handle(c) } } catch (_: Exception) { try { c.close() } catch (_: Exception) {} }
                }
            }, "anizm-hls").apply { isDaemon = true }.start()
            log("hls-proxy: listening on 127.0.0.1:${ss.localPort}")
            ss.localPort
        } catch (e: Exception) { log("hls-proxy: could not start: ${e.message}"); null }
    }

    private fun handle(c: java.net.Socket) {
        try {
            c.use { sock ->
                sock.soTimeout = 15_000
                // v43: headers and body are separate writes; without this, Nagle's algorithm can hold
                // the body back ~40 ms per segment waiting for an ACK.
                try { sock.tcpNoDelay = true } catch (_: Exception) {}
                val input = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = input.readLine() ?: return
                while (true) { val l = input.readLine() ?: break; if (l.isEmpty()) break }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0) ?: ""
                val target = parts.getOrNull(1) ?: ""
                val isSeg = target.startsWith("/seg/")
                val key = (if (isSeg) target.substringAfter("/seg/", "") else target.substringAfter("/hls/", ""))
                    .substringBefore('.').substringBefore('?').substringBefore('/')
                val entry = entries[key]
                val out = sock.getOutputStream()
                fun reply(code: Int, reason: String, body: ByteArray, type: String) {
                    out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                        "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                    if (method != "HEAD") out.write(body)
                    out.flush()
                }
                if (entry == null) { reply(404, "Not Found", ByteArray(0), "text/plain"); return }
                entry.beat?.let { touch(it) }
                if (isSeg) {
                    val segUrl = try { java.net.URLDecoder.decode(target.substringAfter("?u=", ""), "UTF-8") } catch (_: Exception) { "" }
                    if (!segUrl.startsWith("http")) { reply(400, "Bad Request", ByteArray(0), "text/plain"); return }
                    // v35: tokenised streams: send the player straight to the host with the current token.
                    // v41: streams with a playing signal come through here too (so it knows they are
                    // playing) and are sent on unchanged.
                    if (!entry.strip) {
                        val t = entry.token
                        val loc = if (t != null) { ensureFresh(t); segUrl.substringBefore('?') + "?" + t.query } else segUrl
                        out.write(("HTTP/1.1 302 Found\r\nLocation: $loc\r\nContent-Length: 0\r\nCache-Control: no-store\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                        out.flush(); return
                    }
                    // v36: streamed, not buffered. v35 downloaded the whole disguised segment before
                    // sending a byte; after a seek the player gave up waiting (device log 2026-09-26:
                    // "seeked 10 minutes forward and it didn't play"). Now only the head is read until
                    // the real media start is found, then the rest is piped through as it arrives.
                    // timeout = 0: no whole-call timeout, which would cut long transfers off.
                    // v40: one retry on a network error or a 5xx/429 from the CDN, so a single hiccup
                    // doesn't reach the player as a failed segment.
                    var r: NiceResponse? = null
                    for (attempt in 0 until 2) {
                        r = try { kotlinx.coroutines.runBlocking { app.get(segUrl, headers = entry.headers, timeout = 0L) } } catch (e: Exception) { null }
                        val code = r?.code ?: 0
                        if (code in 200..299 || (code in 400..499 && code != 429) || attempt == 1) break
                        r?.closeQuietly()
                        Thread.sleep(400)
                    }
                    val src = try { r?.okhttpResponse?.body?.byteStream() } catch (_: Exception) { null }
                    if (r == null || r.code !in 200..299 || src == null) {
                        log("hls-proxy: segment ${segUrl.substringBefore('?').takeLast(50)} -> ${r?.code ?: "error"}")
                        r?.closeQuietly()
                        reply(502, "Bad Gateway", ByteArray(0), "text/plain"); return
                    }
                    try {
                        val headBuf = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(65_536)
                        var start: Int? = null
                        while (headBuf.size() < 262_144) {
                            val n = src.read(buf); if (n < 0) break
                            headBuf.write(buf, 0, n)
                            if (headBuf.size() >= 1_024) { start = findMediaStart(headBuf.toByteArray()); if (start != null) break }
                        }
                        val head = headBuf.toByteArray()
                        val at = start ?: findMediaStart(head) ?: 0
                        if (at > 0 && !loggedStrip) { loggedStrip = true; log("hls-proxy: segments carry a ${at}-byte disguise (${head.take(4).joinToString("") { "%02x".format(it) }}…), stripping it") }
                        val type = if (head.size > at && head[at] == 0x47.toByte()) "video/mp2t" else "video/mp4"
                        val total = try { r.okhttpResponse.body?.contentLength() ?: -1L } catch (_: Exception) { -1L }
                        val lengthHeader = if (total > at) "Content-Length: ${total - at}\r\n" else ""
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n$lengthHeader" +
                            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                        if (method != "HEAD") {
                            out.write(head, at, head.size - at)
                            while (true) { val n = src.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                        }
                        out.flush()
                    } finally { r.closeQuietly() }
                    return
                }
                // v33: VOD playlists (ENDLIST) don't change, so the copy the sniff page fetched
                // through the WebView is served as-is; only without one is the host asked.
                entry.cached?.let {
                    val port = sock.localPort
                    val body = rewritten[key]?.takeIf { c -> c.first == port }?.second ?: rewrite(it, entry, key, port).toByteArray(Charsets.UTF_8).also { b ->
                        rewritten[key] = port to b
                        log("hls-proxy: served ${entry.url.substringBefore('?').substringAfterLast('/')} from the page's copy")
                    }
                    reply(200, "OK", body, "application/vnd.apple.mpegurl"); return
                }
                var failure = ""
                val upstream = try {
                    kotlinx.coroutines.runBlocking { app.get(entry.url, headers = entry.headers, timeout = 15L) }
                } catch (e: Exception) { failure = "${e.javaClass.simpleName}: ${e.message?.take(80)}"; null }
                val text = upstream?.text
                if (upstream == null || upstream.code !in 200..299 || text == null || !text.trimStart().startsWith("#EXTM3U")) {
                    log("hls-proxy: upstream ${entry.url.substringBefore('?').takeLast(50)} -> ${upstream?.code ?: failure}")
                    reply(502, "Bad Gateway", ByteArray(0), "text/plain"); return
                }
                reply(200, "OK", rewrite(text, entry, key, sock.localPort).toByteArray(Charsets.UTF_8), "application/vnd.apple.mpegurl")
            }
        } catch (e: java.net.SocketException) {
            // The player closed the connection (seek, quality switch): routine, not an error.
        } catch (e: Exception) { log("hls-proxy: ${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun tokenised(ref: String, entry: Entry): String {
        val abs = try { java.net.URI(entry.url).resolve(ref.trim()).toString() } catch (_: Exception) { ref.trim() }
        return if (abs.contains('?') || entry.query.isEmpty()) abs else "$abs?${entry.query}"
    }

    @Volatile private var loggedStrip = false

    /** Where the real media starts: 3 MPEG-TS sync bytes 188 apart, or an MP4 ftyp/styp/moof box; null if neither. */
    private fun findMediaStart(b: ByteArray): Int? = mediaStart(b).takeIf { it > 0 || looksLikeMediaAt0(b) }
    private fun looksLikeMediaAt0(b: ByteArray): Boolean =
        (b.size > 376 && b[0] == 0x47.toByte() && b[188] == 0x47.toByte() && b[376] == 0x47.toByte()) ||
            (b.size >= 8 && isMp4Box(b, 4))

    /** v43: "ftyp", "styp" or "moof" at i, compared byte by byte (was a new String per position). */
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

    private fun rewrite(playlist: String, entry: Entry, key: String, port: Int): String = playlist.lineSequence().joinToString("\n") { raw ->
        val line = raw.trimEnd('\r')
        when {
            line.isBlank() -> line
            line.startsWith("#") && (entry.token != null || entry.beat != null) && !entry.strip -> uriAttrRe.replace(line) { m ->
                "URI=\"http://127.0.0.1:$port/seg/$key?u=" + java.net.URLEncoder.encode(tokenised(m.groupValues[1], entry), "UTF-8") + "\"" }
            line.startsWith("#") -> uriAttrRe.replace(line) { m -> "URI=\"${tokenised(m.groupValues[1], entry)}\"" }
            // v35: disguised segments come back through here to be cut down to the real media;
            // tokenised ones come back to be redirected with a fresh token.
            entry.strip || entry.token != null || entry.beat != null -> "http://127.0.0.1:$port/seg/$key?u=" + java.net.URLEncoder.encode(tokenised(line, entry), "UTF-8")
            else -> tokenised(line, entry)
        }
    }
}
