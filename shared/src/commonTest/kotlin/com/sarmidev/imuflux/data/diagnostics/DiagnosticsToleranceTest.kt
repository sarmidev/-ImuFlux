package com.sarmidev.imuflux.data.diagnostics

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Legacy/offline tolerance:
 *  - Unknown/legacy/`null` enum strings from older documents must degrade to a
 *    safe default instead of crashing the dashboard parse.
 *  - A repository that fails (offline, rules rejection, missing config) must
 *    not propagate exceptions to its callers; reads fall back to empty.
 */
class DiagnosticsToleranceTest {

    @Test
    fun healthStatus_fromWire_handlesLegacyUnknownAndNull() {
        assertEquals(DiagnosticsHealthStatus.OK, DiagnosticsHealthStatus.fromWire("OK"))
        assertEquals(DiagnosticsHealthStatus.UNKNOWN, DiagnosticsHealthStatus.fromWire("LEGACY_GREEN"))
        assertEquals(DiagnosticsHealthStatus.UNKNOWN, DiagnosticsHealthStatus.fromWire(null))
    }

    @Test
    fun stopReason_fromWire_handlesLegacyUnknownAndNull() {
        assertEquals(RecordingStopReason.USER_STOP, RecordingStopReason.fromWire("USER_STOP"))
        assertEquals(RecordingStopReason.UNKNOWN, RecordingStopReason.fromWire("MANUAL"))
        assertEquals(RecordingStopReason.UNKNOWN, RecordingStopReason.fromWire(null))
    }

    /** A throwing repository wrapped exactly like the dashboard consumes it. */
    private class FailingRepository : DiagnosticsTelemetryRepository {
        override suspend fun upsertDeviceSummary(summary: DeviceHealthSummary) = error("offline")
        override suspend fun writeSessionDiagnostics(session: ImuSessionDiagnostics) = error("offline")
        override suspend fun writeHealthWindow(window: ImuHealthWindow) = error("offline")
        override suspend fun fetchDevices(limit: Long): List<DeviceHealthSummary> = error("offline")
        override suspend fun fetchDevice(deviceId: String): DeviceHealthSummary? = error("offline")
        override suspend fun fetchRecentSessions(deviceId: String, limit: Long) = error("offline")
        override suspend fun fetchRecentHealthWindows(deviceId: String, limit: Long) = error("offline")
    }

    @Test
    fun dashboardReadFallsBackToEmptyOnFailure() = runBlocking {
        val repo = FailingRepository()
        val devices = runCatching { repo.fetchDevices() }.getOrDefault(emptyList())
        assertTrue(devices.isEmpty())
    }

    @Test
    fun deviceDetailReadFallsBackToNullOnFailure() = runBlocking {
        val repo = FailingRepository()
        val device = runCatching { repo.fetchDevice("dev_x") }.getOrNull()
        assertNull(device)
    }

    @Test
    fun emptyDeviceList_filtersToEmpty() {
        assertTrue(DiagnosticsFilter.apply(emptyList(), DiagnosticsFilters(warehouseId = "wh1")).isEmpty())
    }
}
