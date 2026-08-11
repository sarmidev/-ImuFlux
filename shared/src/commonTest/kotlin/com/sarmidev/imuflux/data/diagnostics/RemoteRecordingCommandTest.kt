package com.sarmidev.imuflux.data.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteRecordingCommandTest {

    @Test
    fun fromWire_parsesCanonicalValues() {
        assertEquals(RemoteRecordingCommand.START_RECORDING, RemoteRecordingCommand.fromWire("START_RECORDING"))
        assertEquals(RemoteRecordingCommand.STOP_RECORDING, RemoteRecordingCommand.fromWire("STOP_RECORDING"))
    }

    @Test
    fun fromWire_rejectsUnknownOrNull() {
        assertNull(RemoteRecordingCommand.fromWire("PAUSE"))
        assertNull(RemoteRecordingCommand.fromWire(null))
        assertNull(RemoteRecordingCommand.fromWire(""))
    }

    @Test
    fun wireValuesAreStable() {
        // These strings are a cross-language contract (Kotlin ⇄ TS Cloud Function).
        assertEquals("START_RECORDING", RemoteRecordingCommand.START_RECORDING.wire)
        assertEquals("STOP_RECORDING", RemoteRecordingCommand.STOP_RECORDING.wire)
    }

    @Test
    fun fcmTokenUpdate_carriesTokenAndClearsInvalidMarker() {
        val map = DiagnosticsDocuments.fcmTokenUpdate("tok-123", nowMs = 555L)
        assertEquals("tok-123", map[DiagnosticsDocuments.FIELD_FCM_TOKEN])
        assertEquals(555L, map[DiagnosticsDocuments.FIELD_FCM_TOKEN_UPDATED_AT])
        assertEquals(555L, map["lastSeenAt"])
        // Explicit null resets any prior invalid marker.
        assertTrue(map.containsKey(DiagnosticsDocuments.FIELD_FCM_TOKEN_INVALID_AT))
        assertNull(map[DiagnosticsDocuments.FIELD_FCM_TOKEN_INVALID_AT])
        // Never touches currentHealthStatus (preserved from existing doc on update()).
        assertFalse(map.containsKey("currentHealthStatus"))
    }

    @Test
    fun minimalDeviceWithToken_isRulesValidAndCarriesToken() {
        val map = DiagnosticsDocuments.minimalDeviceWithToken("dev-1", "tok-9", nowMs = 999L)
        assertEquals("dev-1", map["deviceId"])
        assertEquals(999L, map["lastSeenAt"])
        assertEquals(DiagnosticsHealthStatus.UNKNOWN.name, map["currentHealthStatus"])
        assertEquals("tok-9", map[DiagnosticsDocuments.FIELD_FCM_TOKEN])
        assertEquals(999L, map[DiagnosticsDocuments.FIELD_FCM_TOKEN_UPDATED_AT])
    }

    @Test
    fun deviceToMap_omitsFcmFieldsWhenNull_toAvoidClobberingToken() {
        // A summary from the aggregator never carries a token; the merge map must
        // NOT include fcm keys, or a health/upload write would erase the token.
        val map = DiagnosticsDocuments.deviceToMap(DeviceHealthSummary(deviceId = "dev-1"))
        assertFalse(map.containsKey(DiagnosticsDocuments.FIELD_FCM_TOKEN))
        assertFalse(map.containsKey(DiagnosticsDocuments.FIELD_FCM_TOKEN_UPDATED_AT))
        assertFalse(map.containsKey(DiagnosticsDocuments.FIELD_FCM_TOKEN_INVALID_AT))
    }

    @Test
    fun deviceToMap_includesFcmFieldsWhenPresent() {
        val map = DiagnosticsDocuments.deviceToMap(
            DeviceHealthSummary(
                deviceId = "dev-1",
                fcmToken = "tok-1",
                fcmTokenUpdatedAt = 10L,
                fcmTokenInvalidAt = 20L,
            ),
        )
        assertEquals("tok-1", map[DiagnosticsDocuments.FIELD_FCM_TOKEN])
        assertEquals(10L, map[DiagnosticsDocuments.FIELD_FCM_TOKEN_UPDATED_AT])
        assertEquals(20L, map[DiagnosticsDocuments.FIELD_FCM_TOKEN_INVALID_AT])
    }

    @Test
    fun hasRemoteControl_reflectsTokenAndInvalidMarker() {
        assertFalse(DeviceHealthSummary(deviceId = "d").hasRemoteControl)
        assertTrue(DeviceHealthSummary(deviceId = "d", fcmToken = "t").hasRemoteControl)
        assertFalse(DeviceHealthSummary(deviceId = "d", fcmToken = "t", fcmTokenInvalidAt = 1L).hasRemoteControl)
        assertFalse(DeviceHealthSummary(deviceId = "d", fcmToken = "").hasRemoteControl)
    }
}
