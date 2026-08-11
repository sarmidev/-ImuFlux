package com.sarmidev.imuflux.backoffice.state

import com.sarmidev.imuflux.backoffice.auth.AdminSession
import com.sarmidev.imuflux.backoffice.auth.FirebaseAuthClient
import com.sarmidev.imuflux.backoffice.auth.FirebaseAuthException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the admin session in memory and transparently refreshes the id token
 * before it expires. Exposed to the Firestore repository as a `suspend () -> String`
 * token provider.
 */
class SessionManager(private val authClient: FirebaseAuthClient) {

    private var session: AdminSession? = null
    private val refreshMutex = Mutex()

    val currentSession: AdminSession? get() = session

    suspend fun signIn(email: String, password: String): AdminSession {
        val newSession = authClient.signIn(email.trim(), password)
        session = newSession
        return newSession
    }

    fun signOut() {
        session = null
    }

    /**
     * Returns a valid id token, refreshing it if it has (nearly) expired.
     * Throws [FirebaseAuthException] if there is no session or refresh fails.
     */
    suspend fun validToken(): String {
        val current = session ?: throw FirebaseAuthException("No hay sesión activa. Inicia sesión.")
        if (!current.isExpired()) return current.idToken
        return refreshMutex.withLock {
            val latest = session ?: throw FirebaseAuthException("No hay sesión activa. Inicia sesión.")
            if (!latest.isExpired()) {
                latest.idToken
            } else {
                val refreshed = authClient.refresh(latest)
                session = refreshed
                refreshed.idToken
            }
        }
    }
}
