package com.sarmidev.imuflux.backoffice.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.datetime.Clock
import kotlin.random.Random

actual fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

actual fun encodeUrlPathSegment(segment: String): String = encodeURIComponent(segment)

actual fun randomUuid(): String {
    // UUID v4 without relying on browser crypto APIs.
    val bytes = ByteArray(16).also { Random.nextBytes(it) }
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    fun hex(b: Byte): String = (b.toInt() and 0xff).toString(16).padStart(2, '0')
    val h = bytes.joinToString("") { hex(it) }
    return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
}

actual fun createHttpClient(): HttpClient = HttpClient(Js)

@JsFun("(s) => encodeURIComponent(s)")
private external fun encodeURIComponent(s: String): String
