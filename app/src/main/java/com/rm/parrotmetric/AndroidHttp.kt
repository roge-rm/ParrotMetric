package com.rm.parrotmetric

import com.rm.parrotmetric.app.Http
import com.rm.parrotmetric.app.HttpReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** HTTP with OkHttp, since Android's HttpURLConnection refuses WebDAV's methods. */
object AndroidHttp : Http {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpReply? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).method(method, body?.toRequestBody())
                headers.forEach { (k, v) -> request.header(k, v) }
                client.newCall(request.build()).execute().use { HttpReply(it.code, it.body?.bytes() ?: ByteArray(0)) }
            }.getOrNull()
        }
}
