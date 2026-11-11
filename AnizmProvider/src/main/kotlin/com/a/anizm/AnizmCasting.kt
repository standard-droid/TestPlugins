package com.a.anizm

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// No network, Android or CloudStream dependency, so tools/check-units.sh tests it with a plain kotlinc.

/**
 * v49: marks a loadLinks run made for casting. A Chromecast (or any cast receiver) fetches the
 * stream itself, so an address on this phone's 127.0.0.1 means nothing to it: under this mark
 * the players skip the local server where a direct address works too, and links that only work
 * through it are left out. Carried in the coroutine context, so every step of the run sees it.
 *
 * v51: a class with a companion Key. v49/v50 declared `object CastingLoad :
 * AbstractCoroutineContextElement(CastingLoad)`, which passes the object as its own key while it
 * is still being created, i.e. null: the first isCastingLoad() threw ExceptionInInitializerError
 * and took the whole loadLinks run down with it (no Aincrad links, often no links at all).
 */
internal class CastingLoad : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CastingLoad>
}

internal suspend fun isCastingLoad(): Boolean = kotlin.coroutines.coroutineContext[CastingLoad] != null

internal fun isLocalLink(url: String): Boolean = url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")
