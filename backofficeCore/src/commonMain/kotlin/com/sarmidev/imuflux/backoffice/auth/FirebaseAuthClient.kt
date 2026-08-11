package com.sarmidev.imuflux.backoffice.auth

import com.sarmidev.imuflux.backoffice.config.BackofficeFirebaseConfig
import com.sarmidev.imuflux.backoffice.platform.createHttpClient
import com.sarmidev.imuflux.backoffice.platform.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Authenticated admin session held purely in memory.
 *
 * [expiresAtMs] is the wall-clock instant at which [idToken] should be considered
 * expired; a small safety margin is applied by [AdminSession.isExpired].
 */
data class AdminSession(
    val idToken: String,
    val refreshToken: String,
    val localId: String,
    val email: String,
    val expiresAtMs: Long,
) {
    fun isExpired(nowMs: Long = currentTimeMillis()): Boolean =
        nowMs >= expiresAtMs - EXPIRY_MARGIN_MS

    private companion object {
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}

/** Thrown for any Auth failure, with a message safe to show in the UI. */
class FirebaseAuthException(message: String, val isCredentialError: Boolean = false) :
    Exception(message)

/**
 * Minimal Firebase Auth client against the Identity Toolkit / Secure Token REST
 * APIs. Uses email/password sign-in and refresh-token flows.
 *
 * No secrets are stored: the API key comes from [BackofficeFirebaseConfig] which is
 * resolved from the environment, a git-ignored local file, or the web config bridge.
 */
class FirebaseAuthClient(
    private val config: BackofficeFirebaseConfig,
    private val httpClient: HttpClient = createHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** Signs in with email/password and returns an in-memory [AdminSession]. */
    suspend fun signIn(email: String, password: String): AdminSession {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
            put("returnSecureToken", true)
        }
        val response = postJson(
            url = "$IDENTITY_BASE/accounts:signInWithPassword?key=${config.apiKey}",
            jsonBody = body.toString(),
        )
        val idToken = response.stringField("idToken")
            ?: throw FirebaseAuthException("Respuesta de login inesperada (falta idToken).")
        val refreshToken = response.stringField("refreshToken").orEmpty()
        val localId = response.stringField("localId").orEmpty()
        val returnedEmail = response.stringField("email") ?: email
        val expiresInSec = response.stringField("expiresIn")?.toLongOrNull() ?: 3600L
        return AdminSession(
            idToken = idToken,
            refreshToken = refreshToken,
            localId = localId,
            email = returnedEmail,
            expiresAtMs = currentTimeMillis() + expiresInSec * 1000L,
        )
    }

    /** Exchanges a refresh token for a fresh id token. */
    suspend fun refresh(session: AdminSession): AdminSession {
        if (session.refreshToken.isBlank()) {
            throw FirebaseAuthException("La sesión expiró. Vuelve a iniciar sesión.")
        }
        val form = "grant_type=refresh_token&refresh_token=${session.refreshToken}"
        val response = postForm(
            url = "$SECURE_TOKEN_BASE/token?key=${config.apiKey}",
            formBody = form,
        )
        val idToken = response.stringField("id_token")
            ?: throw FirebaseAuthException("No se pudo refrescar la sesión. Vuelve a iniciar sesión.")
        val refreshToken = response.stringField("refresh_token").orEmpty().ifEmpty { session.refreshToken }
        val expiresInSec = response.stringField("expires_in")?.toLongOrNull() ?: 3600L
        return session.copy(
            idToken = idToken,
            refreshToken = refreshToken,
            expiresAtMs = currentTimeMillis() + expiresInSec * 1000L,
        )
    }

    private suspend fun postJson(url: String, jsonBody: String): JsonObject {
        val (code, text) = runCatching {
            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }
            response.status.value to response.bodyAsText()
        }.getOrElse { throw FirebaseAuthException("Error de red durante el login: ${it.message}") }
        return parseAuthResponse(code, text)
    }

    private suspend fun postForm(url: String, formBody: String): JsonObject {
        val (code, text) = runCatching {
            val response = httpClient.post(url) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(formBody)
            }
            response.status.value to response.bodyAsText()
        }.getOrElse { throw FirebaseAuthException("Error de red durante el login: ${it.message}") }
        return parseAuthResponse(code, text)
    }

    private fun parseAuthResponse(code: Int, text: String): JsonObject {
        val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (code !in 200..299) {
            val message = parsed?.errorMessage()
            throw mapAuthError(message)
        }
        return parsed ?: throw FirebaseAuthException("Respuesta de autenticación no válida.")
    }

    private fun mapAuthError(code: String?): FirebaseAuthException = when (code) {
        "EMAIL_NOT_FOUND", "INVALID_PASSWORD", "INVALID_LOGIN_CREDENTIALS", "INVALID_EMAIL" ->
            FirebaseAuthException("Email o contraseña incorrectos.", isCredentialError = true)
        "USER_DISABLED" ->
            FirebaseAuthException("La cuenta está deshabilitada.", isCredentialError = true)
        "TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN", "USER_NOT_FOUND" ->
            FirebaseAuthException("La sesión expiró. Vuelve a iniciar sesión.")
        null -> FirebaseAuthException("Fallo de autenticación desconocido.")
        else -> FirebaseAuthException("Fallo de autenticación: $code")
    }

    private fun JsonObject.stringField(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.errorMessage(): String? =
        (this["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull

    private companion object {
        const val IDENTITY_BASE = "https://identitytoolkit.googleapis.com/v1"
        const val SECURE_TOKEN_BASE = "https://securetoken.googleapis.com/v1"
    }
}
