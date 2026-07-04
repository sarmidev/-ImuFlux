package com.sarmidev.imuflux.desktop.data

import com.sarmidev.imuflux.data.diagnostics.DiagnosticsHealthStatus
import com.sarmidev.imuflux.data.diagnostics.RecordingStopReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirestoreRestMapperTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(raw: String) = json.parseToJsonElement(raw).jsonObject

    @Test
    fun mapsDeviceSummaryWithAllTypes() {
        val doc = parse(
            """
            {
              "name": "projects/imuflux/databases/(default)/documents/diagnosticsDevices/dev-123",
              "fields": {
                "deviceId": {"stringValue": "dev-123"},
                "warehouseId": {"stringValue": "wh-1"},
                "warehouseName": {"stringValue": "Central"},
                "forkliftId": {"stringValue": "fk-9"},
                "forkliftModel": {"stringValue": "Toyota 8FBE"},
                "appVersion": {"stringValue": "1.4.2"},
                "buildNumber": {"integerValue": "142"},
                "androidVersion": {"integerValue": "34"},
                "deviceModel": {"stringValue": "Pixel 7"},
                "manufacturer": {"stringValue": "Google"},
                "lastSeenAt": {"integerValue": "1720000000000"},
                "isRecording": {"booleanValue": true},
                "currentHealthStatus": {"stringValue": "WARNING"},
                "currentHealthReasons": {"arrayValue": {"values": [
                  {"stringValue": "JITTER_HIGH"},
                  {"stringValue": "FRAMES_DROPPED"}
                ]}},
                "lastMeasuredSampleRateHz": {"doubleValue": 98.7},
                "lastJitterP95Ms": {"doubleValue": 6.5},
                "lastFramesDropped": {"integerValue": "12"},
                "lastChunkIndex": {"integerValue": "4"},
                "lastBytesWritten": {"integerValue": "204800"},
                "pendingUploadChunks": {"integerValue": "2"},
                "nonFatalErrorCount": {"integerValue": "1"},
                "lastErrorMessageSafe": {"stringValue": "recoverable"}
              }
            }
            """.trimIndent(),
        )

        val summary = FirestoreRestMapper.toDeviceSummary(doc)!!
        assertEquals("dev-123", summary.deviceId)
        assertEquals("Central", summary.warehouseName)
        assertEquals(142L, summary.buildNumber)
        assertEquals(34, summary.androidVersion)
        assertEquals(1720000000000L, summary.lastSeenAt)
        assertTrue(summary.isRecording)
        assertEquals(DiagnosticsHealthStatus.WARNING, summary.currentHealthStatus)
        assertEquals(listOf("JITTER_HIGH", "FRAMES_DROPPED"), summary.currentHealthReasons)
        assertEquals(98.7, summary.lastMeasuredSampleRateHz)
        assertEquals(6.5, summary.lastJitterP95Ms)
        assertEquals(12L, summary.lastFramesDropped)
        assertEquals(204800L, summary.lastBytesWritten)
        assertEquals(2, summary.pendingUploadChunks)
        assertEquals("recoverable", summary.lastErrorMessageSafe)
    }

    @Test
    fun deviceMissingFieldsFallBackToDefaults() {
        val doc = parse(
            """
            {
              "name": "projects/imuflux/databases/(default)/documents/diagnosticsDevices/only-id",
              "fields": {}
            }
            """.trimIndent(),
        )

        val summary = FirestoreRestMapper.toDeviceSummary(doc)!!
        // Falls back to the document id when the deviceId field is absent.
        assertEquals("only-id", summary.deviceId)
        assertEquals("", summary.warehouseId)
        assertEquals(0L, summary.buildNumber)
        assertEquals(0, summary.androidVersion)
        assertFalse(summary.isRecording)
        assertEquals(DiagnosticsHealthStatus.UNKNOWN, summary.currentHealthStatus)
        assertTrue(summary.currentHealthReasons.isEmpty())
        assertEquals(0.0, summary.lastMeasuredSampleRateHz)
        assertNull(summary.lastRecordingStartedAt)
        assertNull(summary.lastErrorMessageSafe)
    }

    @Test
    fun doubleFieldAcceptsIntegerValueAndViceVersa() {
        // Hz stored as an integer, framesDropped stored as a double.
        val doc = parse(
            """
            {
              "name": "…/diagnosticsDevices/x",
              "fields": {
                "deviceId": {"stringValue": "x"},
                "lastMeasuredSampleRateHz": {"integerValue": "100"},
                "lastFramesDropped": {"doubleValue": 7.0}
              }
            }
            """.trimIndent(),
        )
        val summary = FirestoreRestMapper.toDeviceSummary(doc)!!
        assertEquals(100.0, summary.lastMeasuredSampleRateHz)
        assertEquals(7L, summary.lastFramesDropped)
    }

    @Test
    fun mapsSessionDiagnostics() {
        val doc = parse(
            """
            {
              "name": "…/sessions/sess-1",
              "fields": {
                "sessionId": {"stringValue": "sess-1"},
                "deviceId": {"stringValue": "dev-123"},
                "startedAtWallMs": {"integerValue": "1719990000000"},
                "durationMs": {"integerValue": "600000"},
                "measuredSampleRateHz": {"doubleValue": 99.2},
                "jitterP95Ms": {"doubleValue": 3.1},
                "framesDropped": {"integerValue": "0"},
                "chunkCount": {"integerValue": "10"},
                "uploadedChunkCount": {"integerValue": "9"},
                "pendingChunkCount": {"integerValue": "1"},
                "recordingStopReason": {"stringValue": "USER_STOP"},
                "healthStatus": {"stringValue": "OK"},
                "healthReasons": {"arrayValue": {"values": [{"stringValue": "ALL_CHECKS_PASSED"}]}}
              }
            }
            """.trimIndent(),
        )
        val session = FirestoreRestMapper.toSessionDiagnostics(doc)!!
        assertEquals("sess-1", session.sessionId)
        assertEquals("dev-123", session.deviceId)
        assertEquals(600000L, session.durationMs)
        assertEquals(99.2, session.measuredSampleRateHz)
        assertEquals(RecordingStopReason.USER_STOP, session.recordingStopReason)
        assertEquals(DiagnosticsHealthStatus.OK, session.healthStatus)
        assertEquals(listOf("ALL_CHECKS_PASSED"), session.healthReasons)
    }

    @Test
    fun mapsHealthWindow() {
        val doc = parse(
            """
            {
              "name": "…/healthWindows/win-1",
              "fields": {
                "windowId": {"stringValue": "win-1"},
                "sessionId": {"stringValue": "sess-1"},
                "deviceId": {"stringValue": "dev-123"},
                "endedAt": {"integerValue": "1720000000000"},
                "samplesPerSecond": {"doubleValue": 97.5},
                "jitterP95Ms": {"doubleValue": 4.2},
                "framesDropped": {"integerValue": "3"},
                "bytesWritten": {"integerValue": "51200"},
                "uploadPendingCount": {"integerValue": "0"},
                "healthStatus": {"stringValue": "ERROR"}
              }
            }
            """.trimIndent(),
        )
        val window = FirestoreRestMapper.toHealthWindow(doc)!!
        assertEquals("win-1", window.windowId)
        assertEquals(1720000000000L, window.endedAt)
        assertEquals(97.5, window.samplesPerSecond)
        assertEquals(3L, window.framesDropped)
        assertEquals(DiagnosticsHealthStatus.ERROR, window.healthStatus)
        assertTrue(window.healthReasons.isEmpty())
    }

    @Test
    fun documentIdFallbackWhenSessionIdFieldMissing() {
        val doc = parse(
            """
            {
              "name": "…/sessions/fallback-session",
              "fields": {"deviceId": {"stringValue": "dev-9"}}
            }
            """.trimIndent(),
        )
        val session = FirestoreRestMapper.toSessionDiagnostics(doc)!!
        assertEquals("fallback-session", session.sessionId)
    }
}
