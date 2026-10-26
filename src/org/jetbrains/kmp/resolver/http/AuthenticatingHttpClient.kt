package org.jetbrains.kmp.resolver.http

import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import org.jetbrains.kmp.resolver.auth.RequestCredentials

/**
 * [HttpClient] authenticating every request with the headers of [credentials] before handing it to
 * [delegate].
 *
 * A header supplied by the credentials replaces a header of the same name already set on the
 * request. A request its credentials do not apply to is sent unchanged.
 *
 * [HttpClient.sendAsync] cannot suspend, so each call runs as a child coroutine of [scope]
 * completing the returned future: cancelling the future cancels the coroutine along with the
 * delegate's request, and the end of [scope] awaits the requests still running. Only [sendAsync] is
 * supported, the blocking [send] would wait on a future completed by [scope] and deadlock when
 * called from its thread.
 *
 * Redirects are followed by [delegate] according to its policy, so redirected requests carry the
 * headers obtained for the original URI rather than headers requested for the redirect target.
 */
internal class AuthenticatingHttpClient(
    private val scope: CoroutineScope,
    private val delegate: HttpClient,
    private val credentials: RequestCredentials,
) : HttpClient() {
    private suspend fun <T> sendWithCredentials(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
    ): HttpResponse<T> {
        val headers = credentials.headersFor(request.uri())
        val authenticated = if (headers.isNullOrEmpty()) request else request.withHeaders(headers)
        return when (pushPromiseHandler) {
            null -> delegate.sendAsync(authenticated, responseBodyHandler)
            else -> delegate.sendAsync(authenticated, responseBodyHandler, pushPromiseHandler)
        }.await()
    }

    private fun HttpRequest.withHeaders(headers: Map<String, List<String>>): HttpRequest =
        HttpRequest.newBuilder(this) { name, _ ->
                headers.keys.none { it.equals(name, ignoreCase = true) }
            }
            .apply {
                headers.forEach { (name, values) ->
                    values.forEach { value -> header(name, value) }
                }
            }
            .build()

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = scope.future {
        sendWithCredentials(request, responseBodyHandler, pushPromiseHandler = null)
    }

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
    ): CompletableFuture<HttpResponse<T>> = scope.future {
        sendWithCredentials(request, responseBodyHandler, pushPromiseHandler)
    }

    override fun <T : Any?> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> =
        throw UnsupportedOperationException(
            "AuthenticatingHttpClient only supports sendAsync, see its documentation",
        )

    // Forwarded by hand: `by delegate` only works for interfaces and HttpClient is an abstract
    // class.
    override fun cookieHandler(): Optional<CookieHandler> = delegate.cookieHandler()

    override fun connectTimeout(): Optional<java.time.Duration> = delegate.connectTimeout()

    override fun followRedirects(): Redirect = delegate.followRedirects()

    override fun proxy(): Optional<ProxySelector> = delegate.proxy()

    override fun sslContext(): SSLContext = delegate.sslContext()

    override fun sslParameters(): SSLParameters = delegate.sslParameters()

    override fun authenticator(): Optional<Authenticator> = delegate.authenticator()

    override fun version(): Version = delegate.version()

    override fun executor(): Optional<Executor> = delegate.executor()

    override fun newWebSocketBuilder(): WebSocket.Builder = delegate.newWebSocketBuilder()

    override fun shutdown() = delegate.shutdown()

    override fun awaitTermination(duration: java.time.Duration): Boolean =
        delegate.awaitTermination(duration)

    override fun isTerminated(): Boolean = delegate.isTerminated

    override fun shutdownNow() = delegate.shutdownNow()

    override fun close() = delegate.close()
}

/**
 * The resolver's [jdkHttpClient], authenticating requests with [credentials] through
 * [AuthenticatingHttpClient] whose requests run in [scope].
 */
internal fun authenticatingHttpClient(
    scope: CoroutineScope,
    credentials: RequestCredentials,
    connectTimeout: Duration,
): HttpClient =
    AuthenticatingHttpClient(
        scope = scope,
        delegate = jdkHttpClient(connectTimeout),
        credentials = credentials,
    )
