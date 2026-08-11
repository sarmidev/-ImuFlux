package com.sarmidev.imuflux.data.diagnostics

/**
 * Dashboard device list ordering:
 * 1. Currently recording devices first
 * 2. Then by most recent recording activity ([DeviceHealthSummary.lastRecordingAt])
 * 3. Then by [DeviceHealthSummary.lastSeenAt] as a stable fallback
 */
object DiagnosticsDeviceOrdering {

    val comparator: Comparator<DeviceHealthSummary> =
        compareByDescending<DeviceHealthSummary> { it.isRecording }
            .thenByDescending { it.lastRecordingAt }
            .thenByDescending { it.lastSeenAt }

    fun sort(devices: List<DeviceHealthSummary>): List<DeviceHealthSummary> =
        devices.sortedWith(comparator)
}
