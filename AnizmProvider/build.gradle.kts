version = 52

cloudstream {
    language    = "tr"
    description = "Anizm (anizm.net) - Türkçe altyazılı anime"
    authors     = listOf(" ")

    /**
     * Status:
     *  0 = Down
     *  1 = Working
     *  2 = Slow
     *  3 = Beta only
     */
    status  = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")

    // PNG, not the site's favicon.ico: anizm.net answers non-browser requests with a Cloudflare
    // 403, and an .ico is a risk for Android's image decoder. Google's favicon service serves the
    // site's fox logo as a 128x128 PNG.
    iconUrl = "https://www.google.com/s2/favicons?domain=anizm.net&sz=128"
}
