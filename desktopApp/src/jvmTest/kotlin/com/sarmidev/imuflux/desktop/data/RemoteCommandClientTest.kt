package com.sarmidev.imuflux.desktop.data

import com.sarmidev.imuflux.data.diagnostics.RemoteRecordingCommand
import com.sarmidev.imuflux.desktop.config.DesktopFirebaseConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteCommandClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun client(): RemoteCommandClient = RemoteCommandClient(
        config = DesktopFirebaseConfig(apiKey = "k", projectId = "imuflux", source = "test"),
        tokenProvider = { "fake-token" },
        endpointUrl = "https://example.test/fn",
        requestIdFactory = { "req-fixed" },
    )

    @Test
    fun buildRequestBody_hasDeviceCommandAndRequestId() {
        val body = client().buildRequestBody("dev-1", RemoteRecordingCommand.START_RECORDING, "req-7")
        val obj = json.parseToJsonElement(body).jsonObject
        assertEquals("dev-1", obj["deviceId"]?.jsonPrimitive?.contentOrNull)
        assertEquals("START_RECORDING", obj["command"]?.jsonPrimitive?.contentOrNull)
        assertEquals("req-7", obj["requestId"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun parseResponse_success() {
        val result = client().parseResponse(
            deviceId = "dev-1",
            command = RemoteRecordingCommand.START_RECORDING,
            code = 200,
            text = """{"success": true, "deviceId": "dev-1", "command": "START_RECORDING", "messageId": "msg-1"}""",
        )
        assertTrue(result.success)
        assertEquals("msg-1", result.messageId)
        assertNull(result.error)
    }

    @Test
    fun parseResponse_backendErrorUsesReturnedMessage() {
        val result = client().parseResponse(
            deviceId = "dev-1",
            command = RemoteRecordingCommand.STOP_RECORDING,
            code = 409,
            text = """{"success": false, "error": "Device has no registered FCM token"}""",
        )
        assertFalse(result.success)
        assertEquals("Device has no registered FCM token", result.error)
    }

    @Test
    fun parseResponse_nonJsonFallsBackToTransportError() {
        val result = client().parseResponse(
            deviceId = "dev-1",
            command = RemoteRecordingCommand.START_RECORDING,
            code = 403,
            text = "Forbidden",
        )
        assertFalse(result.success)
        assertEquals("La cuenta no tiene permisos de administrador.", result.error)
    }

    @Test
    fun parseResponse_successFalseWith200IsStillFailure() {
        val result = client().parseResponse(
            deviceId = "dev-1",
            command = RemoteRecordingCommand.START_RECORDING,
            code = 200,
            text = """{"success": false, "error": "weird"}""",
        )
        assertFalse(result.success)
        assertEquals("weird", result.error)
    }

    @Test
    fun resolveEndpointUrl_prefersEnvThenLocalPropsThenDefault() {
        // 1) Env wins.
        val fromEnv = RemoteCommandClient.resolveEndpointUrl(
            projectId = "imuflux",
            env = { name -> if (name == RemoteCommandClient.ENV_URL) "https://env.example/fn" else null },
            localProperties = File("does-not-exist.properties"),
        )
        assertEquals("https://env.example/fn", fromEnv)

        // 3) Default derived from region + projectId when nothing configured.
        val default = RemoteCommandClient.resolveEndpointUrl(
            projectId = "imuflux",
            env = { null },
            localProperties = File("does-not-exist.properties"),
        )
        assertEquals(
            "https://${RemoteCommandClient.DEFAULT_REGION}-imuflux.cloudfunctions.net/${RemoteCommandClient.FUNCTION_NAME}",
            default,
        )
    }
}
