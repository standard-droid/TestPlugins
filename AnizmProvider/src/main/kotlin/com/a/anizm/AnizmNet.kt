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
import android.webkit.CookieManager
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody

// How requests leave the app: Chrome's network stack (Cronet) where available, the site's cookies, Cloudflare handling.

/**
 * One request through Chrome's network stack. Null when the stack is unavailable or the
 * request failed for a reason other than a timeout (the caller then uses OkHttp); a timeout
 * throws SocketTimeoutException, as OkHttp would. Redirects are followed here, hop by hop,
 * so the final URL is known. The body is read up to maxBytes.
 */
internal suspend fun AnizmProvider.chromeFetch(url: String, headers: Map<String, String>, timeoutSec: Long, allowRedirects: Boolean = true,
                                formBody: Map<String, String>? = null, maxBytes: Int = 6_000_000): NiceResponse? {
    if (System.currentTimeMillis() < cronetOffUntil) return null
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
            catch (t: Throwable) { noteCronetFailure(t); log("net: Chrome stack could not open ${current.substringBefore('?').takeLast(60)} (${t.javaClass.simpleName})"); return null }
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val result: Pair<Int, ByteArray>? = coroutineScope {
            // Ends the blocking request below on a timeout, and (v48) when the caller is cancelled:
            // leaving the screen used to leave the request running until its own timeout.
            val watchdog = launch {
                try { delay(left); timedOut.set(true); try { conn.disconnect() } catch (_: Throwable) {} }
                catch (e: kotlinx.coroutines.CancellationException) {
                    if (!finished.get()) try { conn.disconnect() } catch (_: Throwable) {}
                    throw e
                }
            }
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
                if (timedOut.get()) throw java.net.SocketTimeoutException("timeout")
                noteCronetFailure(e)
                log("net: Chrome stack failed on ${current.substringBefore('?').takeLast(60)} (${e.javaClass.simpleName}: ${e.message?.take(60)}) — using OkHttp")
                null
            } finally { finished.set(true); watchdog.cancel() }
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
        cronetFailures.set(0)
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

/** Chrome's network stack, or OkHttp when it is unavailable. */
internal suspend fun AnizmProvider.browserGet(url: String, headers: Map<String, String>, timeoutSec: Long, allowRedirects: Boolean = true): NiceResponse =
    chromeFetch(url, headers, timeoutSec, allowRedirects) ?: app.get(url, headers = headers, timeout = timeoutSec, allowRedirects = allowRedirects)

// 4.0: every anizm.net request goes through here. Replaces getSession() + the
// 403/419 "refresh session and retry" loop, which had a real bug: the refresh ran the
// homepage through CloudflareKiller, but the retry itself did NOT use the interceptor,
// and CloudflareKiller only attaches its cf_clearance cookie to requests it
// intercepts — so once CF turned a challenge on, everything but the homepage kept
// failing. Now the blocked request itself is retried through cfKiller, and we stay
// in that mode for a while instead of eating a failed request every time.
internal suspend fun AnizmProvider.siteGet(
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

internal suspend fun AnizmProvider.siteText(url: String, headers: Map<String, String>,
                             params: Map<String, String> = emptyMap(), timeout: Long = 12L): String? {
    val r = siteGet(url, headers, params, timeout)
    if (r.code !in 200..299) { log("http ${r.code} for ${url.substringBefore('?')}"); return null }
    return r.text
}

internal suspend fun AnizmProvider.siteDocument(url: String, headers: Map<String, String>, timeout: Long = 12L): org.jsoup.nodes.Document? =
    siteText(url, headers, timeout = timeout)?.let { org.jsoup.Jsoup.parse(it, url) }

// Self-test, run from the settings' "Run connection test" (v39: no longer automatic). It replays the same
// request a few different ways and logs what each one gets back, so a single logcat shows
// whether the block is about the path, the headers, the missing cookies or the client
// itself — instead of us guessing one variable at a time across builds.
@Volatile internal var diagnosedThisSession = false
internal suspend fun AnizmProvider.diagnoseDirectBlock(nid: String, episodeUrl: String, collect: ((String) -> Unit)? = null) {
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
