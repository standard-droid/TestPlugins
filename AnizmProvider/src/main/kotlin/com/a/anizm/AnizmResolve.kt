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
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

// From a player id to the address its player lives at: the /player/<id> redirect, and the WebView fallback.

/**
 * v48: who made a source. The episode page asks /bolum/<ep>/video/<id> when a player button is
 * pressed, just before it loads /player/<id>; the answer names the translator, encoder and
 * uploader, which go into the link's name (setting "Show who made each source"). One small
 * request per looked-up source, the same one a browser makes; skipped when the setting is off.
 */
internal suspend fun AnizmProvider.fetchVideoInfo(epId: String, nid: String, episodeUrl: String) {
    if (videoCredits[nid] != null) return
    val info = try {
        siteText("$mainUrl/bolum/$epId/video/$nid", episodeXhrHeaders(episodeUrl), timeout = 6L)?.let { AnizmParser.videoInfo(it) }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } ?: return
    videoCredits[nid] = info.credits()
    // The page waits for this answer before it loads the player frame.
    delay(60L + (Math.random() * 140).toLong())
}

internal suspend fun AnizmProvider.resolveViaRedirect(nid: String, episodeUrl: String, epId: String? = null,
                                       playerUrl: String = playerUrlFor(nid)): String? {
    if (epId != null && settings.showCredits) fetchVideoInfo(epId, nid, episodeUrl)
    // Chrome's headers for a frame navigation (client hints and Sec-Fetch-*) with the episode
    // page as Referer; the Referer is the part the site checks.
    val cookieHeader = webViewCookies()?.let { mapOf("Cookie" to it) } ?: emptyMap()
    val headers = baseHeaders + navHints + mapOf("Referer" to onCurrentHost(episodeUrl)) + cookieHeader
    AnizmProvider.lastProbeTarget = nid to episodeUrl
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
        // v39: the self-test no longer runs here. It sent 9 more requests to the URL that had
        // just been refused, and held up the WebView fallback for 7-30 s while it did.
        // "Run connection test" in the settings still runs it on demand.
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
        r.closeQuietly()
        if (r.code == 404 || r.code == 410) {
            // v39: ask the site where this id's player is now, the way the episode page does.
            val moved = epId?.let { sitePlayerUrl(it, nid, episodeUrl) }?.takeIf { it != playerUrl }
            // Only on the site's own host: this request carries anizm's cookies and same-origin headers.
            val movedHost = moved?.let { try { java.net.URI(it).host } catch (_: Exception) { null } }
            if (moved != null && movedHost != null && siteHostRe.matches(movedHost)) {
                if (moved.contains(nid)) playerUrlTemplate = moved.substringBeforeLast(nid) + "{id}" + moved.substringAfterLast(nid)
                logW("site-change warning: ${playerUrl.substringAfter("//")} answered ${r.code}; the site now names ${moved.substringAfter("//")} — using that")
                return resolveViaRedirect(nid, episodeUrl, null, moved)
            }
            logW("site-change warning: ${playerUrl.substringAfter("//")} returned ${r.code} (Referer gate changed?)" +
                (moved?.let { "; the site names $it, not on its own host — not followed" } ?: ""))
        } else log("resolve: /player/$nid http ${r.code}")
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

internal suspend fun AnizmProvider.httpResolveEmbeds(
    numIds: List<String>,
    episodeUrl: String,
    // v39: the episode id for each numId (for the site's own player lookup, see playerUrlFor), and
    // a stop signal: once the lazy target is met, the lookups still queued behind the gate are dropped.
    epIdOf: (String) -> String? = { null },
    stop: () -> Boolean = { false },
    // v48: called before each lookup; lazy loading waits here for the source looked up before
    // to report (or 1.5 s), so a lookup that turns out not to be needed is not made.
    hold: suspend () -> Unit = {},
    onResolved: (numId: String, embed: String) -> Unit,
) {
    coroutineScope {
        numIds.forEach { nid ->
            launch {
                resolveGate.withPermit {
                    // One refusal stops the rest of the batch — don't keep knocking.
                    if (System.currentTimeMillis() < playerHttpBlockedUntil || stop()) return@withPermit
                    hold()
                    if (stop()) return@withPermit
                    paceResolve()
                    if (System.currentTimeMillis() < playerHttpBlockedUntil || stop()) return@withPermit
                    val embed = try { resolveViaRedirect(nid, episodeUrl, epIdOf(nid)) }
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
internal suspend fun AnizmProvider.resolveEmbeds(
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
                            val hash = noteApMatch(m)
                            if (seenEmbeds.add(hash))
                                handler.post { handleDetectedEmbed("ap:$hash") }
                            return emptyResponse()
                        }
                    }
                    if (host.contains("puffytr")) {
                        puffytrRe.find(url)?.let { m ->
                            val hash = noteBetaMatch(m)
                            if (seenEmbeds.add(hash))
                                handler.post { handleDetectedEmbed("bp:$hash") }
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

internal suspend fun AnizmProvider.sitePlayerUrl(epId: String, nid: String, episodeUrl: String): String? = try {
    siteText("$mainUrl/bolum/$epId/video/$nid", episodeXhrHeaders(episodeUrl), timeout = 8L)
        ?.let { AnizmParser.playerUrl(it, mainUrl) }
} catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
