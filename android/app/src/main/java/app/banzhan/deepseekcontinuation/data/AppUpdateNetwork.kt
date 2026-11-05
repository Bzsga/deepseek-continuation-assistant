package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.AppUpdateManifest
import app.banzhan.deepseekcontinuation.core.AppUpdateManifestParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class AppUpdateDownloadException(message: String) : Exception(message)

/** A separate client has no account credentials, cookies, API configuration or redirect following. */
internal class AppUpdateNetwork(private val calls: Call.Factory = updateHttpClient) {
    suspend fun fetchManifest(): AppUpdateManifest = exchange(
        Request.Builder().url(AppUpdateManifestParser.MANIFEST_URL)
            .header("Accept", "application/json").header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache").get().build(),
    ) { response, active ->
        val body = response.body ?: throw AppUpdateDownloadException("暂时无法检查更新，请稍后重试。")
        val maximum = AppUpdateManifestParser.MAX_MANIFEST_BYTES
        if (body.contentLength() > maximum) throw AppUpdateDownloadException("更新信息无效，请稍后重新检查。")
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                if (!active()) throw CancellationException()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0 || output.size().toLong() + count > maximum) {
                    throw AppUpdateDownloadException("更新信息无效，请稍后重新检查。")
                }
                output.write(buffer, 0, count)
            }
        }
        try {
            AppUpdateManifestParser.parse(output.toByteArray())
        } catch (_: IllegalArgumentException) {
            throw AppUpdateDownloadException("更新信息无效，请稍后重新检查。")
        }
    }

    private suspend fun <T> exchange(request: Request, read: (Response, () -> Boolean) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(AppUpdateDownloadException("网络连接失败，请稍后重试。"))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!continuation.isActive || call.isCanceled()) throw CancellationException()
                        if (it.code != 200 || it.request.url != request.url ||
                            it.header("Content-Encoding").let { encoding -> encoding != null && !encoding.equals("identity", true) }) {
                            throw AppUpdateDownloadException("更新服务暂时不可用，请稍后重试。")
                        }
                        read(it) { continuation.isActive && !call.isCanceled() }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) {
                        val safe = error as? AppUpdateDownloadException ?: AppUpdateDownloadException("更新请求未完成，请重试。")
                        continuation.resumeWithException(safe)
                    }
                }
            }
        })
    }
}

private val updateHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES)
    .build()

internal val updateJson = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
