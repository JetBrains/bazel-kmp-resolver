package org.jetbrains.kmp.resolver

import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.net.ssl.SSLContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The JDK HTTP client shared by Amper's downloads and the artifact HEAD probes, configured like
 * Amper's own default client (HTTP/2 when the server supports it, redirects followed except from
 * HTTPS to HTTP, default SSL context) with the caller's [connectTimeout].
 */
internal fun jdkHttpClient(connectTimeout: Duration): HttpClient =
    HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .sslContext(SSLContext.getDefault())
        .connectTimeout(connectTimeout.toJavaDuration())
        .build()

/**
 * Retry policy of [sendWithRetry]: a request answered with a 5xx status or failing with an I/O
 * error (timeouts included) is retried up to [maxRetries] times, waiting `min(baseDelay *
 * 2^(retry - 1), maxDelay)` plus up to [randomization] before each retry, or longer when the server
 * asks so with a `Retry-After` header.
 */
internal data class RetryPolicy(
    val maxRetries: Int,
    val baseDelay: Duration,
    val maxDelay: Duration,
    val randomization: Duration,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must not be negative" }
        require(baseDelay.isPositive()) { "baseDelay must be positive" }
        require(maxDelay.isPositive()) { "maxDelay must be positive" }
        require(!randomization.isNegative()) { "randomization must not be negative" }
    }

    fun delayBefore(retry: Int, retryAfter: Duration?): Duration {
        val exponential = minOf(baseDelay * (1 shl (retry - 1)), maxDelay)
        val randomized =
            exponential + Random.nextLong(0, randomization.inWholeMilliseconds + 1).milliseconds
        return maxOf(randomized, retryAfter ?: Duration.ZERO)
    }

    companion object {
        /**
         * Five retries with delays of 3, 6, 12, 24 and 48 seconds plus up to a second of jitter.
         */
        val DEFAULT =
            RetryPolicy(
                maxRetries = 5,
                baseDelay = 3.seconds,
                maxDelay = 60.seconds,
                randomization = 1.seconds,
            )
    }
}

private val retryLogger: Logger =
    LoggerFactory.getLogger("org.jetbrains.kmp.resolver.sendWithRetry")

/**
 * Sends [request] with this client, retrying according to [policy] on 5xx responses and I/O
 * failures. Other failures, such as credentials failing to resolve for the request, are not
 * retried.
 */
internal suspend fun <T> HttpClient.sendWithRetry(
    request: HttpRequest,
    responseBodyHandler: HttpResponse.BodyHandler<T>,
    policy: RetryPolicy,
): HttpResponse<T> {
    repeat(policy.maxRetries) { retry ->
        val attempt =
            try {
                Attempt.Answered(sendAsync(request, responseBodyHandler).await())
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Attempt.Failed(e)
            }
        val retryAfter =
            when (attempt) {
                is Attempt.Answered ->
                    when {
                        attempt.response.statusCode() !in 500..599 -> return attempt.response
                        else -> {
                            retryLogger.debug(
                                "[{}] server error {}, retrying ({}/{})",
                                request.uri(),
                                attempt.response.statusCode(),
                                retry + 1,
                                policy.maxRetries,
                            )
                            attempt.response.retryAfter()
                        }
                    }

                is Attempt.Failed -> {
                    retryLogger.debug(
                        "[{}] request failed with {}, retrying ({}/{})",
                        request.uri(),
                        attempt.exception.toString(),
                        retry + 1,
                        policy.maxRetries,
                    )
                    null
                }
            }
        delay(policy.delayBefore(retry + 1, retryAfter))
    }
    // The last attempt is not retried: its answer or failure is the caller's.
    return sendAsync(request, responseBodyHandler).await()
}

private sealed interface Attempt<out T> {
    class Answered<T>(val response: HttpResponse<T>) : Attempt<T>

    class Failed(val exception: IOException) : Attempt<Nothing>
}

/** The delay-seconds form of a `Retry-After` header; an HTTP-date is ignored. */
private fun HttpResponse<*>.retryAfter(): Duration? =
    headers().firstValue("Retry-After").orElse(null)?.trim()?.toLongOrNull()?.seconds
