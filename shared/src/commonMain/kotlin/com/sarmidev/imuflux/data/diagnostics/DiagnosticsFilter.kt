package com.sarmidev.imuflux.data.diagnostics

/**
 * Dashboard filter criteria. All fields are optional; `null`/blank means "any".
 * Pure data so the filtering logic in [DiagnosticsFilter] is unit-testable.
 */
data class DiagnosticsFilters(
    val warehouseId: String? = null,
    val forkliftId: String? = null,
    val deviceId: String? = null,
    val appVersion: String? = null,
    val healthStatus: DiagnosticsHealthStatus? = null,
    /** Only devices seen at/after this wall-clock ms (inclusive). */
    val seenSinceMs: Long? = null,
)

/** Pure filtering of device summaries. Kept side-effect free for testing. */
object DiagnosticsFilter {

    fun apply(devices: List<DeviceHealthSummary>, filters: DiagnosticsFilters): List<DeviceHealthSummary> =
        devices.filter { device ->
            matches(filters.warehouseId, device.warehouseId) &&
                matches(filters.forkliftId, device.forkliftId) &&
                matches(filters.deviceId, device.deviceId) &&
                matches(filters.appVersion, device.appVersion) &&
                (filters.healthStatus == null || device.currentHealthStatus == filters.healthStatus) &&
                (filters.seenSinceMs == null || device.lastSeenAt >= filters.seenSinceMs)
        }

    private fun matches(filter: String?, value: String): Boolean {
        val f = filter?.trim().orEmpty()
        return f.isEmpty() || value.equals(f, ignoreCase = true)
    }
}
