package com.sarmidev.imuflux.data.diagnostics

import android.content.Context
import android.content.SharedPreferences
import com.sarmidev.imuflux.service.SessionConfigStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the device's current operational [DeviceAssignment] (forklift +
 * warehouse) from the existing [SessionConfigStore].
 *
 * Terminology (post-migration, English canonical):
 *  - `forkliftModel`   ← [SessionConfigStore.getForklift] (operator-entered model)
 *  - `forkliftId`      ← [SessionConfigStore.getForkliftId] (`{name}_{6hex}`)
 *  - `warehouseName`   ← [SessionConfigStore.getWarehouse]
 *  - `warehouseId`     ← stable slug derived from the warehouse name
 *
 * Also offers [consumeAssignmentChange] so the diagnostics layer can emit a
 * `device_assignment_changed` analytics event exactly once per real change.
 */
@Singleton
class DeviceAssignmentProvider @Inject constructor(
    @ApplicationContext context: Context,
    private val sessionConfigStore: SessionConfigStore,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun current(): DeviceAssignment {
        val warehouseName = sessionConfigStore.getWarehouse()
        return DeviceAssignment(
            forkliftId = sessionConfigStore.getForkliftId(),
            forkliftModel = sessionConfigStore.getForklift(),
            warehouseId = slug(warehouseName),
            warehouseName = warehouseName,
        )
    }

    /**
     * Returns the current assignment if it changed since the last call, else
     * `null`. Lets the caller fire `device_assignment_changed` only on real
     * changes. The "seen" marker is persisted so it survives process death.
     */
    fun consumeAssignmentChange(): DeviceAssignment? {
        val current = current()
        val fingerprint = listOf(
            current.forkliftId,
            current.forkliftModel,
            current.warehouseId,
            current.warehouseName,
        ).joinToString("\u0001")
        val previous = prefs.getString(KEY_LAST_FINGERPRINT, null)
        if (previous == fingerprint) return null
        prefs.edit().putString(KEY_LAST_FINGERPRINT, fingerprint).apply()
        // Only report a change once a prior value existed; the very first
        // assignment is the baseline, not a "change".
        return if (previous == null) null else current
    }

    private fun slug(raw: String): String {
        val cleaned = raw.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return cleaned.ifEmpty { "unassigned" }
    }

    private companion object {
        const val PREFS_NAME = "imuflux_diagnostics"
        const val KEY_LAST_FINGERPRINT = "assignment_fingerprint"
    }
}
