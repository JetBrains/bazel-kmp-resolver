package org.jetbrains.kmp.resolver.auth

import java.net.URI

/** What a [RequestCredentials] source has to say about a request. */
internal sealed interface Authentication {
    /** The source does not apply to the request; another source may. */
    data object NotApplicable : Authentication

    /**
     * Headers to set on the request, replacing those of the same name; none authenticates nothing.
     */
    data class Headers(val headers: Map<String, List<String>>) : Authentication
}

/**
 * Source of the HTTP headers authenticating requests, applied to every request by
 * [AuthenticatingHttpClient].
 */
internal fun interface RequestCredentials {
    suspend fun authenticationFor(uri: URI): Authentication

    companion object {
        /**
         * The first source applying to a URI authenticates it, like Bazel prefers a matching
         * credential helper over netrc entries even when the helper returns no header.
         */
        fun firstApplicable(sources: List<RequestCredentials>): RequestCredentials =
            RequestCredentials { uri ->
                sources.firstNotNullOfOrNull { source ->
                    when (val authentication = source.authenticationFor(uri)) {
                        Authentication.NotApplicable -> null
                        is Authentication.Headers -> authentication
                    }
                } ?: Authentication.NotApplicable
            }
    }
}
