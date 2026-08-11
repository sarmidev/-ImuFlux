package com.sarmidev.imuflux.data.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticsFilterTest {

    private fun device(
        id: String,
        warehouse: String = "wh1",
        forklift: String = "f1",
        appVersion: String = "1.0",
        status: DiagnosticsHealthStatus = DiagnosticsHealthStatus.OK,
        lastSeen: Long = 1_000L,
    ) = DeviceHealthSummary(
        deviceId = id,
        warehouseId = warehouse,
        forkliftId = forklift,
        appVersion = appVersion,
        currentHealthStatus = status,
        lastSeenAt = lastSeen,
    )

    private val devices = listOf(
        device("a", warehouse = "wh1", forklift = "f1", appVersion = "1.0", status = DiagnosticsHealthStatus.OK, lastSeen = 100L),
        device("b", warehouse = "wh2", forklift = "f2", appVersion = "1.1", status = DiagnosticsHealthStatus.ERROR, lastSeen = 200L),
        device("c", warehouse = "wh1", forklift = "f3", appVersion = "1.1", status = DiagnosticsHealthStatus.WARNING, lastSeen = 300L),
    )

    @Test
    fun noFilters_returnsAll() {
        assertEquals(3, DiagnosticsFilter.apply(devices, DiagnosticsFilters()).size)
    }

    @Test
    fun byWarehouse() {
        val r = DiagnosticsFilter.apply(devices, DiagnosticsFilters(warehouseId = "wh1"))
        assertEquals(listOf("a", "c"), r.map { it.deviceId })
    }

    @Test
    fun byForklift() {
        val r = DiagnosticsFilter.apply(devices, DiagnosticsFilters(forkliftId = "f2"))
        assertEquals(listOf("b"), r.map { it.deviceId })
    }

    @Test
    fun byDevice_isCaseInsensitive() {
        val r = DiagnosticsFilter.apply(devices, DiagnosticsFilters(deviceId = "A"))
        assertEquals(listOf("a"), r.map { it.deviceId })
    }

    @Test
    fun byAppVersion() {
        val r = DiagnosticsFilter.apply(devices, DiagnosticsFilters(appVersion = "1.1"))
        assertEquals(listOf("b", "c"), r.map { it.deviceId })
    }

    @Test
    fun byHealthStatus() {
        val r = DiagnosticsFilter.apply(devices, DiagnosticsFilters(healthStatus = DiagnosticsHealthStatus.ERROR))
        assertEquals(listOf("b"), r.map { it.deviceId })
    }

    @Test
    fun bySeenSince() {
        val r = DiagnosticsFilter.apply(devices, DiagnosticsFilters(seenSinceMs = 250L))
        assertEquals(listOf("c"), r.map { it.deviceId })
    }

    @Test
    fun combinedFilters() {
        val r = DiagnosticsFilter.apply(
            devices,
            DiagnosticsFilters(warehouseId = "wh1", appVersion = "1.1"),
        )
        assertEquals(listOf("c"), r.map { it.deviceId })
    }
}
