package org.jetbrains.kmp.resolver

import java.io.IOException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.net.http.HttpResponse.PushPromiseHandler
import java.net.http.WebSocket
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

/**
 * An [HttpClient] that attaches the caller-resolved credentials of [credentials] to every request it sends, before
 * delegating to [delegate].
 *
 * This exists because the Amper dependency resolution can only send HTTP Basic auth (it builds an `Authorization`
 * header out of `MavenRepository.userName`/`password`), while a Bazel credential helper returns arbitrary headers,
 * typically a bearer token. `java.net.http` has no request interceptor, so wrapping the client Amper downloads
 * with is the only place where an arbitrary header can be injected.
 *
 * See [MultiplatformResolver] for how this client is handed over to the Amper resolution.
 */
internal class CredentialAwareHttpClient(
    private val delegate: HttpClient,
    private val credentials: RepositoryCredentialsResolver,
) : HttpClient() {

    /**
     * Returns [request] with the credentials of its URL attached.
     *
     * Any header we are about to set is dropped from the copy first: [HttpRequest.Builder.header] *appends* a
     * value, so the Basic auth Amper adds by itself would otherwise be sent alongside ours as a second
     * `Authorization` header.
     */
    internal fun authorized(request: HttpRequest): HttpRequest {
        val headers = credentials.headersFor(request.uri().toString())
        if (headers.isEmpty()) return request

        val builder = HttpRequest.newBuilder(request) { name, _ ->
            headers.keys.none { it.equals(name, ignoreCase = true) }
        }
        headers.forEach { (name, values) -> values.forEach { value -> builder.header(name, value) } }
        return builder.build()
    }

    override fun <T : Any?> send(request: HttpRequest, responseBodyHandler: BodyHandler<T>): HttpResponse<T> =
        delegate.send(authorized(request), responseBodyHandler)

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = delegate.sendAsync(authorized(request), responseBodyHandler)

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: BodyHandler<T>,
        pushPromiseHandler: PushPromiseHandler<T>?,
    ): CompletableFuture<HttpResponse<T>> =
        delegate.sendAsync(authorized(request), responseBodyHandler, pushPromiseHandler)

    override fun cookieHandler(): Optional<CookieHandler> = delegate.cookieHandler()
    override fun connectTimeout(): Optional<Duration> = delegate.connectTimeout()
    override fun followRedirects(): Redirect = delegate.followRedirects()
    override fun proxy(): Optional<ProxySelector> = delegate.proxy()
    override fun sslContext(): SSLContext = delegate.sslContext()
    override fun sslParameters(): SSLParameters = delegate.sslParameters()
    override fun authenticator(): Optional<Authenticator> = delegate.authenticator()
    override fun version(): Version = delegate.version()
    override fun executor(): Optional<Executor> = delegate.executor()
    override fun newWebSocketBuilder(): WebSocket.Builder = delegate.newWebSocketBuilder()

    companion object {
        /**
         * Builds a client mirroring the defaults of the one Amper would have used, so that swapping it in only
         * changes authentication.
         */
        fun wrappingDefaultClient(credentials: RepositoryCredentialsResolver): CredentialAwareHttpClient =
            CredentialAwareHttpClient(
                delegate = HttpClient.newBuilder()
                    .version(Version.HTTP_2)
                    .followRedirects(Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(20))
                    .build(),
                credentials = credentials,
            )
    }
}

/**
 * Thrown when the credentials of a repository cannot be expressed in the way a tool we delegate to expects them.
 */
internal class UnsupportedCredentialsException(message: String) : IOException(message)
