package com.a.anizm

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * User-facing settings, shown from CloudStream's Extensions screen via Plugin.openSettings.
 *
 * Stored in plain SharedPreferences (not CloudStream's getKey/setKey) so this doesn't depend
 * on app-internal APIs that have moved between releases. The UI is built in code, so the
 * plugin needs no XML resources (requiresResources stays false).
 *
 * The provider reads these on every loadLinks call, so changes apply to the next episode
 * opened — no app restart.
 */
class AnizmSettings(internal val prefs: SharedPreferences) {

    /** A player family, matched against the site's player button label (case-insensitive). */
    data class SourceGroup(val key: String, val label: String, val keywords: List<String>)

    companion object {
        const val PREFS_NAME = "anizm_settings"
        /** Kept by "Defaults": state the extension learned, not choices the user made. */
        val KEPT_ON_RESET = setOf("site_host", "site_warnings")
        const val OTHER = "other"

        /**
         * Order matters twice: first keyword match wins when classifying a label, and list
         * position is the lazy-resolve priority (earlier = tried first). "other" must be last.
         */
        val GROUPS = listOf(
            SourceGroup("aincrad", "Aincrad", listOf("aincrad")),
            SourceGroup("beta", "Beta Player", listOf("beta")),
            SourceGroup("gdrive", "Google Drive", listOf("gdrive", "google", "drive")),
            SourceGroup("sistenn", "Sistenn", listOf("sistenn")),
            SourceGroup("voe", "Voe", listOf("voe")),
            SourceGroup("sibnet", "Sibnet", listOf("sibnet")),
            SourceGroup("okru", "Odnoklassniki (ok.ru)", listOf("ok.ru", "okru", "odnoklassniki")),
            SourceGroup("vidmoly", "Vidmoly", listOf("vidmoly")),
            SourceGroup("dood", "DoodStream", listOf("dood")),
            SourceGroup("mp4upload", "Mp4Upload", listOf("mp4upload")),
            SourceGroup("uqload", "UQload", listOf("uqload")),
            SourceGroup("sendvid", "SendVid", listOf("sendvid")),
            SourceGroup("hdvid", "HDVid", listOf("hdvid")),
            SourceGroup("abyss", "Abyss", listOf("abyss")),
            SourceGroup(OTHER, "Other players (LuluStream, FireStream, UpBolt…)", emptyList()),
        )


        fun showDialog(context: Context, settings: AnizmSettings) {
            val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt() }

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            fun header(text: String) = TextView(context).apply {
                this.text = text
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, dp(14), 0, dp(2))
            }.also { root.addView(it) }
            fun note(text: String) = TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                alpha = 0.75f
                setPadding(0, 0, 0, dp(4))
            }.also { root.addView(it) }
            fun check(text: String, checked: Boolean) = CheckBox(context).apply {
                this.text = text
                isChecked = checked
            }.also { root.addView(it) }

            header("Sources")
            note("Tried top to bottom. Use the arrows to reorder, the checkbox to turn a source off. Matched by the player's button name on anizm.")
            // v18: the try order is editable. Arrows rather than drag-and-drop so it also
            // works with a TV remote's D-pad.
            val order = settings.sourceOrder().toMutableList()
            val enabled = HashMap<String, Boolean>()
            for (g in GROUPS) enabled[g.key] = settings.isSourceEnabled(g.key)
            val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            root.addView(listBox)

            fun renderList() {
                listBox.removeAllViews()
                order.forEachIndexed { i, key ->
                    val group = GROUPS.firstOrNull { it.key == key } ?: return@forEachIndexed
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    val box = CheckBox(context).apply {
                        text = "${i + 1}. ${group.label}"
                        isChecked = enabled[key] ?: true
                        setOnCheckedChangeListener { _, v -> enabled[key] = v }
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    row.addView(box)
                    fun arrow(label: String, enabledArrow: Boolean, move: () -> Unit) = Button(context).apply {
                        text = label
                        isEnabled = enabledArrow
                        minWidth = dp(44); minimumWidth = dp(44)
                        setOnClickListener { move(); renderList() }
                    }
                    row.addView(arrow("\u25B2", i > 0) {
                        val tmp = order[i - 1]; order[i - 1] = order[i]; order[i] = tmp
                    })
                    row.addView(arrow("\u25BC", i < order.size - 1) {
                        val tmp = order[i + 1]; order[i + 1] = order[i]; order[i] = tmp
                    })
                    listBox.addView(row)
                }
            }
            renderList()
            val lastResortBox = check("If no enabled source works, also try disabled ones", settings.tryDisabledAsLastResort)

            header("Loading")
            val lazyBox = check("Lazy loading: stop once enough sources work", settings.lazyResolve)
            val targetRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(32), 0, 0, 0)
            }
            targetRow.addView(TextView(context).apply { text = "Working sources needed: " })
            // Typed in, not picked from a list: any number works, and it is one click on a TV remote.
            val targetField = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(settings.lazyTargetSources.toString())
                width = dp(90)
                setSelectAllOnFocus(true)
            }
            targetRow.addView(targetField)
            root.addView(targetRow)
            note("Fewer = fewer requests to the site and faster start, but fewer choices in the source list. Turn lazy loading off to always list every source.")
            val minQRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(32), 0, 0, 0)
            }
            minQRow.addView(TextView(context).apply { text = "Minimum height that counts: " })
            val minQField = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(settings.lazyMinQuality.toString())
                width = dp(90)
                setSelectAllOnFocus(true)
            }
            minQRow.addView(minQField)
            minQRow.addView(TextView(context).apply { text = " p  (0 = any)" })
            root.addView(minQRow)
            note("Lower-quality sources are still listed, they just don't stop the search. If nothing reaches this quality, everything that was found is kept.")
            fun syncTargetRow() {
                val on = lazyBox.isChecked
                targetField.isEnabled = on; minQField.isEnabled = on
                targetRow.alpha = if (on) 1f else 0.4f; minQRow.alpha = targetRow.alpha
            }
            syncTargetRow()
            lazyBox.setOnCheckedChangeListener { _, _ -> syncTargetRow() }

            header("Connection")
            note("anizm sometimes refuses this extension's direct player lookups, and it falls back to the in-app browser engine (slower, but works). This test shows what the site answers.")
            val testBtn = Button(context).apply {
                text = "Run connection test"
                setOnClickListener {
                    isEnabled = false; text = "Testing…"
                    CoroutineScope(Dispatchers.IO).launch {
                        val lines = try { AnizmProvider.instance?.runConnectionTest() ?: listOf("Extension not loaded yet.") }
                        catch (e: Exception) { listOf("Test failed: ${e.message}") }
                        withContext(Dispatchers.Main) {
                            isEnabled = true; text = "Run connection test"
                            AlertDialog.Builder(context)
                                .setTitle("Connection test")
                                .setMessage(lines.joinToString("\n\n"))
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            }
            root.addView(testBtn)

            header("Site check")
            note("Runs every step (home, search, anime page, episodes, fansubs, sources, player) on Sousou no Frieren and shows what each one found. If the site changes again, copy this report and send it along.")
            val siteBtn = Button(context).apply {
                text = "Run site check"
                setOnClickListener {
                    isEnabled = false; text = "Checking…"
                    CoroutineScope(Dispatchers.IO).launch {
                        val lines = try { AnizmProvider.instance?.runSiteCheck() ?: listOf("Extension not loaded yet.") }
                        catch (e: Exception) { listOf("Check failed: ${e.message}") }
                        val report = lines.joinToString("\n")
                        withContext(Dispatchers.Main) {
                            isEnabled = true; text = "Run site check"
                            AlertDialog.Builder(context)
                                .setTitle("Site check")
                                .setMessage(report)
                                .setPositiveButton("OK", null)
                                .setNeutralButton("Copy") { _, _ ->
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                        ?.setPrimaryClip(ClipData.newPlainText("Anizm site check", report))
                                }
                                .show()
                        }
                    }
                }
            }
            root.addView(siteBtn)
            // v48: what the extension noticed about the site on its own (a player that moved, a page
            // read through a fallback), and the recent log, without a computer and adb.
            val warnings = settings.recentWarnings()
            if (warnings.isNotEmpty()) {
                header("Recent site warnings")
                note(warnings.takeLast(8).joinToString("\n"))
            }
            fun copy(label: String, text: String) =
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText(label, text))
            root.addView(Button(context).apply {
                text = "Copy recent log"
                setOnClickListener {
                    val log = AnizmProvider.instance?.recentLogText().orEmpty()
                    copy("Anizm log", (if (warnings.isEmpty()) "" else "Site warnings:\n" + warnings.joinToString("\n") + "\n\n") + log)
                    text = if (log.isEmpty()) "Nothing logged yet" else "Copied (${log.count { it == '\n' } + 1} lines)"
                }
            })
            root.addView(Button(context).apply {
                text = "Clear saved data"
                setOnClickListener {
                    AnizmProvider.instance?.clearSavedData()
                    settings.clearWarnings()
                    text = "Cleared"
                }
            })
            note("Saved data: file sizes, where each player id points and which player pages never worked. It only saves requests; clearing it is always safe.")

            header("Extras")
            val sniffBox = check("Use the in-app browser for players CloudStream can't read", settings.browserSniff)
            note("Needed for Sistenn and similar (their stream link is built by the page's own JavaScript). Costs a few seconds per source, and only runs after the normal method finds nothing.")
            val deferBox = check("Try in-app-browser sources last (faster start)", settings.deferBrowserSources)
            note("On: with lazy loading, Sistenn and similar wait until the other sources have been tried, and are skipped if those already reach the target. Off: the order above is followed exactly.")
            val sizeBox = check("Show estimated file size for Aincrad, Beta Player and Sistenn", settings.estimateSizes)
            note("Measured from a few small reads spread over the video, the first time a video is opened; the result is then kept. Google Drive sizes are exact and need no extra requests.")
            val accurateBox = check("More accurate sizes (about twice the reads)", settings.accurateSizes)
            note("On: about 35 small reads per video (once), usually within 3 % of the real size. Off: about half as many, usually within 5 %.")
            val creditsBox = check("Show who made each source (translator, encoder, uploader)", settings.showCredits)
            note("Added to the source's name, e.g. \"Net-Rip - Beta Player · Enc/Up: Rinrintan\". Costs one small request per source, the same one the site makes when a player button is pressed.")
            val driveOriginalBox = check("Google Drive: also list the original uploaded file", settings.driveOriginal)
            note("Google's own streams (1080p, 720p, 360p) are re-encodes, often a third of the upload's size. On: the uploaded file is listed too, as \"… Original\", with its real size. Costs a few small requests per Drive source, and Google blocks it for a few hours when the file's download quota runs out (the streams still play).")
            val lockBox = check("Aincrad: always play the best quality (no automatic switching)", settings.lockTopQuality)
            note("On: Aincrad plays only its top quality (usually 1080p), so the player never drops to a lower one; on a slow connection it buffers instead. Off: the player switches by connection speed, using Aincrad's quality labels, which are often wrong for 1080p.")

            val scroll = ScrollView(context).apply { addView(root) }

            AlertDialog.Builder(context)
                .setTitle("Anizm settings")
                .setView(scroll)
                .setPositiveButton("Save") { _, _ ->
                    val e = settings.prefs.edit()
                    for ((key, on) in enabled) e.putBoolean("src_$key", on)
                    e.putString("src_order", order.joinToString(","))
                    e.putBoolean("try_disabled_last_resort", lastResortBox.isChecked)
                    e.putBoolean("lazy_resolve", lazyBox.isChecked)
                    // Blank or nonsense falls back to the current value rather than to 0, which
                    // would make lazy loading stop before anything had been found.
                    e.putInt("lazy_target", targetField.text.toString().trim().toIntOrNull()?.coerceIn(1, 99) ?: settings.lazyTargetSources)
                    e.putInt("lazy_min_quality", minQField.text.toString().trim().toIntOrNull()?.coerceIn(0, 4320) ?: settings.lazyMinQuality)
                    e.putBoolean("estimate_sizes", sizeBox.isChecked)
                    e.putBoolean("lock_top_quality", lockBox.isChecked)
                    e.putBoolean("accurate_sizes", accurateBox.isChecked)
                    e.putBoolean("show_credits", creditsBox.isChecked)
                    e.putBoolean("drive_original", driveOriginalBox.isChecked)
                    e.putBoolean("browser_sniff", sniffBox.isChecked)
                    e.putBoolean("defer_browser_sources", deferBox.isChecked)
                    e.apply()
                }
                // v50: settings only. The site's current domain and the site-change warnings are
                // state, not settings; clearing them sent the app back to an old, possibly blocked domain.
                .setNeutralButton("Defaults") { _, _ ->
                    val e = settings.prefs.edit()
                    settings.prefs.all.keys.filter { it !in AnizmSettings.KEPT_ON_RESET }.forEach { e.remove(it) }
                    e.apply()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    fun isSourceEnabled(key: String) = prefs.getBoolean("src_$key", true)

    /**
     * The user's try order. Anything saved that no longer exists is dropped, and any group
     * added in a later version is appended, so an old saved order never hides a new source.
     */
    fun sourceOrder(): List<String> {
        val saved = prefs.getString("src_order", null)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: return GROUPS.map { it.key }
        val known = GROUPS.map { it.key }
        val kept = saved.filter { it in known }.distinct()
        return kept + known.filter { it !in kept }
    }
    val tryDisabledAsLastResort get() = prefs.getBoolean("try_disabled_last_resort", true)
    val lazyResolve get() = prefs.getBoolean("lazy_resolve", true)
    val lazyTargetSources get() = prefs.getInt("lazy_target", 2).coerceAtLeast(1)
    val estimateSizes get() = prefs.getBoolean("estimate_sizes", true)
    /** v46: Aincrad's split-audio masters are served with only their top variant. */
    val lockTopQuality get() = prefs.getBoolean("lock_top_quality", true)
    val browserSniff get() = prefs.getBoolean("browser_sniff", true)
    /** Put browser-only sources (Sistenn…) after the rest while lazy loading. */
    val deferBrowserSources get() = prefs.getBoolean("defer_browser_sources", true)
    /** v40: the site's current host after a domain move (null = the built-in anizm.net). */
    var siteHost: String?
        get() = prefs.getString("site_host", null)
        set(v) { prefs.edit().putString("site_host", v).apply() }
    /** v48: 16 size samples per video instead of 8 (and 4 instead of 2 for each lower quality). */
    val accurateSizes get() = prefs.getBoolean("accurate_sizes", true)
    /** v48: translator, encoder and uploader in each source's name. */
    val showCredits get() = prefs.getBoolean("show_credits", true)
    val driveOriginal get() = prefs.getBoolean("drive_original", true)

    /** v48: the last 20 "the site changed" warnings, newest last, each with its date. */
    fun recentWarnings(): List<String> = prefs.getString("site_warnings", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    @Synchronized fun addWarning(msg: String) {
        val line = msg.replace('\n', ' ').take(240)
        val old = recentWarnings()
        // The same warning again (every episode, while the site stays changed) only moves its date.
        val kept = old.filter { it.substringAfter("  ") != line }
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
        prefs.edit().putString("site_warnings", (kept + "$stamp  $line").takeLast(20).joinToString("\n")).apply()
    }
    fun clearWarnings() { prefs.edit().remove("site_warnings").apply() }
    /** 0 = any quality counts toward the lazy target. */
    val lazyMinQuality get() = prefs.getInt("lazy_min_quality", 1080)

    /** Which group a player button label belongs to. */
    fun groupOf(label: String): SourceGroup {
        val l = label.lowercase()
        return GROUPS.firstOrNull { g -> g.keywords.any { l.contains(it) } } ?: GROUPS.last()
    }
    fun priorityOf(label: String): Int {
        val idx = sourceOrder().indexOf(groupOf(label).key)
        return if (idx >= 0) idx else GROUPS.size
    }
    fun isEnabled(label: String) = isSourceEnabled(groupOf(label).key)
    fun isRecognised(label: String) = groupOf(label).key != OTHER
}
