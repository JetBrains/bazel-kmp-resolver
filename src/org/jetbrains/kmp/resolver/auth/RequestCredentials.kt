package org.jetbrains.kmp.resolver.auth

import java.net.URI

/**
 * Source of the HTTP headers authenticating requests, applied to every request by
 * [AuthenticatingHttpClient].
 */
internal fun interface RequestCredentials {
    /**
     * Headers to set on a request of [uri], replacing those of the same name, or null when this
     * source does not apply to the URI at all, in which case another source may. An empty map
     * applies and authenticates nothing.
     */
    suspend fun headersFor(uri: URI): Map<String, List<String>>?
}
