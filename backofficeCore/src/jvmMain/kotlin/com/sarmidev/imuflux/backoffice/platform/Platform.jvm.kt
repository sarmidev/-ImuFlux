package com.sarmidev.imuflux.backoffice.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun encodeUrlPathSegment(segment: String): String =
    URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")

actual fun randomUuid(): String = UUID.randomUUID().toString()

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp)
