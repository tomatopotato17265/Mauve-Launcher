package dev.pencilstub.mauvelauncher.auth

import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class HttpCall(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray?)

internal class HttpResult(val status: Int, private val headers: Map<String, List<String>>, val body: String) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

internal fun interface HttpTransport {
    suspend fun send(call: HttpCall): HttpResult
}

internal class JdkHttpTransport : HttpTransport {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    override suspend fun send(call: HttpCall): HttpResult {
        val request = HttpRequest.newBuilder(URI(call.url))
            .timeout(Duration.ofSeconds(30))
            .method(call.method, call.body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody())
            .apply { call.headers.forEach(::header) }
            .build()

        // sign-in code can't be used twice
        var attempt = 1
        while (true) {
            try {
                val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
                return HttpResult(response.statusCode(), response.headers().map(), response.body())
            } catch (e: Exception) {
                if ((e !is ConnectException && e !is HttpConnectTimeoutException) || attempt >= MAX_ATTEMPTS) throw e
                attempt++
                delay(250)
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
