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

// Small types shared by the provider's files (they were nested in AnizmProvider).

internal class PlayerLookupBlocked(val code: Int) : Exception("blocked: http $code")

// v7: why Aincrad links doubled (2 → 4) when an episode was loaded again: getVideo hands
// out a fresh signed URL (…master.m3u8?md5=…&expires=…) on every call, so the second
// loadLinks emitted the "same" stream under a different URL — and, since the first load
// had no size, a different name too. CloudStream treats that as a new link. Caching the
// signed URL (until shortly before it expires) and the size estimate per stream makes a
// repeat load emit byte-identical links, which the app de-duplicates.
// viaChrome: getVideo was answered through Chrome's network stack, so the playlist must be too (2026-11).
internal data class AincradSource(val videoSource: String, val securedLink: String, val validUntil: Long, val cookie: String = "", val viaChrome: Boolean = false)

internal data class VidInfo(val numId: String, val name: String, val fansub: String, val epId: String? = null)

// v33: one run per episode at a time. CloudStream calls loadLinks twice for the same
// episode ~0.5s apart (preload + open); the device logs (2026-09-26) show both calls doing
// everything — two WebView sniffs per Sistenn source, two Drive resolves (each one counting
// against the file's download quota), two Beta size estimates. The second call now joins
// the first: it gets every link found so far at once, then the rest as they arrive.
internal class LinkRun {
    val links = ArrayList<ExtractorLink>()
    val subscribers = ArrayList<(ExtractorLink) -> Unit>()
    // null = the run was cancelled before finishing (a joined caller then runs its own)
    val done = kotlinx.coroutines.CompletableDeferred<Boolean?>()
}

internal class StreamInfo(val sizes: Map<Int, Long>, val joins: Map<Int, List<Int>>,
                         val declared: Boolean = false, val joinsChecked: Boolean = false)

internal class SegRead(val size: Long?, val pts: Double?)

internal class Measured(val media: Hls.Media, val sizes: Map<Int, Long>, val pts: Map<Int, Double>, val exact: Boolean = false) {
    val bytes: Long? get() = if (exact) sizes.values.sum() else Hls.estimateBytes(media.segs, sizes)
}

// ── Sistenn family: the resolver page (v48) ──────────────────────────────
// Seen on the live site 2026-11-10: sistenn.uns.bio/#<id> and anizm.rpmvid.com/#<id> are no
// longer the player itself but a 4.7 KB page that asks /api/public/resolve?file1=<id> and, when
// the answer has a url, goes there (location.replace). For an id it doesn't know it answers
// 404 {"ok":false,"error":"not found"} and the page shows "The system is under maintenance".
// The old /api/v1/info and /api/v1/video are gone from these hosts (404 "Cannot GET").
// So the resolver is asked first: no url means no video, at once, with no page load in the
// in-app browser; a url is classified like any other player address. A host that doesn't
// have the resolver ("Cannot GET") still runs the old player and is handled as before.
internal sealed class SistennResolved {
    class Url(val url: String) : SistennResolved()
    object Gone : SistennResolved()
    object NotOffered : SistennResolved()
}

// ── Sistenn API, no WebView (v37) ────────────────────────────────────────
// Worked out from the player's own bundle (index-DqFBtoPY.js, 2026-09-26) and checked against
// captured responses: /api/v1/info and /api/v1/video answer hex text that is AES-128-CBC with
// a fixed key and IV the script assembles at runtime ("kiemtienmua911ca" / "1234567890oiuytr";
// both depend only on location.protocol and the '#' of the hash). /api/v1/player?t= takes the
// same encryption of a small JSON {website, playing, sessionId, userId, playerId, videoId,
// country, platform, browser, os} and answers {k, kx}. The video answer lists the stream as
// cfNative (a Sistenn-served playlist carrying its own ~3.5 h token — made for players that
// cannot add tokens, i.e. exactly this one), cf / source (the /v4/ hosts, k&kx appended from
// "pk"), hlsVideoTiktok / hlsVideoGoogle (image-disguised segments), ordered and adjusted by
// streamingConfig. When the site is full it answers no sources plus delivery.capacityToken.
internal enum class SistennResult { EMITTED, CAPACITY, UNAVAILABLE }

internal data class SistennSource(val kind: String, val url: String, val strip: Boolean)

internal class SistennChecked(val tokenQuery: String, val variantCount: Int, val loaded: List<Pair<Variant, String>>)

// GDrive's `confirm=t` is not a real token — it only happens to work when Google skips
// the virus-scan interstitial (small/short files). Anything past that threshold serves
// an HTML confirmation page instead of video, which the player then fails to open
// ("setDataSource failed"). Real fix: fetch that page, scrape the actual `confirm` +
// `uuid` values Google issues, and replay them — this is Google's own two-step flow,
// not a workaround. Every request uses a Range so a multi-GB file is never pulled
// through just to read its headers.
//
// v4: also works out size + resolution, so Drive links show "[1.4GB]" and a real
// quality instead of "unknown" (which never counted toward the minimum-quality stop).
// In order of cost, each step only if the previous one didn't answer:
//   size:    Content-Range total → Content-Length (only on a non-ranged 200) →
//            the "(1.4G)" text on Google's interstitial page
//   quality: resolution in the file name (Content-Disposition, or the interstitial's
//            file link) → the MP4 header in the bytes we already downloaded → one more
//            64KB ranged read at the moov box, for files whose header sits at the end.
// Checked on the live site: file names are hit and miss ("…1080p.WEB-DL…mp4" vs
// "[GachaFlexSubs] Sousou no Frieren S1E1.mp4"), hence the header fallback.
internal data class GDriveResolution(val url: String, val headers: Map<String, String>, val sizeBytes: Long?, val height: Int?)

// ── Minimal MP4 reader: video track height from tkhd, or where moov is ───────
internal sealed class Mp4Probe {
    data class Height(val height: Int) : Mp4Probe()
    data class MoovAt(val offset: Long, val size: Long) : Mp4Probe()
    object Unknown : Mp4Probe()
}

/**
 * v32: the device log (2026-09-26) had a Drive link resolve fine here (form submit ->
 * 830MB, 1080p) and then fail in ExoPlayer 22s later with UnrecognizedInputFormat, i.e. the
 * same URL no longer returned the video. Ask for it once more the way the player will (from
 * byte 0, same headers) and only list it if that is still the file. Logs what Google sent
 * otherwise, which says whether the confirm/uuid link is single-use, quota-limited, etc.
 */
// ── Drive streaming (v35) ────────────────────────────────────────────────
// What drive.google.com/file/d/<id>/preview itself does (browser capture, 2026-09-26):
//   GET content-workspacevideo-pa.googleapis.com/v1/drive/media/<id>/playback?key=<AIza…>
// Cookie-free; 200 with mediaStreamingData.formatStreamingData.progressiveTranscodes =
// muxed MP4s (itag 18/22/37 → 360/720/1080p) on *.c.drive.google.com/videoplayback, valid
// 3 h and signed to the requesting IP (the phone, same as the player). Without key → 403;
// a file over its signed-out play limit → 429. The key is Drive's public web key: it is read
// from the preview page (every AIza… string there is tried once) and then remembered.
internal data class DriveStream(val height: Int, val url: String, val sizeBytes: Long?)
