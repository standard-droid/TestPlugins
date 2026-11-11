// Test-only stand-in for CloudStream's com.lagradost.cloudstream3.utils.AppUtils, so check-units.sh
// can compile the plugin's pure files without the CloudStream library. Same signatures as upstream,
// and the app's own Json settings (library MainAPI.kt: encodeDefaults, explicitNulls = false,
// ignoreUnknownKeys). Upstream additionally falls back to Jackson when kotlinx can't decode a value;
// the extension doesn't rely on that (tryParseJson returns null either way). Never shipped.
package com.lagradost.cloudstream3.utils

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

val shimJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

object AppUtils {
    @OptIn(InternalSerializationApi::class)
    fun Any.toJson(): String {
        if (this is String) return this
        @Suppress("UNCHECKED_CAST")
        return shimJson.encodeToString(this::class.serializer() as KSerializer<Any>, this)
    }

    inline fun <reified T : Any> parseJson(value: String): T = shimJson.decodeFromString(serializer<T>(), value)

    inline fun <reified T> tryParseJson(value: String?): T? =
        try { shimJson.decodeFromString(serializer<T>(), value ?: return null) } catch (_: Exception) { null }
}
