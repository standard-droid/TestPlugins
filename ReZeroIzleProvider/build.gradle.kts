// use an integer for version numbers
version = 19

cloudstream {
    // All of the following are optional, remove any line you don't need
    description = "Re:Zero kara Hajimeru Isekai Seikatsu — Türkçe altyazılı, Google Drive üzerinden."
    authors = listOf("a")

    /**
     * Status of the plugin.
     * 0: down
     * 1: ok
     * 2: slow
     * 3: beta only
     */
    status = 1 // will be 3 if not main

    tvTypes = listOf("Anime", "OVA")

    iconUrl = "https://rezeroizle.com/images/icon.png"

    language = "tr"

    // No Android imports (BasePlugin, com.lagradost.api.Log), so :make also builds a .jar for
    // the cross-platform app; ensureJarCompatibility fails the build if an android.* import slips in.
    isCrossPlatform = true
}
