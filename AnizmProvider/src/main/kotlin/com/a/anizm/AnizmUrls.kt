package com.a.anizm

// v49: address helpers with no network, Android or CloudStream dependency, so
// AnizmProvider/tools/check-units.sh can test them with a plain kotlinc.

/** True when both addresses are on the same host (a host that can't be read never matches). */
internal fun sameHost(a: String, b: String): Boolean {
    val ha = try { java.net.URI(a).host } catch (_: Exception) { null } ?: return false
    val hb = try { java.net.URI(b).host } catch (_: Exception) { null } ?: return false
    return ha.equals(hb, ignoreCase = true)
}

/**
 * v49: [host] is [domain] or one of its subdomains. A bare endsWith also matched unrelated
 * hosts (evilanizm.net for anizm.net). [host] may be a host or a whole address.
 */
internal fun onDomain(host: String, domain: String): Boolean {
    val h = (if ("://" in host) try { java.net.URI(host).host } catch (_: Exception) { null } else host)?.lowercase() ?: return false
    val d = domain.lowercase()
    return h == d || h.endsWith(".$d")
}

/** [url] with [params] set in its query (values URL-encoded; existing keys replaced), the fragment kept last. */
internal fun withParams(url: String, params: Map<String, String>): String {
    if (params.isEmpty()) return url
    val base = url.substringBefore('?').substringBefore('#'); val frag = url.substringAfter('#', "")
    val q = LinkedHashMap<String, String>()
    url.substringBefore('#').substringAfter('?', "").split('&').filter { it.isNotEmpty() }.forEach { q[it.substringBefore('=')] = it.substringAfter('=', "") }
    params.forEach { (k, v) -> q[k] = java.net.URLEncoder.encode(v, "UTF-8") }
    return base + "?" + q.entries.joinToString("&") { "${it.key}=${it.value}" } + (if (frag.isNotEmpty()) "#$frag" else "")
}

// v50: what the log may show. "Copy recent log" hands the log to the user, who pastes it into bug
// reports; signed stream addresses and cookies in it could be replayed until they expire.
private val longQueryValueRe = Regex("""([?&][A-Za-z0-9_.\-]+=)([^&\s#"'<>]{16,})""")
private val secretHeaderNames = setOf("cookie", "set-cookie", "authorization", "x-csrf-token", "x-xsrf-token")

/** [msg] with long query values (tokens, signatures) cut to their first 4 characters and length. */
internal fun redactForLog(msg: String): String =
    if ('?' !in msg && '&' !in msg) msg
    else longQueryValueRe.replace(msg) { m -> m.groupValues[1] + m.groupValues[2].take(4) + "…(${m.groupValues[2].length})" }

/** Request headers for the log: cookies by name only, credentials hidden, everything else cut to 60 characters. */
internal fun headersForLog(h: Map<String, String>): String = h.entries.joinToString("; ") { (k, v) ->
    when {
        k.lowercase() == "cookie" -> "$k=[" + v.split(';').map { it.trim().substringBefore('=') }.filter { it.isNotEmpty() }.joinToString(",") + "]"
        k.lowercase() in secretHeaderNames -> "$k=[hidden]"
        else -> "$k=${v.take(60)}"
    }
}
