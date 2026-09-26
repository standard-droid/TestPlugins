package com.a.rezeroizle

/**
 * Reads Google Drive IDs out of the site's seasons-data.js without running it.
 *
 * The file holds every season in one object:
 *   var SEASON_CONFIGS = {
 *     1: { episodeDriveIds: ["id", ...], episodeDriveIds2: { 14: "id" },
 *          episodeDriveIds3: { }, specials: [{ extraType: "snow", driveId: "id", ... }] },
 *     ...
 *   }
 * Lookups go by season and episode number (from the page URL), never by position in the
 * whole file: the header comment alone contains two quoted placeholder strings.
 */
object SeasonsData {

    private val FILE_ID_RE  = Regex("""/file/d/([^/?#]+)""")
    private val PARAM_ID_RE = Regex("""[?&]id=([^&#]+)""")
    private val OBJ_ENTRY_RE = Regex("""(\d+)\s*:\s*(["'`])(.*?)\2""")

    /** Drive IDs for an episode, primary first; empty when the episode has none yet. */
    fun episodeIds(js: String, season: Int, episode: Int): List<String> {
        val block = seasonBlock(stripComments(js), season) ?: return emptyList()
        val primary = field(block, "episodeDriveIds", '[')
            ?.let { arrayStrings(it).getOrNull(episode - 1) }
        val second = field(block, "episodeDriveIds2", '{')?.let { objectStrings(it)[episode] }
        val third  = field(block, "episodeDriveIds3", '{')?.let { objectStrings(it)[episode] }
        return listOfNotNull(primary, second, third).map(::normalize).filter { it.isNotBlank() }.distinct()
    }

    /**
     * Drive IDs for a season's special (OVA). [slug] is the page name, e.g. "memory-snow";
     * it is matched against each special's extraType and title, and a season with a
     * single special uses that one.
     */
    fun specialIds(js: String, season: Int, slug: String): List<String> {
        val block = seasonBlock(stripComments(js), season) ?: return emptyList()
        val specials = field(block, "specials", '[')?.let { topLevelObjects(it) }.orEmpty()
        if (specials.isEmpty()) return emptyList()

        val key = slug.lowercase().filter { it.isLetterOrDigit() }
        val special = specials.firstOrNull { s ->
            listOf("extraType", "title").any { f ->
                val v = stringField(s, f)?.lowercase()?.filter { it.isLetterOrDigit() }
                !v.isNullOrBlank() && (key.contains(v) || v.contains(key))
            }
        } ?: specials.singleOrNull() ?: return emptyList()

        return listOf("driveId", "driveId2", "driveId3")
            .mapNotNull { stringField(special, it) }
            .map(::normalize).filter { it.isNotBlank() }.distinct()
    }

    /** Same rules as the site's getDriveId(): share links and ?id= links reduce to the ID. */
    fun normalize(raw: String): String {
        val s = raw.trim()
        FILE_ID_RE.find(s)?.let { return it.groupValues[1] }
        PARAM_ID_RE.find(s)?.let { return it.groupValues[1] }
        return s
    }

    // ── JS scanning ──────────────────────────────────────────────────────────

    /** Removes // and /* */ comments outside string literals. */
    fun stripComments(js: String): String {
        val out = StringBuilder(js.length)
        var i = 0
        var quote: Char? = null
        while (i < js.length) {
            val c = js[i]
            if (quote != null) {
                out.append(c)
                if (c == '\\' && i + 1 < js.length) { out.append(js[i + 1]); i += 2; continue }
                if (c == quote) quote = null
                i++
                continue
            }
            when {
                c == '"' || c == '\'' || c == '`' -> { quote = c; out.append(c); i++ }
                js.startsWith("//", i) -> {
                    while (i < js.length && js[i] != '\n') i++
                }
                js.startsWith("/*", i) -> {
                    val end = js.indexOf("*/", i + 2)
                    i = if (end < 0) js.length else end + 2
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** Index just past the bracket matching the one at [open], skipping string literals. */
    private fun matchEnd(s: String, open: Int): Int {
        var depth = 0
        var i = open
        var quote: Char? = null
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                if (c == '\\') { i += 2; continue }
                if (c == quote) quote = null
            } else when (c) {
                '"', '\'', '`' -> quote = c
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return -1
    }

    /** The `{ ... }` for [season] inside SEASON_CONFIGS, found only at the object's top level. */
    private fun seasonBlock(js: String, season: Int): String? {
        val start = Regex("""SEASON_CONFIGS\s*=\s*\{""").find(js) ?: return null
        val open = start.range.last
        val end = matchEnd(js, open).takeIf { it > 0 } ?: return null
        val body = js.substring(open + 1, end - 1)
        val keyRe = Regex("""^\s*["']?$season["']?\s*:\s*\{""")

        var depth = 0
        var quote: Char? = null
        var i = 0
        var entryStart = 0
        while (i < body.length) {
            val c = body[i]
            if (quote != null) {
                if (c == '\\') { i += 2; continue }
                if (c == quote) quote = null
            } else when (c) {
                '"', '\'', '`' -> quote = c
                '{', '[' -> {
                    if (depth == 0 && c == '{' && keyRe.containsMatchIn(body.substring(entryStart, i + 1))) {
                        val e = matchEnd(body, i)
                        return if (e > 0) body.substring(i, e) else null
                    }
                    depth++
                }
                '}', ']' -> depth--
                ',' -> if (depth == 0) entryStart = i + 1
            }
            i++
        }
        return null
    }

    /** The bracketed value of `name:` in [block] (first match, [open] is '[' or '{'). */
    private fun field(block: String, name: String, open: Char): String? {
        val m = Regex("""(?<![\w$])$name\s*:\s*""" + Regex.escape(open.toString())).find(block) ?: return null
        val e = matchEnd(block, m.range.last)
        return if (e > 0) block.substring(m.range.last, e) else null
    }

    private fun stringField(obj: String, name: String): String? =
        Regex("""(?<![\w$])$name\s*:\s*(["'`])(.*?)\1""").find(obj)?.groupValues?.get(2)

    /** Elements of a `[...]` literal in order; anything that isn't a string becomes "". */
    private fun arrayStrings(arr: String): List<String> {
        val items = mutableListOf<String>()
        val inner = arr.substring(1, arr.length - 1)
        var depth = 0
        var quote: Char? = null
        var cur = StringBuilder()
        var i = 0
        fun flush() {
            val t = cur.toString().trim()
            val q = t.firstOrNull()
            items.add(if (q != null && q in "\"'`" && t.length >= 2 && t.last() == q) t.substring(1, t.length - 1) else "")
            cur = StringBuilder()
        }
        while (i < inner.length) {
            val c = inner[i]
            if (quote != null) {
                cur.append(c)
                if (c == '\\' && i + 1 < inner.length) { cur.append(inner[i + 1]); i += 2; continue }
                if (c == quote) quote = null
            } else when (c) {
                '"', '\'', '`' -> { quote = c; cur.append(c) }
                '{', '[' -> { depth++; cur.append(c) }
                '}', ']' -> { depth--; cur.append(c) }
                ',' -> if (depth == 0) flush() else cur.append(c)
                else -> cur.append(c)
            }
            i++
        }
        // A trailing comma leaves only whitespace behind; that is not an element.
        if (cur.isNotBlank()) flush()
        return items
    }

    /** `{ 14: "id", ... }` → map of episode number to value. */
    private fun objectStrings(obj: String): Map<Int, String> =
        OBJ_ENTRY_RE.findAll(obj).associate { it.groupValues[1].toInt() to it.groupValues[3] }

    /** The `{...}` elements at the top level of a `[...]` literal. */
    private fun topLevelObjects(arr: String): List<String> {
        val out = mutableListOf<String>()
        var i = 1
        while (i < arr.length - 1) {
            val c = arr[i]
            if (c == '"' || c == '\'' || c == '`') {
                val close = arr.indexOf(c, i + 1)
                i = if (close < 0) arr.length else close + 1
                continue
            }
            if (c == '{' || c == '[') {
                val e = matchEnd(arr, i)
                if (e < 0) break
                if (c == '{') out.add(arr.substring(i, e))
                i = e
                continue
            }
            i++
        }
        return out
    }
}
