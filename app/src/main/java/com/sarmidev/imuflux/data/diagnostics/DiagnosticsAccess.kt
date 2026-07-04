package com.sarmidev.imuflux.data.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import com.sarmidev.imuflux.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gate that decides whether the in-app diagnostics dashboard is reachable.
 *
 * The dashboard is **hidden from normal users** by default. It becomes
 * available when any of the following is true:
 *  1. `BuildConfig.DEBUG` is `true` — compile-time constant, always true in
 *     debug builds regardless of how the APK is installed or signed.
 *  2. The `ApplicationInfo.FLAG_DEBUGGABLE` runtime flag is set — catches
 *     debuggable release variants and sideloaded debug APKs.
 *  3. An explicit admin flag is set in private SharedPreferences — allows
 *     field operators to enable the dashboard on production installs via
 *     [setAdminEnabled] or MDM pre-provisioning.
 *
 * Using both (1) and (2) makes the check robust: `BuildConfig.DEBUG` is
 * the reliable primary signal; `FLAG_DEBUGGABLE` is a secondary runtime
 * guard. Either alone can fail in edge cases (stripped flags, custom build
 * configs); together they cover all realistic debug-build scenarios.
 */
@Singleton
class DiagnosticsAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** `true` when the installed APK has the debuggable flag set at runtime. */
    private val isDebuggable: Boolean
        get() = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun isDashboardEnabled(): Boolean =
        BuildConfig.DEBUG || isDebuggable || prefs.getBoolean(KEY_ADMIN_ENABLED, false)

    fun setAdminEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ADMIN_ENABLED, enabled).apply()
    }

    private companion object {
        const val PREFS_NAME = "imuflux_diagnostics"
        const val KEY_ADMIN_ENABLED = "admin_enabled"
    }
}
