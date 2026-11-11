package com.a.anizm

import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Serves rewritten HLS playlists to the app's own player on 127.0.0.1.
 *
 * An ExtractorLink can't carry a changed playlist, so each one is registered here and the player
 * is given a local URL. Three uses:
 *  - a playlist text the provider already has and changed (Aincrad's top-quality-only master,
 *    Beta playlists with their missing discontinuities marked): served as it is, with every URI
 *    made absolute; segments go from the stream host to the player directly;
 *  - Sistenn-type hosts, which want their access token (?k=…&kx=…) on every playlist and segment
 *    request but list segments relative and without it: the playlist is fetched with the link's
 *    headers and each segment comes back here to be redirected with the current token;
 *  - disguised segments (an image header in front of the media): each segment is fetched here
 *    and passed on from where the real media starts.
 * CloudStream allows cleartext traffic (usesCleartextTraffic="true"), so http://127.0.0.1 is permitted.
 */
internal class LocalHlsServer(
    private val log: (String) -> Unit,
    // v41: how the server talks to the stream's site (Chrome's network stack when available).
    private val fetch: (String, Map<String, String>) -> NiceResponse? = { u, h ->
        try { kotlinx.coroutines.runBlocking { app.get(u, headers = h, timeout = 10L) } } catch (_: Exception) { null } },
) {
    private class Entry(val url: String, val query: String, val headers: Map<String, String>, val created: Long,
                        val strip: Boolean = false, val token: TokenState? = null, val beat: Heartbeat? = null) {
        // Hosts this entry's playlist names; /seg/ only fetches from these (v48).
        val hosts: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    }

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

    // v48: the same playlist text registered again gets the address it already has. Every load of
    // an episode used to add a new entry (and a new 75-150 KB copy, kept 6 h), and handed the app a
    // different URL for the same stream, which it then listed twice.
    private val byContent = java.util.concurrent.ConcurrentHashMap<String, String>() // url + text hash -> key
    private val maxEntries = 48

    /** Returns the local URL for this playlist, or null if the server could not start. */
    @Synchronized
    fun register(url: String, query: String, headers: Map<String, String>, cachedText: String? = null, stripSegments: Boolean = false,
                 token: TokenState? = null, beat: Heartbeat? = null): String? {
        val port = ensureStarted() ?: return null
        val now = System.currentTimeMillis()
        entries.entries.removeAll { now - it.value.created > entryTtlMs }
        val text = cachedText?.takeIf { it.trimStart().startsWith("#EXTM3U") }
        // The playlist's own address only matters when it lists something relative to it; a text of
        // absolute addresses (Beta: its /m3/<token> address changes, its text doesn't) is the same stream.
        val relative = text?.lineSequence()?.map { it.trim() }?.any { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("http") } == true ||
            text?.contains("URI=\"/") == true
        val contentKey = if (text != null && token == null && beat == null)
            "${if (relative) url.substringBefore('?') else ""}|$query|$stripSegments|${text.hashCode()}|${text.length}|${headers.hashCode()}" else null
        contentKey?.let { ck -> byContent[ck]?.takeIf { entries.containsKey(it) }?.let { return "http://127.0.0.1:$port/hls/$it.m3u8" } }
        // Oldest out first when there are many (a long session of episodes).
        while (entries.size >= maxEntries) entries.entries.minByOrNull { it.value.created }?.let { entries.remove(it.key) } ?: break
        rewritten.keys.retainAll(entries.keys)
        byContent.values.retainAll(entries.keys)
        val key = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val entry = Entry(url, query, headers, now, strip = stripSegments, token = token, beat = beat)
        entries[key] = entry
        // A copy in hand is rewritten here, once, and only the result is kept.
        text?.let { rewritten[key] = rewrite(it, entry, key, port).toByteArray(Charsets.UTF_8) }
        contentKey?.let { byContent[it] = key }
        return "http://127.0.0.1:$port/hls/$key.m3u8"
    }

    // Pooled threads: idle ones are reused and dropped after 60 s. v48: at most 16 at once (it was
    // unbounded); a player asks for a handful of things at a time, and a request beyond that is refused
    // rather than left to pile up threads on a TV box.
    private val pool = java.util.concurrent.ThreadPoolExecutor(0, 16, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue()) { r -> Thread(r, "anizm-hls-conn").apply { isDaemon = true } }
    // A VOD playlist never changes, so it is rewritten once per entry.
    private val rewritten = java.util.concurrent.ConcurrentHashMap<String, ByteArray>() // key -> body

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
                    // v48: only addresses on the hosts this entry's own playlist named. The link's headers
                    // (cookies among them) go out with the request, so it must not be an arbitrary one.
                    // v49: fail closed. The hosts are learned when the playlist is rewritten, which always
                    // happens before the player can know any /seg/ address; an entry with none yet has no
                    // legitimate segment, so nothing is fetched for it (it used to fetch anything).
                    val segHost = segUrl.toHttpUrlOrNull()?.host
                    if (segHost == null || segHost !in entry.hosts) { reply(403, "Forbidden", ByteArray(0), "text/plain"); return }
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
                    // v49: but a stalled host is given up on: the answer must start within
                    // segmentStartTimeoutMs, and the body is closed when no bytes arrive for
                    // segmentStallTimeoutMs (it used to wait forever, and 16 stalled segments took
                    // every proxy thread, so no stream played until the app restarted).
                    // v40: one retry on a network error or a 5xx/429 from the CDN, so a single hiccup
                    // doesn't reach the player as a failed segment.
                    var r: NiceResponse? = null
                    for (attempt in 0 until 2) {
                        r = try { kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(segmentStartTimeoutMs) { app.get(segUrl, headers = entry.headers, timeout = 0L) } } } catch (e: Exception) { null }
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
                    val progress = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
                    val live: NiceResponse = r
                    val stall = stallWatch.scheduleWithFixedDelay({
                        if (System.currentTimeMillis() - progress.get() > segmentStallTimeoutMs) {
                            log("hls-proxy: segment ${segUrl.substringBefore('?').takeLast(50)} stalled, closing it")
                            live.closeQuietly()
                        }
                    }, 5, 5, java.util.concurrent.TimeUnit.SECONDS)
                    try {
                        val headBuf = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(65_536)
                        var start: Int? = null
                        while (headBuf.size() < 262_144) {
                            val n = src.read(buf); if (n < 0) break
                            progress.set(System.currentTimeMillis())
                            headBuf.write(buf, 0, n)
                            if (headBuf.size() >= 1_024) { start = Hls.findMediaStart(headBuf.toByteArray()); if (start != null) break }
                        }
                        val head = headBuf.toByteArray()
                        val at = start ?: Hls.findMediaStart(head) ?: 0
                        if (at > 0 && !loggedStrip) { loggedStrip = true; log("hls-proxy: segments carry a ${at}-byte disguise (${head.take(4).joinToString("") { "%02x".format(it) }}…), stripping it") }
                        val type = if (head.size > at && head[at] == 0x47.toByte()) "video/mp2t" else "video/mp4"
                        val total = try { r.okhttpResponse.body?.contentLength() ?: -1L } catch (_: Exception) { -1L }
                        val lengthHeader = if (total > at) "Content-Length: ${total - at}\r\n" else ""
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n$lengthHeader" +
                            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                        if (method != "HEAD") {
                            out.write(head, at, head.size - at)
                            while (true) { val n = src.read(buf); if (n < 0) break; progress.set(System.currentTimeMillis()); out.write(buf, 0, n) }
                        }
                        out.flush()
                    } finally { stall.cancel(false); r.closeQuietly() }
                    return
                }
                // v33: VOD playlists (ENDLIST) don't change, so the copy the sniff page fetched
                // through the WebView is served as-is; only without one is the host asked.
                rewritten[key]?.let { body ->
                    log("hls-proxy: served ${entry.url.substringBefore('?').substringAfterLast('/')} from the page's copy")
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
        // v49: the same parser /seg/ checks with, so the two can't read a host differently.
        abs.toHttpUrlOrNull()?.host?.let { entry.hosts += it }
        return if (abs.contains('?') || entry.query.isEmpty()) abs else "$abs?${entry.query}"
    }

    @Volatile private var loggedStrip = false

    // v49: limits for a segment fetched through /seg/ (see there).
    private val segmentStartTimeoutMs = 30_000L
    private val segmentStallTimeoutMs = 45_000L
    private val stallWatch = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r -> Thread(r, "anizm-hls-stall").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }

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
