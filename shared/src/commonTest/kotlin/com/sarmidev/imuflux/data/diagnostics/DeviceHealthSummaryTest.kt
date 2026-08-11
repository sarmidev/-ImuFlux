package com.sarmidev.imuflux.data.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceHealthSummaryTest {

    @Test
    fun lastRecordingAt_defaultsToZeroWhenNeverRecorded() {
        val device = DeviceHealthSummary(deviceId = "d1")
        assertEquals(0L, device.lastRecordingAt)
    }

    @Test
    fun lastRecordingAt_usesStartedWhenEndedIsNull() {
        val device = DeviceHealthSummary(
            deviceId = "d1",
            lastRecordingStartedAt = 1_000L,
            lastRecordingEndedAt = null,
        )
        assertEquals(1_000L, device.lastRecordingAt)
    }

    @Test
    fun lastRecordingAt_usesEndedWhenStartedIsNull() {
        val device = DeviceHealthSummary(
            deviceId = "d1",
            lastRecordingStartedAt = null,
            lastRecordingEndedAt = 2_000L,
        )
        assertEquals(2_000L, device.lastRecordingAt)
    }

    @Test
    fun lastRecordingAt_picksMaxOfStartedAndEnded() {
        val startedLater = DeviceHealthSummary(
            deviceId = "d1",
            lastRecordingStartedAt = 5_000L,
            lastRecordingEndedAt = 4_000L,
        )
        assertEquals(5_000L, startedLater.lastRecordingAt)

        val endedLater = DeviceHealthSummary(
            deviceId = "d2",
            lastRecordingStartedAt = 3_000L,
            lastRecordingEndedAt = 6_000L,
        )
        assertEquals(6_000L, endedLater.lastRecordingAt)
    }
}
