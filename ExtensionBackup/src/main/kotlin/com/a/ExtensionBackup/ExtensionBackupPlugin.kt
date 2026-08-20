package recloudstream

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.io.File

@CloudstreamPlugin
class ExtensionBackupPlugin : Plugin() {

    override fun load(context: Context) {
        // No file I/O here anymore — just register the settings entry point.
        // This is what makes the settings gear icon show up next to the plugin.
        openSettings = { ctx -> showBackupDialog(ctx) }
    }

    private fun showBackupDialog(context: Context) {
        val source = File(context.filesDir, "Extensions")
        val files = source.walkTopDown()
            .filter { it.isFile && it.extension == "cs3" }
            .sortedBy { it.nameWithoutExtension }
            .toList()

        if (files.isEmpty()) {
            Toast.makeText(context, "No installed extensions found", Toast.LENGTH_LONG).show()
            return
        }

        val labels = files.map { it.nameWithoutExtension }.toTypedArray()
        val checked = BooleanArray(files.size) { true } // preselect all

        AlertDialog.Builder(context)
            .setTitle("Back up extensions")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Export") { _, _ ->
                exportSelected(context, files.filterIndexed { i, _ -> checked[i] })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportSelected(context: Context, files: List<File>) {
        if (files.isEmpty()) {
            Toast.makeText(context, "Nothing selected", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Toast.makeText(
                context,
                "Grant CloudStream \"All files access\" first, then try again",
                Toast.LENGTH_LONG
            ).show()
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // A few OEM builds don't support the per-app deep link.
                // Settings > Apps > CloudStream > Permissions > All files access still works.
            }
            return
        }

        val dest = File(Environment.getExternalStorageDirectory(), "Cloudstream3/ExtractedExtensions")
        dest.mkdirs()

        var copied = 0
        val log = StringBuilder()

        files.forEach { file ->
            try {
                val target = File(dest, "${file.parentFile?.name}_${file.name}")
                file.copyTo(target, overwrite = true)
                copied++
                log.appendLine("OK  ${file.name}")
            } catch (e: Exception) {
                log.appendLine("FAIL ${file.name}: ${e.message}")
            }
        }

        File(dest, "_log.txt").writeText("Copied $copied of ${files.size} file(s)\n\n$log")
        Toast.makeText(
            context,
            "Backed up $copied of ${files.size} extension(s) to Cloudstream3/ExtractedExtensions",
            Toast.LENGTH_LONG
        ).show()
    }
}
