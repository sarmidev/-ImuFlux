package com.sarmidev.imuflux.data.storage

import com.sarmidev.imuflux.domain.model.SessionMetadata
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the forklift/warehouse terminology migration in `metadata.json`.
 *
 * Guarantees:
 *  - New metadata is written with the canonical English keys
 *    (`forkliftModel`, `warehouse`, `forkliftId`) and NOT the legacy ones.
 *  - Legacy `metadata.json` files (`forklift_model`, `toro_id`) still load.
 *  - When both legacy and canonical keys exist, the canonical one wins.
 *  - A round-trip preserves the values.
 */
class SessionMetadataJsonTest {

    private fun baseMetadata(
        forkliftModel: String = "RX-50",
        warehouse: String = "ZAR-A",
        forkliftId: String = "RX-50_7f3a2b",
    ) = SessionMetadata(
        sessionId = "20260417_103045",
        startedAtWallMs = 1753171845000L,
        startedAtBootNs = 123456789012345L,
        deviceModel = "Pixel 7",
        deviceManufacturer = "Google",
        sdkInt = 34,
        appVersion = "1.0",
        sensors = emptyList(),
        columns = listOf("timestamp_ns", "lin_x"),
        chunkDurationMs = 300000L,
        chunkMaxBytes = 20971520L,
        forkliftModel = forkliftModel,
        warehouse = warehouse,
        forkliftId = forkliftId,
    )

    @Test
    fun newMetadata_writesCanonicalEnglishKeys() {
        val json = SessionMetadataJson.toJson(baseMetadata())

        assertTrue("forkliftModel must be written", json.has("forkliftModel"))
        assertTrue("forkliftId must be written", json.has("forkliftId"))
        assertTrue("warehouse must be written", json.has("warehouse"))

        assertFalse("legacy forklift_model must not be written", json.has("forklift_model"))
        assertFalse("legacy toro_id must not be written", json.has("toro_id"))

        assertEquals("RX-50", json.getString("forkliftModel"))
        assertEquals("RX-50_7f3a2b", json.getString("forkliftId"))
        assertEquals("ZAR-A", json.getString("warehouse"))
    }

    @Test
    fun emptyForkliftId_isOmitted() {
        val json = SessionMetadataJson.toJson(baseMetadata(forkliftId = ""))
        assertFalse(json.has("forkliftId"))
        assertFalse(json.has("toro_id"))
    }

    @Test
    fun legacyMetadata_isStillReadable() {
        val legacy = JSONObject(
            """
            {
              "session_id": "20240101_000000",
              "started_at_wall_ms": 1700000000000,
              "started_at_boot_ns": 999,
              "device": { "model": "Galaxy", "manufacturer": "samsung", "sdk": 33 },
              "app_version": "0.9",
              "columns": ["timestamp_ns"],
              "chunk_duration_ms": 300000,
              "chunk_max_bytes": 20971520,
              "forklift_model": "OLD-RX",
              "warehouse": "OLD-WH",
              "toro_id": "OLD-RX_aaa111"
            }
            """.trimIndent(),
        )

        val metadata = SessionMetadataJson.fromJson(legacy, fallbackSessionId = "fallback")

        assertEquals("OLD-RX", metadata.forkliftModel)
        assertEquals("OLD-WH", metadata.warehouse)
        assertEquals("OLD-RX_aaa111", metadata.forkliftId)
    }

    @Test
    fun canonicalKeys_takePrecedenceOverLegacy() {
        val mixed = JSONObject(
            """
            {
              "session_id": "mix",
              "forklift_model": "LEGACY",
              "toro_id": "LEGACY_ID",
              "forkliftModel": "CANONICAL",
              "forkliftId": "CANONICAL_ID",
              "warehouse": "WH"
            }
            """.trimIndent(),
        )

        val metadata = SessionMetadataJson.fromJson(mixed, fallbackSessionId = "mix")

        assertEquals("CANONICAL", metadata.forkliftModel)
        assertEquals("CANONICAL_ID", metadata.forkliftId)
    }

    @Test
    fun roundTrip_preservesValues() {
        val original = baseMetadata()
        val restored = SessionMetadataJson.fromJson(
            SessionMetadataJson.toJson(original),
            fallbackSessionId = "unused",
        )

        assertEquals(original.sessionId, restored.sessionId)
        assertEquals(original.forkliftModel, restored.forkliftModel)
        assertEquals(original.warehouse, restored.warehouse)
        assertEquals(original.forkliftId, restored.forkliftId)
        assertEquals(original.startedAtWallMs, restored.startedAtWallMs)
        assertEquals(original.columns, restored.columns)
    }

    @Test
    fun missingSessionId_usesFallback() {
        val metadata = SessionMetadataJson.fromJson(JSONObject("{}"), fallbackSessionId = "fb")
        assertEquals("fb", metadata.sessionId)
    }
}
