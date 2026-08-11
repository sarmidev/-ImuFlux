package com.sarmidev.imuflux.backoffice.platform

import io.ktor.client.HttpClient

expect fun currentTimeMillis(): Long

expect fun encodeUrlPathSegment(segment: String): String

expect fun randomUuid(): String

expect fun createHttpClient(): HttpClient
