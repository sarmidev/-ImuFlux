package com.sarmidev.imuflux.data.diagnostics

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists this device's FCM registration token into
 * `diagnosticsDevices/{deviceId}` so the admin backend can push remote
 * recording commands.
 *
 * ### deviceId
 * Uses the exact same [DeviceIdentityProvider] anonymous Firebase UID as the
 * diagnostics aggregator, so the token lands on the same document the dashboard
 * already shows and Firestore's `isOwner(deviceId)` rule is satisfied.
 *
 * ### Not clobbering health
 * The device cannot read its own `diagnosticsDevices` document (dashboard reads
 * are admin-only), so we can't check existence first. Instead we:
 *   1. Try `update()` with only the token fields + a fresh `lastSeenAt`. On an
 *      existing document this preserves the real `currentHealthStatus` and every
 *      other field.
 *   2. If the document does not exist yet, `update()` fails with NOT_FOUND, so we
 *      fall back to `set(merge)` with a **minimal, rules-valid** document that
 *      carries `currentHealthStatus = UNKNOWN` (never fabricated metrics).
 *
 * A later [ImuDiagnosticsAggregator] summary merge never carries a token
 * ([DiagnosticsDocuments.deviceToMap] omits null FCM fields), so it can't erase
 * what we write here.
 *
 * ### Failure tolerance
 * Every operation is fire-and-forget and swallows errors: token registration
 * must never affect recording or diagnostics.
 */
@Singleton
class FcmTokenRegistrar @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val messaging: FirebaseMessaging,
    private val identityProvider: DeviceIdentityProvider,
) {

    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * Fetches the current FCM token and persists it. Safe to call on every app
     * start; idempotent for an unchanged token.
     */
    fun registerCurrentToken() {
        scope.launch {
            runCatching {
                val token = messaging.token.awaitTask()
                if (token.isNullOrBlank()) {
                    Log.w(TAG, "FCM token unavailable (null/blank)")
                    return@launch
                }
                persist(token)
            }.onFailure { Log.w(TAG, "registerCurrentToken failed: ${it.message}") }
        }
    }

    /** Persists a token delivered by `FirebaseMessagingService.onNewToken`. */
    fun onTokenRefreshed(token: String) {
        if (token.isBlank()) return
        scope.launch {
            runCatching { persist(token) }
                .onFailure { Log.w(TAG, "onTokenRefreshed failed: ${it.message}") }
        }
    }

    private suspend fun persist(token: String) {
        val deviceId = identityProvider.ensureSignedIn()
        if (deviceId.isEmpty()) {
            Log.w(TAG, "No deviceId (sign-in pending); skipping token write")
            return
        }
        val now = System.currentTimeMillis()
        val doc = firestore.collection(DiagnosticsConfig.DEVICES_COLLECTION).document(deviceId)

        // 1) Preserve health: update() only touches the token fields.
        val updateResult = runCatching {
            doc.update(DiagnosticsDocuments.fcmTokenUpdate(token, now)).awaitTask()
        }
        if (updateResult.isSuccess) {
            Log.d(TAG, "FCM token updated on existing device doc ($deviceId)")
            return
        }

        // 2) Document does not exist yet → create a minimal, rules-valid doc.
        runCatching {
            doc.set(
                DiagnosticsDocuments.minimalDeviceWithToken(deviceId, token, now),
                com.google.firebase.firestore.SetOptions.merge(),
            ).awaitTask()
            Log.d(TAG, "FCM token written via minimal device doc ($deviceId)")
        }.onFailure { Log.w(TAG, "FCM token write failed for $deviceId: ${it.message}") }
    }

    private suspend fun <T> Task<T>.awaitTask(): T? = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) cont.resume(task.result)
            else cont.resumeWithException(task.exception ?: IllegalStateException("Firebase task failed"))
        }
    }

    private companion object {
        const val TAG = "FcmTokenRegistrar"
    }
}
