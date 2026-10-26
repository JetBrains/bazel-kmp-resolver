package org.jetbrains.kmp.resolver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Starts an HTTP server on an ephemeral port of the loopback interface, serving every path with
 * [handler].
 */
internal fun startLocalHttpServer(handler: HttpHandler): HttpServer =
    HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/", handler)
        executor = Executors.newCachedThreadPool()
        start()
    }

internal fun HttpServer.urlOf(path: String): String = "http://localhost:${address.port}$path"

/**
 * Answers [status] with [body], or without body for HEAD requests, which the JDK server requires.
 */
internal fun HttpExchange.respond(status: Int, body: ByteArray) {
    when (requestMethod) {
        "HEAD" -> sendResponseHeaders(status, -1)
        else -> {
            sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) responseBody.use { it.write(body) }
        }
    }
    close()
}
