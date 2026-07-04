package com.sarmidev.imuflux.data.diagnostics

/**
 * Persistence boundary for lightweight, already-aggregated diagnostics.
 *
 * Writes are **fire-and-forget and failure-tolerant**: an offline device, a
 * missing `google-services.json`, or any Firestore error must never propagate
 * to the recording or upload paths. Implementations therefore swallow and log
 * their own errors and the write methods do not throw.
 *
 * Reads are used only by the admin dashboard and may return empty lists on
 * failure.
 */
interface DiagnosticsTelemetryRepository {

    /** Upserts the device-level summary doc `/diagnosticsDevices/{deviceId}`. */
    suspend fun upsertDeviceSummary(summary: DeviceHealthSummary)

    /** Writes/merges a session doc `…/{deviceId}/sessions/{sessionId}`. */
    suspend fun writeSessionDiagnostics(session: ImuSessionDiagnostics)

    /** Writes a health window doc `…/{deviceId}/healthWindows/{windowId}`. */
    suspend fun writeHealthWindow(window: ImuHealthWindow)

    // ── Dashboard reads ───────────────────────────────────────────────────

    /** Returns up to [limit] device summaries, most recently seen first. */
    suspend fun fetchDevices(limit: Long = 200L): List<DeviceHealthSummary>

    suspend fun fetchDevice(deviceId: String): DeviceHealthSummary?

    suspend fun fetchRecentSessions(deviceId: String, limit: Long = 20L): List<ImuSessionDiagnostics>

    suspend fun fetchRecentHealthWindows(deviceId: String, limit: Long = 30L): List<ImuHealthWindow>
}
