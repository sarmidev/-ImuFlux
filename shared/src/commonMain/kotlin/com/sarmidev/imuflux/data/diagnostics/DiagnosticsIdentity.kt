package com.sarmidev.imuflux.data.diagnostics

/**
 * Stable identity of the physical device + app build. Produced by
 * `DeviceIdentityProvider` in the Android app; never contains user-entered
 * (assignment) data.
 */
data class DeviceIdentity(
    val deviceId: String,
    val appVersion: String,
    val buildNumber: Long,
    val androidVersion: Int,
    val deviceModel: String,
    val manufacturer: String,
)

/**
 * Current operational assignment of the device, entered by the operator
 * (forklift + warehouse). Produced by `DeviceAssignmentProvider` in the Android app.
 *
 * Uses canonical English terminology (post terminology migration):
 * `forkliftId`, `forkliftModel`, `warehouseId`, `warehouseName`.
 */
data class DeviceAssignment(
    val forkliftId: String,
    val forkliftModel: String,
    val warehouseId: String,
    val warehouseName: String,
) {
    companion object {
        val EMPTY = DeviceAssignment(
            forkliftId = "",
            forkliftModel = "",
            warehouseId = "",
            warehouseName = "",
        )
    }
}
