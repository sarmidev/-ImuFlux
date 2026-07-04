package com.sarmidev.imuflux.data.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Firestore serialization: verifies canonical English field names and value
 * mapping for the three diagnostics document types.
 */
class DiagnosticsDocumentsTest {

    @Test
    fun deviceToMap_usesCanonicalKeysAndEnumNames() {
        val map = DiagnosticsDocuments.deviceToMap(
            DeviceHealthSummary(
                deviceId = "dev_1",
                warehouseId = "wh1",
                warehouseName = "WH 1",
                forkliftId = "F_1",
                forkliftModel = "RX",
                appVersion = "1.0",
                buildNumber = 7L,
                androidVersion = 34,
                deviceModel = "Pixel",
                manufacturer = "Google",
                lastSeenAt = 123L,
                isRecording = true,
                currentHealthStatus = DiagnosticsHealthStatus.WARNING,
                currentHealthReasons = listOf(DiagnosticsReasons.JITTER_HIGH),
                pendingUploadChunks = 2,
            ),
        )
        assertEquals("dev_1", map["deviceId"])
        assertEquals("wh1", map["warehouseId"])
        assertEquals("WH 1", map["warehouseName"])
        assertEquals("F_1", map["forkliftId"])
        assertEquals("RX", map["forkliftModel"])
        assertEquals(7L, map["buildNumber"])
        assertEquals(true, map["isRecording"])
        assertEquals("WARNING", map["currentHealthStatus"])
        assertEquals(listOf(DiagnosticsReasons.JITTER_HIGH), map["currentHealthReasons"])
        assertEquals(2, map["pendingUploadChunks"])
        // No legacy snake_case keys leak in.
        assertTrue(map.keys.none { it.contains("_") })
    }

    @Test
    fun sessionToMap_serializesEnumsAsNames() {
        val map = DiagnosticsDocuments.sessionToMap(
            ImuSessionDiagnostics(
                sessionId = "S1",
                deviceId = "dev_1",
                recordingStopReason = RecordingStopReason.SERVICE_DESTROYED,
                healthStatus = DiagnosticsHealthStatus.ERROR,
                healthReasons = listOf(DiagnosticsReasons.SAMPLE_RATE_CRITICAL),
                expectedSampleRateHz = 100.0,
                measuredSampleRateHz = 70.0,
            ),
        )
        assertEquals("S1", map["sessionId"])
        assertEquals("SERVICE_DESTROYED", map["recordingStopReason"])
        assertEquals("ERROR", map["healthStatus"])
        assertEquals(100.0, map["expectedSampleRateHz"])
        assertEquals(70.0, map["measuredSampleRateHz"])
    }

    @Test
    fun windowToMap_hasWindowIdAndStatus() {
        val map = DiagnosticsDocuments.windowToMap(
            ImuHealthWindow(
                windowId = "S1_w0001",
                sessionId = "S1",
                deviceId = "dev_1",
                samplesPerSecond = 99.0,
                healthStatus = DiagnosticsHealthStatus.OK,
            ),
        )
        assertEquals("S1_w0001", map["windowId"])
        assertEquals(99.0, map["samplesPerSecond"])
        assertEquals("OK", map["healthStatus"])
    }
}
