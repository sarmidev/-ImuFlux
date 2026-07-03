package com.sarmidev.imuflux.data.storage

import com.sarmidev.imuflux.domain.model.SessionMetadata
import org.json.JSONArray
import org.json.JSONObject

/**
 * Context-free (de)serializer for `metadata.json`.
 *
 * Extracted from [SessionFileManager] so the canonical-key writing and the
 * legacy-key reading can be unit-tested on the host JVM without an Android
 * `Context`.
 *
 * ### Terminology migration
 * Canonical English keys are written from now on:
 *  - `forkliftModel` (legacy: `forklift_model`)
 *  - `forkliftId`    (legacy: `toro_id`)
 *  - `warehouse`     (unchanged)
 *
 * Reading is **backward compatible**: metadata files produced by older builds
 * (using `forklift_model` / `toro_id`) still load correctly. Canonical keys
 * take precedence when both are present.
 *
 * Note: the CSV column header `forklift_model` is a separate data-file contract
 * (snake_case, see `CsvSchema`/`ARCHITECTURE.md` §6/§11) and is intentionally
 * NOT migrated here — only the `metadata.json` keys are.
 */
object SessionMetadataJson {

    // Canonical (new) keys.
    private const val KEY_FORKLIFT_MODEL = "forkliftModel"
    private const val KEY_FORKLIFT_ID = "forkliftId"
    private const val KEY_WAREHOUSE = "warehouse"

    // Legacy keys kept readable for sessions already stored on disk.
    private const val LEGACY_KEY_FORKLIFT_MODEL = "forklift_model"
    private const val LEGACY_KEY_FORKLIFT_ID = "toro_id"

    /** Serializes a [SessionMetadata] into a `metadata.json` object using canonical keys. */
    fun toJson(metadata: SessionMetadata): JSONObject = JSONObject().apply {
        put("session_id", metadata.sessionId)
        put("started_at_wall_ms", metadata.startedAtWallMs)
        put("started_at_boot_ns", metadata.startedAtBootNs)
        metadata.endedAtWallMs?.let { put("ended_at_wall_ms", it) }
        metadata.endedAtBootNs?.let { put("ended_at_boot_ns", it) }
        put("device", JSONObject().apply {
            put("model", metadata.deviceModel)
            put("manufacturer", metadata.deviceManufacturer)
            put("sdk", metadata.sdkInt)
        })
        put("app_version", metadata.appVersion)
        put("sensors", JSONArray().apply {
            metadata.sensors.forEach { s ->
                put(JSONObject().apply {
                    put("type", s.type)
                    put("name", s.name)
                    put("vendor", s.vendor)
                    put("resolution", s.resolution.toDouble())
                    put("fifo_max_event_count", s.fifoMaxEventCount)
                    put("min_delay_us", s.minDelayUs)
                    put("is_wake_up", s.isWakeUp)
                    put("max_delay_us", s.maxDelayUs)
                })
            }
        })
        put("columns", JSONArray(metadata.columns))
        put("chunk_duration_ms", metadata.chunkDurationMs)
        put("chunk_max_bytes", metadata.chunkMaxBytes)
        metadata.resumeOf?.let { put("resume_of", it) }
        put(KEY_FORKLIFT_MODEL, metadata.forkliftModel)
        put(KEY_WAREHOUSE, metadata.warehouse)
        if (metadata.forkliftId.isNotEmpty()) put(KEY_FORKLIFT_ID, metadata.forkliftId)
        put("watchdog_resurrections", metadata.resurrectionCount)
        if (metadata.requestedSamplingPeriodUs > 0) {
            put("requested_sampling_period_us", metadata.requestedSamplingPeriodUs)
        }
        if (metadata.decimateToHz > 0) put("decimate_to_hz", metadata.decimateToHz)
        val power = JSONObject()
        metadata.batteryOptimizationIgnoredAtStart?.let { power.put("battery_optimization_ignored", it) }
        metadata.deviceIdleModeAtStart?.let { power.put("device_idle_mode", it) }
        metadata.screenInteractiveAtStart?.let { power.put("screen_interactive", it) }
        metadata.chargingAtStart?.let { power.put("charging", it) }
        if (power.length() > 0) put("power_state_at_start", power)
    }

    /**
     * Reconstructs a [SessionMetadata] from a parsed `metadata.json`.
     *
     * @param fallbackSessionId used when the `session_id` field is missing.
     */
    fun fromJson(json: JSONObject, fallbackSessionId: String): SessionMetadata {
        val device = json.optJSONObject("device")
        return SessionMetadata(
            sessionId = json.optString("session_id", fallbackSessionId),
            startedAtWallMs = json.optLong("started_at_wall_ms", 0L),
            startedAtBootNs = json.optLong("started_at_boot_ns", 0L),
            endedAtWallMs = json.optLongOrNull("ended_at_wall_ms"),
            endedAtBootNs = json.optLongOrNull("ended_at_boot_ns"),
            deviceModel = device?.optString("model", "unknown") ?: "unknown",
            deviceManufacturer = device?.optString("manufacturer", "unknown") ?: "unknown",
            sdkInt = device?.optInt("sdk", 0) ?: 0,
            appVersion = json.optString("app_version", ""),
            sensors = json.optJSONArray("sensors").toSensorDescriptors(),
            columns = json.optJSONArray("columns").toStringList(),
            chunkDurationMs = json.optLong("chunk_duration_ms", 0L),
            chunkMaxBytes = json.optLong("chunk_max_bytes", 0L),
            resumeOf = json.optStringOrNull("resume_of"),
            forkliftModel = json.optStringWithLegacy(KEY_FORKLIFT_MODEL, LEGACY_KEY_FORKLIFT_MODEL),
            warehouse = json.optString(KEY_WAREHOUSE, ""),
            forkliftId = json.optStringWithLegacy(KEY_FORKLIFT_ID, LEGACY_KEY_FORKLIFT_ID),
            resurrectionCount = json.optInt("watchdog_resurrections", 0),
            requestedSamplingPeriodUs = json.optInt("requested_sampling_period_us", 0),
            decimateToHz = json.optInt("decimate_to_hz", 0),
            batteryOptimizationIgnoredAtStart = json.optJSONObject("power_state_at_start")
                ?.optBooleanOrNull("battery_optimization_ignored"),
            deviceIdleModeAtStart = json.optJSONObject("power_state_at_start")
                ?.optBooleanOrNull("device_idle_mode"),
            screenInteractiveAtStart = json.optJSONObject("power_state_at_start")
                ?.optBooleanOrNull("screen_interactive"),
            chargingAtStart = json.optJSONObject("power_state_at_start")
                ?.optBooleanOrNull("charging"),
        )
    }

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
        if (has(name) && !isNull(name)) optBoolean(name) else null

    /**
     * Reads [newKey] (canonical) and falls back to [legacyKey] when the
     * canonical value is absent or empty. Lets legacy `metadata.json` files
     * keep loading after the terminology migration.
     */
    fun JSONObject.optStringWithLegacy(newKey: String, legacyKey: String, default: String = ""): String {
        val canonical = optString(newKey, "")
        if (canonical.isNotEmpty()) return canonical
        return optString(legacyKey, default)
    }

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name) else null

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotEmpty() } else null

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { index -> optString(index) }
    }

    private fun JSONArray?.toSensorDescriptors(): List<SessionMetadata.SensorDescriptor> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            val sensor = optJSONObject(index) ?: JSONObject()
            SessionMetadata.SensorDescriptor(
                type = sensor.optString("type", ""),
                name = sensor.optString("name", ""),
                vendor = sensor.optString("vendor", ""),
                resolution = sensor.optDouble("resolution", 0.0).toFloat(),
                fifoMaxEventCount = sensor.optInt("fifo_max_event_count", 0),
                minDelayUs = sensor.optInt("min_delay_us", 0),
                isWakeUp = sensor.optBoolean("is_wake_up", false),
                maxDelayUs = sensor.optInt("max_delay_us", 0),
            )
        }
    }
}
