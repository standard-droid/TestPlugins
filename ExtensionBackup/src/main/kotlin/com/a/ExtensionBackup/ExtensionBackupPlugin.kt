package recloudstream

import android.content.Context
import android.os.Environment
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.io.File

@CloudstreamPlugin
class ExtensionBackupPlugin : Plugin() {
    override fun load(context: Context) {
        val source = File(context.filesDir, "Extensions")
        val dest = File(Environment.getExternalStorageDirectory(), "Cloudstream3/ExtractedExtensions")
        dest.mkdirs()

        val log = StringBuilder()
        var copied = 0

        source.walkTopDown()
            .filter { it.isFile && it.extension == "cs3" }
            .forEach { file ->
                try {
                    val target = File(dest, "${file.parentFile?.name}_${file.name}")
                    file.copyTo(target, overwrite = true)
                    copied++
                    log.appendLine("OK  ${file.name}")
                } catch (e: Exception) {
                    log.appendLine("FAIL ${file.name}: ${e.message}")
                }
            }

        File(dest, "_log.txt").writeText("Copied $copied file(s)\n\n$log")
    }
}
