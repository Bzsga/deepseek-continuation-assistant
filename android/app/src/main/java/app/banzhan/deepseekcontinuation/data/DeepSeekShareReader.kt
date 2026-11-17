package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.DeepSeekShareLink
import app.banzhan.deepseekcontinuation.core.DeepSeekShareParser
import app.banzhan.deepseekcontinuation.core.ImportResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DeepSeekShareDocument(val result: ImportResult, val bytes: ByteArray)

class DeepSeekShareReadException(message: String) : IOException(message)

/** Reads a public snapshot anonymously; it never uses the app's account or model clients. */
class DeepSeekShareReader(private val calls: Call.Factory = shareHttpClient) {
    suspend fun read(link: DeepSeekShareLink): DeepSeekShareDocument = withContext(Dispatchers.IO) {
        // Revalidate even callers that constructed the public data class themselves.
        val validated = DeepSeekShareLink.parse(link.url)
        require(validated == link) { "分享链接无效，请重新复制链接。" }
        val url = "https://chat.deepseek.com/api/v0/share/content".toHttpUrl().newBuilder()
            .addQueryParameter("share_id", validated.id).build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
            // The public share edge rejects the default okhttp agent on some direct connections.
            .header("User-Agent", SHARE_USER_AGENT)
            .header("Accept-Encoding", "identity").header("Cache-Control", "no-cache").get().build()
        val bytes = fetch(request)
        val result = try { DeepSeekShareParser.parse(bytes, validated.id) }
            catch (error: IllegalArgumentException) { throw DeepSeekShareReadException(error.message ?: "分享内容无法识别，请改用 ZIP 导入。") }
        DeepSeekShareDocument(result, bytes)
    }

    private suspend fun fetch(request: Request): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(
                    DeepSeekShareReadException("读取分享失败，请检查网络后重试，或改用 ZIP 文件导入。"))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!continuation.isActive || call.isCanceled()) throw CancellationException()
                        if (it.code == 404 || it.code == 410) throw DeepSeekShareReadException("分享不存在或已撤销，请重新生成分享链接。")
                        if (it.code == 429) throw DeepSeekShareReadException("分享接口暂时限制访问（HTTP 429），请稍后重试或改用 ZIP 导入。")
                        if (it.code == 403) throw DeepSeekShareReadException("分享接口暂时拒绝此连接（HTTP 403），请稍后重试或改用 ZIP 导入。")
                        if (it.request.url != request.url || it.code in 300..399) throw DeepSeekShareReadException("分享接口发生跳转，暂时无法读取。请稍后重试或改用 ZIP 导入。")
                        if (it.code != 200) throw DeepSeekShareReadException("分享暂时无法访问（HTTP ${it.code}），请稍后重试或改用 ZIP 导入。")
                        if (it.header("Content-Encoding").let { encoding -> encoding != null && !encoding.equals("identity", true) }) {
                            throw DeepSeekShareReadException("分享响应格式暂不支持，请改用 ZIP 文件导入。")
                        }
                        val body = it.body ?: throw DeepSeekShareReadException("分享内容为空，请检查链接。")
                        if (body.contentLength() > DeepSeekShareParser.MAX_BYTES) throw DeepSeekShareReadException("分享内容超过 8 MiB，请改用 ZIP 文件导入。")
                        val output = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                if (!continuation.isActive || call.isCanceled()) throw CancellationException()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                if (output.size().toLong() + count > DeepSeekShareParser.MAX_BYTES) {
                                    throw DeepSeekShareReadException("分享内容超过 8 MiB，请改用 ZIP 文件导入。")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                        output.toByteArray()
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error as? DeepSeekShareReadException
                        ?: DeepSeekShareReadException("读取分享未完成，请重试或改用 ZIP 文件导入。"))
                }
            }
        })
    }
}

private const val SHARE_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

private val shareHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
    .build()
