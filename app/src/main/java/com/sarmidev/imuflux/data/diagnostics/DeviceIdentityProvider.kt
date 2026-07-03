package com.sarmidev.imuflux.data.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Provides a stable [DeviceIdentity] whose [DeviceIdentity.deviceId] is the
 * Firebase Anonymous Auth UID.
 *
 * ### Why Firebase UID (not a random UUID)?
 * The Firestore security rules use `isOwner(deviceId)` which checks
 * `request.auth.uid == deviceId`. The document path and the auth token
 * therefore **must** use the same value — the Firebase anonymous UID.
 *
 * ### Stability
 * Firebase Auth persists the anonymous user locally (survives process death).
 * On top of that we cache the UID in private SharedPreferences so it is
 * readable synchronously after the first successful sign-in and as an offline
 * fallback on subsequent launches before Firebase Auth initialises.
 *
 * ### Thread safety
 * [cachedUid] is `@Volatile`. [ensureSignedIn] is a suspend function intended
 * to run on the diagnostics IO thread; it is safe to call concurrently from
 * multiple coroutines (Firebase Auth handles concurrent `signInAnonymously`
 * calls itself, and the worst outcome is an extra network round-trip).
 */
@Singleton
class DeviceIdentityProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * In-memory cache. Populated eagerly on construction (covers the common
     * case where Firebase Auth already has a persistent anonymous user) and
     * also from the SharedPreferences fallback.
     */
    @Volatile
    private var cachedUid: String? =
        auth.currentUser?.uid ?: prefs.getString(KEY_FIREBASE_UID, null)

    /**
     * The stable Firebase anonymous UID, or an empty string if sign-in has
     * never completed (first launch, no network).
     *
     * Checks `FirebaseAuth.currentUser` on every access so it automatically
     * reflects a sign-in that was completed by any other code path (e.g. the
     * Firestore repository's own safety guard), without needing a separate
     * [ensureSignedIn] call.
     */
    val deviceId: String
        get() {
            val cached = cachedUid
            if (cached != null) return cached
            // Firebase Auth may have completed sign-in via another path.
            val uid = auth.currentUser?.uid ?: return ""
            persist(uid)
            return uid
        }

    /**
     * Returns the stable Firebase UID, signing in anonymously if necessary.
     *
     * This is the canonical call-site for the diagnostics aggregator before it
     * builds any [DeviceIdentity] snapshot or hands a document path to Firestore.
     *
     * Never throws: if sign-in fails and no cached UID is available (first
     * launch without network) the method returns an empty string — subsequent
     * Firestore writes will fail their security-rule check, which is the
     * correct and safe outcome.
     */
    suspend fun ensureSignedIn(): String {
        // Fast path: UID already known.
        cachedUid?.let { return it }

        // Firebase Auth may already have a persistent anonymous session.
        auth.currentUser?.uid?.let { uid ->
            persist(uid)
            return uid
        }

        // Full sign-in: runs on whichever thread the caller lives on
        // (the diagnostics IO thread in normal usage).
        return runCatching {
            val result = auth.signInAnonymously().awaitTask()
            val uid = result?.user?.uid ?: return@runCatching ""
            persist(uid)
            uid
        }.getOrElse { e ->
            Log.w(TAG, "Anonymous sign-in failed: ${e.message}")
            // Offline fallback: use any UID persisted from a previous session.
            prefs.getString(KEY_FIREBASE_UID, "") ?: ""
        }
    }

    fun current(): DeviceIdentity = DeviceIdentity(
        deviceId = deviceId,
        appVersion = appVersion(),
        buildNumber = buildNumber(),
        androidVersion = Build.VERSION.SDK_INT,
        deviceModel = Build.MODEL ?: "unknown",
        manufacturer = Build.MANUFACTURER ?: "unknown",
    )

    // ── Internals ─────────────────────────────────────────────────────────

    private fun persist(uid: String) {
        cachedUid = uid
        prefs.edit().putString(KEY_FIREBASE_UID, uid).apply()
    }

    private fun appVersion(): String = runCatching {
        packageInfo().versionName ?: "unknown"
    }.getOrDefault("unknown")

    private fun buildNumber(): Long = runCatching {
        val info = packageInfo()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    private fun packageInfo() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }

    private suspend fun <T> Task<T>.awaitTask(): T? = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) cont.resume(task.result)
            else cont.resumeWithException(
                task.exception ?: IllegalStateException("Firebase sign-in task failed"),
            )
        }
    }

    private companion object {
        const val TAG = "DeviceIdentityProvider"
        const val PREFS_NAME = "imuflux_diagnostics"

        // New key — the old "device_id" key stored a random UUID and is
        // simply ignored going forward (no active cleanup needed).
        const val KEY_FIREBASE_UID = "firebase_uid"
    }
}
