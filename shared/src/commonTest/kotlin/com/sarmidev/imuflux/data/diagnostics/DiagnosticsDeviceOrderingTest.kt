package com.sarmidev.imuflux.data.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticsDeviceOrderingTest {

    private fun device(
        id: String,
        isRecording: Boolean = false,
        lastRecordingStartedAt: Long? = null,
        lastRecordingEndedAt: Long? = null,
        lastSeenAt: Long = 0L,
        status: DiagnosticsHealthStatus = DiagnosticsHealthStatus.OK,
    ) = DeviceHealthSummary(
        deviceId = id,
        isRecording = isRecording,
        lastRecordingStartedAt = lastRecordingStartedAt,
        lastRecordingEndedAt = lastRecordingEndedAt,
        lastSeenAt = lastSeenAt,
        currentHealthStatus = status,
    )

    @Test
    fun recordingDevices_pinAboveNonRecording_evenWithOlderLastSeen() {
        val devices = listOf(
            device("idle-recent", isRecording = false, lastSeenAt = 9_000L, lastRecordingEndedAt = 8_000L),
            device("recording-stale", isRecording = true, lastSeenAt = 100L, lastRecordingStartedAt = 50L),
        )

        val ordered = DiagnosticsDeviceOrdering.sort(devices).map { it.deviceId }
        assertEquals(listOf("recording-stale", "idle-recent"), ordered)
    }

    @Test
    fun nonRecording_orderedByMostRecentRecording() {
        val devices = listOf(
            device("older-rec", lastRecordingEndedAt = 1_000L, lastSeenAt = 9_000L),
            device("newer-rec", lastRecordingEndedAt = 5_000L, lastSeenAt = 100L),
            device("never-rec", lastSeenAt = 8_000L),
        )

        val ordered = DiagnosticsDeviceOrdering.sort(devices).map { it.deviceId }
        assertEquals(listOf("newer-rec", "older-rec", "never-rec"), ordered)
    }

    @Test
    fun neverRecorded_fallbackToLastSeenAt() {
        val devices = listOf(
            device("seen-old", lastSeenAt = 100L),
            device("seen-new", lastSeenAt = 500L),
        )

        val ordered = DiagnosticsDeviceOrdering.sort(devices).map { it.deviceId }
        assertEquals(listOf("seen-new", "seen-old"), ordered)
    }

    @Test
    fun healthStatus_noLongerDrivesOrdering() {
        val devices = listOf(
            device(
                "error-old-rec",
                status = DiagnosticsHealthStatus.ERROR,
                lastRecordingEndedAt = 100L,
                lastSeenAt = 100L,
            ),
            device(
                "ok-recent-rec",
                status = DiagnosticsHealthStatus.OK,
                lastRecordingEndedAt = 9_000L,
                lastSeenAt = 9_000L,
            ),
        )

        val ordered = DiagnosticsDeviceOrdering.sort(devices).map { it.deviceId }
        assertEquals(listOf("ok-recent-rec", "error-old-rec"), ordered)
    }

    @Test
    fun multipleRecording_sortedByLastRecordingThenLastSeen() {
        val devices = listOf(
            device(
                "rec-a",
                isRecording = true,
                lastRecordingStartedAt = 2_000L,
                lastSeenAt = 100L,
            ),
            device(
                "rec-b",
                isRecording = true,
                lastRecordingStartedAt = 3_000L,
                lastSeenAt = 50L,
            ),
            device(
                "idle",
                isRecording = false,
                lastRecordingEndedAt = 9_999L,
                lastSeenAt = 9_999L,
            ),
        )

        val ordered = DiagnosticsDeviceOrdering.sort(devices).map { it.deviceId }
        assertEquals(listOf("rec-b", "rec-a", "idle"), ordered)
    }
}
