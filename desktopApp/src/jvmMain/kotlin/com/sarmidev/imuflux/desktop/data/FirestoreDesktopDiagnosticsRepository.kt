package com.sarmidev.imuflux.desktop.data

import com.sarmidev.imuflux.data.diagnostics.DeviceHealthSummary
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsConfig
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsTelemetryRepository
import com.sarmidev.imuflux.data.diagnostics.ImuHealthWindow
import com.sarmidev.imuflux.data.diagnostics.ImuSessionDiagnostics
import com.sarmidev.imuflux.desktop.config.DesktopFirebaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Thrown for Firestore read failures, with a UI-safe message. */
class FirestoreDesktopException(
    message: String,
    /** True when the failure is a missing `admin` custom claim / rules denial. */
    val isPermissionDenied: Boolean = false,
) : Exception(message)

/**
 * Read-only [DiagnosticsTelemetryRepository] backed by the Firestore REST API.
 *
 * Authentication is a Bearer `idToken` obtained from an admin Firebase user (see
 * `FirebaseAuthClient`). Reads use `runQuery` structured queries so ordering and
 * limiting happen server-side, matching the Android repository's `orderBy`/`limit`.
 *
 * Writes are intentionally unsupported: desktop is an admin dashboard that only
 * observes telemetry in this phase.
 */
class FirestoreDesktopDiagnosticsRepository(
    private val config: DesktopFirebaseConfig,
    private val tokenProvider: suspend () -> String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder().build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : DiagnosticsTelemetryRepository {

    private val documentsBase =
        "https://firestore.googleapis.com/v1/projects/${config.projectId}/databases/(default)/documents"

    // ── Reads ──────────────────────────────────────────────────────────────

    override suspend fun fetchDevices(limit: Long): List<DeviceHealthSummary> {
        val body = structuredQuery(
            collectionId = DiagnosticsConfig.DEVICES_COLLECTION,
            orderByField = "lastSeenAt",
            limit = limit,
        )
        return runQuery(url = "$documentsBase:runQuery", body = body)
            .mapNotNull(FirestoreRestMapper::toDeviceSummary)
    }

    override suspend fun fetchDevice(deviceId: String): DeviceHealthSummary? {
        val document = getDocument(
            "$documentsBase/${DiagnosticsConfig.DEVICES_COLLECTION}/${encode(deviceId)}",
        ) ?: return null
        return FirestoreRestMapper.toDeviceSummary(document)
    }

    override suspend fun fetchRecentSessions(deviceId: String, limit: Long): List<ImuSessionDiagnostics> {
        val parent = "${DiagnosticsConfig.DEVICES_COLLECTION}/${encode(deviceId)}"
        val body = structuredQuery(
            collectionId = DiagnosticsConfig.SESSIONS_SUBCOLLECTION,
            orderByField = "startedAtWallMs",
            limit = limit,
        )
        return runQuery(url = "$documentsBase/$parent:runQuery", body = body)
            .mapNotNull(FirestoreRestMapper::toSessionDiagnostics)
    }

    override suspend fun fetchRecentHealthWindows(deviceId: String, limit: Long): List<ImuHealthWindow> {
        val parent = "${DiagnosticsConfig.DEVICES_COLLECTION}/${encode(deviceId)}"
        val body = structuredQuery(
            collectionId = DiagnosticsConfig.HEALTH_WINDOWS_SUBCOLLECTION,
            orderByField = "endedAt",
            limit = limit,
        )
        return runQuery(url = "$documentsBase/$parent:runQuery", body = body)
            .mapNotNull(FirestoreRestMapper::toHealthWindow)
    }

    // ── Writes: unsupported on desktop (read-only admin dashboard) ──────────

    override suspend fun upsertDeviceSummary(summary: DeviceHealthSummary) = unsupportedWrite()
    override suspend fun writeSessionDiagnostics(session: ImuSessionDiagnostics) = unsupportedWrite()
    override suspend fun writeHealthWindow(window: ImuHealthWindow) = unsupportedWrite()

    private fun unsupportedWrite(): Nothing =
        throw UnsupportedOperationException("Desktop diagnostics is read-only; writes are performed by the mobile app.")

    // ── HTTP internals ──────────────────────────────────────────────────────

    private fun structuredQuery(collectionId: String, orderByField: String, limit: Long): String =
        """
        {
          "structuredQuery": {
            "from": [{"collectionId": "$collectionId"}],
            "orderBy": [{"field": {"fieldPath": "$orderByField"}, "direction": "DESCENDING"}],
            "limit": $limit
          }
        }
        """.trimIndent()

    private suspend fun runQuery(url: String, body: String): List<JsonObject> = withContext(Dispatchers.IO) {
        val text = execute(
            Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .header("Authorization", "Bearer ${tokenProvider()}")
                .build(),
        )
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull()
            ?: throw FirestoreDesktopException("Respuesta de Firestore inesperada.")
        array.mapNotNull { element ->
            (element as? JsonObject)?.get("document") as? JsonObject
        }
    }

    private suspend fun getDocument(url: String): JsonObject? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer ${tokenProvider()}")
            .build()
        val (code, text) = call(request)
        when {
            code == 404 -> null
            code in 200..299 -> runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            else -> throw mapError(code, text)
        }
    }

    private fun execute(request: Request): String {
        val (code, text) = call(request)
        if (code !in 200..299) throw mapError(code, text)
        return text
    }

    private fun call(request: Request): Pair<Int, String> =
        runCatching {
            httpClient.newCall(request).execute().use { resp ->
                resp.code to (resp.body?.string().orEmpty())
            }
        }.getOrElse { throw FirestoreDesktopException("Error de red al leer diagnostics: ${it.message}") }

    private fun mapError(code: Int, text: String): FirestoreDesktopException {
        val status = runCatching {
            (json.parseToJsonElement(text).jsonObject["error"] as? JsonObject)
                ?.get("status")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return when {
            code == 401 || code == 403 || status == "PERMISSION_DENIED" || status == "UNAUTHENTICATED" ->
                FirestoreDesktopException(
                    "Permiso denegado. La cuenta debe tener el custom claim admin == true.",
                    isPermissionDenied = true,
                )
            else -> FirestoreDesktopException("Firestore devolvió un error ($code).")
        }
    }

    private fun encode(segment: String): String =
        java.net.URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")

    private companion object {
        const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
    }
}
