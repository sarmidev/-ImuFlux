package com.sarmidev.imuflux.desktop.data

import com.sarmidev.imuflux.data.diagnostics.RemoteRecordingCommand
import com.sarmidev.imuflux.data.diagnostics.RemoteRecordingPayload
import com.sarmidev.imuflux.desktop.config.DesktopFirebaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Properties
import java.util.UUID

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
 * `idToken`. The desktop never touches FCM directly and holds no server key: it
 * only forwards `{ deviceId, command, requestId }` with a Bearer token.
 *
 * The endpoint URL is resolved (see [resolveEndpointUrl]) from, in order:
 *   1. env var `IMUFLUX_REMOTE_COMMANDS_URL`
 *   2. `desktopApp/local.properties` key `remoteCommands.url`
 *   3. a default derived from the region + project id — not a hardcoded prod URL,
 *      but the conventional Cloud Functions HTTPS endpoint for this project.
 */
class RemoteCommandClient(
    config: DesktopFirebaseConfig,
    private val tokenProvider: suspend () -> String,
    private val endpointUrl: String = resolveEndpointUrl(config.projectId),
    private val httpClient: OkHttpClient = OkHttpClient.Builder().build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) {

    suspend fun startRecording(deviceId: String): RemoteCommandResult =
        send(deviceId, RemoteRecordingCommand.START_RECORDING)

    suspend fun stopRecording(deviceId: String): RemoteCommandResult =
        send(deviceId, RemoteRecordingCommand.STOP_RECORDING)

    private suspend fun send(deviceId: String, command: RemoteRecordingCommand): RemoteCommandResult =
        withContext(Dispatchers.IO) {
            val body = buildRequestBody(deviceId, command, requestIdFactory())
            val request = Request.Builder()
                .url(endpointUrl)
                .post(body.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .header("Authorization", "Bearer ${tokenProvider()}")
                .build()

            val (code, text) = runCatching {
                httpClient.newCall(request).execute().use { resp ->
                    resp.code to (resp.body?.string().orEmpty())
                }
            }.getOrElse { throw RemoteCommandException("Error de red al enviar el comando: ${it.message}") }

            parseResponse(deviceId, command, code, text)
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
        private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"

        fun resolveEndpointUrl(
            projectId: String,
            env: (String) -> String? = System::getenv,
            localProperties: File = File("desktopApp/local.properties"),
        ): String {
            env(ENV_URL)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            readLocalUrl(localProperties)?.let { return it }
            return "https://$DEFAULT_REGION-$projectId.cloudfunctions.net/$FUNCTION_NAME"
        }

        private fun readLocalUrl(file: File): String? {
            if (!file.exists()) return null
            val props = Properties()
            runCatching { file.inputStream().use(props::load) }.getOrElse { return null }
            return props.getProperty(LOCAL_PROP_KEY)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
