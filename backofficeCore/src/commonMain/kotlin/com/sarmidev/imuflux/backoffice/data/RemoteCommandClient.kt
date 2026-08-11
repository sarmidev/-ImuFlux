package com.sarmidev.imuflux.backoffice.data

import com.sarmidev.imuflux.backoffice.config.BackofficeFirebaseConfig
import com.sarmidev.imuflux.backoffice.config.PlatformFirebaseConfigLoader
import com.sarmidev.imuflux.backoffice.platform.createHttpClient
import com.sarmidev.imuflux.backoffice.platform.randomUuid
import com.sarmidev.imuflux.data.diagnostics.RemoteRecordingCommand
import com.sarmidev.imuflux.data.diagnostics.RemoteRecordingPayload
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Outcome of a remote command call, mirroring the Cloud Function JSON response. */
data class RemoteCommandResult(
    val success: Boolean,
    val deviceId: String,
    val command: RemoteRecordingCommand,
    val messageId: String? = null,
    val error: String? = null,
)

/** Thrown for transport/config problems; validation/backend errors surface as [RemoteCommandResult.error]. */
class RemoteCommandException(message: String) : Exception(message)

/**
 * Calls the `sendRemoteRecordingCommand` Cloud Function with the admin
 * `idToken`. The backoffice never touches FCM directly and holds no server key: it
 * only forwards `{ deviceId, command, requestId }` with a Bearer token.
 *
 * The endpoint URL is resolved via [PlatformFirebaseConfigLoader.resolveRemoteCommandsUrl].
 */
class RemoteCommandClient(
    config: BackofficeFirebaseConfig,
    private val tokenProvider: suspend () -> String,
    private val endpointUrl: String = PlatformFirebaseConfigLoader.resolveRemoteCommandsUrl(config.projectId),
    private val httpClient: HttpClient = createHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val requestIdFactory: () -> String = { randomUuid() },
) {

    suspend fun startRecording(deviceId: String): RemoteCommandResult =
        send(deviceId, RemoteRecordingCommand.START_RECORDING)

    suspend fun stopRecording(deviceId: String): RemoteCommandResult =
        send(deviceId, RemoteRecordingCommand.STOP_RECORDING)

    private suspend fun send(deviceId: String, command: RemoteRecordingCommand): RemoteCommandResult {
        val body = buildRequestBody(deviceId, command, requestIdFactory())
        val (code, text) = runCatching {
            val response = httpClient.post(endpointUrl) {
                contentType(ContentType.Application.Json)
                setBody(body)
                header(HttpHeaders.Authorization, "Bearer ${tokenProvider()}")
            }
            response.status.value to response.bodyAsText()
        }.getOrElse { throw RemoteCommandException("Error de red al enviar el comando: ${it.message}") }

        return parseResponse(deviceId, command, code, text)
    }

    // ── Pure, unit-testable helpers ─────────────────────────────────────────

    fun buildRequestBody(deviceId: String, command: RemoteRecordingCommand, requestId: String): String =
        buildJsonObject {
            put(RemoteRecordingPayload.KEY_DEVICE_ID, deviceId)
            put(RemoteRecordingPayload.KEY_COMMAND, command.wire)
            put(RemoteRecordingPayload.KEY_REQUEST_ID, requestId)
        }.toString()

    fun parseResponse(
        deviceId: String,
        command: RemoteRecordingCommand,
        code: Int,
        text: String,
    ): RemoteCommandResult {
        val parsed: JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val success = parsed?.get("success")?.jsonPrimitive?.booleanOrNull ?: false
        val messageId = parsed?.get("messageId")?.jsonPrimitive?.contentOrNull
        val error = parsed?.get("error")?.jsonPrimitive?.contentOrNull

        return if (code in 200..299 && success) {
            RemoteCommandResult(success = true, deviceId = deviceId, command = command, messageId = messageId)
        } else {
            RemoteCommandResult(
                success = false,
                deviceId = deviceId,
                command = command,
                error = error ?: mapTransportError(code),
            )
        }
    }

    private fun mapTransportError(code: Int): String = when (code) {
        401 -> "No autenticado. Vuelve a iniciar sesión."
        403 -> "La cuenta no tiene permisos de administrador."
        404 -> "El dispositivo no existe en diagnostics."
        409 -> "El dispositivo no tiene token FCM (control remoto no disponible)."
        410 -> "El token FCM del dispositivo ya no es válido; se ha limpiado."
        else -> "El backend devolvió un error ($code)."
    }

    companion object {
        const val ENV_URL = "IMUFLUX_REMOTE_COMMANDS_URL"
        const val LOCAL_PROP_KEY = "remoteCommands.url"
        const val DEFAULT_REGION = "europe-west1"
        const val FUNCTION_NAME = "sendRemoteRecordingCommand"
    }
}
