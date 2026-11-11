package com.a.rezeroizle

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

// BasePlugin, not Plugin: no Android Context needed, so the plugin also builds as a
// cross-platform .jar (isCrossPlatform in build.gradle.kts).
@CloudstreamPlugin
class ReZeroIzlePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ReZeroIzleProvider())
    }
}
