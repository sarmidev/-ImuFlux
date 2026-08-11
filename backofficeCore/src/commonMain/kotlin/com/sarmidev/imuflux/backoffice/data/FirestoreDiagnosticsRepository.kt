package com.sarmidev.imuflux.backoffice.data

import com.sarmidev.imuflux.backoffice.config.BackofficeFirebaseConfig
import com.sarmidev.imuflux.backoffice.platform.createHttpClient
import com.sarmidev.imuflux.backoffice.platform.encodeUrlPathSegment
import com.sarmidev.imuflux.data.diagnostics.DeviceHealthSummary
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsConfig
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsTelemetryRepository
import com.sarmidev.imuflux.data.diagnostics.ImuHealthWindow
import com.sarmidev.imuflux.data.diagnostics.ImuSessionDiagnostics
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Thrown for Firestore read failures, with a UI-safe message. */
class FirestoreDiagnosticsException(
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
 * Writes are intentionally unsupported: the backoffice only observes telemetry.
 */
class FirestoreDiagnosticsRepository(
    private val config: BackofficeFirebaseConfig,
    private val tokenProvider: suspend () -> String,
    private val httpClient: HttpClient = createHttpClient(),
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
            "$documentsBase/${DiagnosticsConfig.DEVICES_COLLECTION}/${encodeUrlPathSegment(deviceId)}",
        ) ?: return null
        return FirestoreRestMapper.toDeviceSummary(document)
    }

    override suspend fun fetchRecentSessions(deviceId: String, limit: Long): List<ImuSessionDiagnostics> {
        val parent = "${DiagnosticsConfig.DEVICES_COLLECTION}/${encodeUrlPathSegment(deviceId)}"
        val body = structuredQuery(
            collectionId = DiagnosticsConfig.SESSIONS_SUBCOLLECTION,
            orderByField = "startedAtWallMs",
            limit = limit,
        )
        return runQuery(url = "$documentsBase/$parent:runQuery", body = body)
            .mapNotNull(FirestoreRestMapper::toSessionDiagnostics)
    }

    override suspend fun fetchRecentHealthWindows(deviceId: String, limit: Long): List<ImuHealthWindow> {
        val parent = "${DiagnosticsConfig.DEVICES_COLLECTION}/${encodeUrlPathSegment(deviceId)}"
        val body = structuredQuery(
            collectionId = DiagnosticsConfig.HEALTH_WINDOWS_SUBCOLLECTION,
            orderByField = "endedAt",
            limit = limit,
        )
        return runQuery(url = "$documentsBase/$parent:runQuery", body = body)
            .mapNotNull(FirestoreRestMapper::toHealthWindow)
    }

    // ── Writes: unsupported (read-only admin dashboard) ─────────────────────

    override suspend fun upsertDeviceSummary(summary: DeviceHealthSummary) = unsupportedWrite()
    override suspend fun writeSessionDiagnostics(session: ImuSessionDiagnostics) = unsupportedWrite()
    override suspend fun writeHealthWindow(window: ImuHealthWindow) = unsupportedWrite()

    private fun unsupportedWrite(): Nothing =
        throw UnsupportedOperationException("Backoffice diagnostics is read-only; writes are performed by the mobile app.")

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

    private suspend fun runQuery(url: String, body: String): List<JsonObject> {
        val text = executePost(url, body)
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull()
            ?: throw FirestoreDiagnosticsException("Respuesta de Firestore inesperada.")
        return array.mapNotNull { element ->
            (element as? JsonObject)?.get("document") as? JsonObject
        }
    }

    private suspend fun getDocument(url: String): JsonObject? {
        val (code, text) = callGet(url)
        return when {
            code == 404 -> null
            code in 200..299 -> runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            else -> throw mapError(code, text)
        }
    }

    private suspend fun executePost(url: String, body: String): String {
        val (code, text) = callPost(url, body)
        if (code !in 200..299) throw mapError(code, text)
        return text
    }

    private suspend fun callPost(url: String, body: String): Pair<Int, String> =
        runCatching {
            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
                header(HttpHeaders.Authorization, "Bearer ${tokenProvider()}")
            }
            response.status.value to response.bodyAsText()
        }.getOrElse { throw FirestoreDiagnosticsException("Error de red al leer diagnostics: ${it.message}") }

    private suspend fun callGet(url: String): Pair<Int, String> =
        runCatching {
            val response = httpClient.get(url) {
                header(HttpHeaders.Authorization, "Bearer ${tokenProvider()}")
            }
            response.status.value to response.bodyAsText()
        }.getOrElse { throw FirestoreDiagnosticsException("Error de red al leer diagnostics: ${it.message}") }

    private fun mapError(code: Int, text: String): FirestoreDiagnosticsException {
        val status = runCatching {
            (json.parseToJsonElement(text).jsonObject["error"] as? JsonObject)
                ?.get("status")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return when {
            code == 401 || code == 403 || status == "PERMISSION_DENIED" || status == "UNAUTHENTICATED" ->
                FirestoreDiagnosticsException(
                    "Permiso denegado. La cuenta debe tener el custom claim admin == true.",
                    isPermissionDenied = true,
                )
            else -> FirestoreDiagnosticsException("Firestore devolvió un error ($code).")
        }
    }
}
