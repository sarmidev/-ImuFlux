package com.example.scantest.data.repository

import com.example.scantest.data.analysis.DeviceKeyUtil
import com.example.scantest.data.analysis.DeviceModelStats
import com.example.scantest.data.analysis.InspectionResult
import com.example.scantest.data.analysis.Verdict
import com.example.scantest.domain.model.SessionMetadata
import com.example.scantest.domain.repository.AnalysisRemoteRepository
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class AnalysisRemoteRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) : AnalysisRemoteRepository {

    override suspend fun uploadAnalysis(
        metadata: SessionMetadata,
        result: InspectionResult,
    ) {
        ensureSignedIn()

        val deviceKey = DeviceKeyUtil.normalizeDeviceKey(
            manufacturer = metadata.deviceManufacturer,
            model = metadata.deviceModel,
            sdkInt = metadata.sdkInt,
        )
        val verdict = DeviceKeyUtil.verdictFor(result)
        val sessionRef = firestore.collection(SESSIONS_COLLECTION).document(metadata.sessionId)
        val deviceRef = firestore.collection(DEVICE_MODELS_COLLECTION).document(deviceKey)
        val sessionDocument = buildSessionDocument(metadata, result, deviceKey, verdict)

        firestore.runTransaction { transaction ->
            val existingSession = transaction.get(sessionRef)
            val deviceSnapshot = if (existingSession.exists()) null else transaction.get(deviceRef)

            transaction.set(sessionRef, sessionDocument, SetOptions.merge())

            // A repeated analysis for the same session updates the detail doc but
            // does not double-count the aggregate for the device model.
            if (existingSession.exists()) return@runTransaction null

            val oldStats = deviceSnapshot?.toStats() ?: emptyStats()
            val newStats = oldStats.with(result, verdict)
            val score = DeviceKeyUtil.computeCompatibilityScore(newStats)
            val category = DeviceKeyUtil.computeCategory(score, newStats)

            transaction.set(
                deviceRef,
                buildDeviceModelDocument(
                    metadata = metadata,
                    deviceKey = deviceKey,
                    stats = newStats,
                    score = score,
                    category = category,
                    verdict = verdict,
                    exists = deviceSnapshot?.exists() == true,
                ),
                SetOptions.merge(),
            )
            null
        }.awaitTask()
    }

    private suspend fun ensureSignedIn() {
        if (auth.currentUser == null) {
            auth.signInAnonymously().awaitTask()
        }
    }

    private fun buildSessionDocument(
        metadata: SessionMetadata,
        result: InspectionResult,
        deviceKey: String,
        verdict: Verdict,
    ): Map<String, Any?> = mapOf(
        "sessionId" to metadata.sessionId,
        "deviceKey" to deviceKey,
        "device" to mapOf(
            "manufacturer" to metadata.deviceManufacturer,
            "model" to metadata.deviceModel,
            "sdkInt" to metadata.sdkInt,
        ),
        "context" to mapOf(
            "forkliftModel" to metadata.forkliftModel,
            "warehouse" to metadata.warehouse,
            "appVersion" to metadata.appVersion,
        ),
        "timestamps" to mapOf(
            "startedAtWallMs" to metadata.startedAtWallMs,
            "endedAtWallMs" to metadata.endedAtWallMs,
            "analyzedAt" to FieldValue.serverTimestamp(),
        ),
        "analysis" to mapOf(
            "schemaVersion" to ANALYSIS_SCHEMA_VERSION,
            "totalRows" to result.totalRows,
            "durationS" to result.durationS,
            "dtMedianMs" to result.dtMedianMs,
            "dtMeanMs" to result.dtMeanMs,
            "jitterP95Ms" to result.jitterP95Ms,
            "gaps" to result.gaps,
            "maxGapMs" to result.maxGapMs,
            "completenessPercent" to result.completenessPercent,
            "watchdogResurrections" to result.watchdogResurrections,
            "timingPassed" to result.timingPassed,
            "timingErrors" to result.timingErrors,
            "dataProblems" to result.dataProblems,
            "verdict" to verdict.name,
            "rawVerdict" to verdict.name,
        ),
        "sensorGroups" to result.sensorGroups.map { group ->
            mapOf(
                "name" to group.name,
                "status" to group.status.name,
                "detail" to group.detail,
            )
        },
    )

    private fun buildDeviceModelDocument(
        metadata: SessionMetadata,
        deviceKey: String,
        stats: DeviceModelStats,
        score: Int,
        category: String,
        verdict: Verdict,
        exists: Boolean,
    ): Map<String, Any?> {
        val document = mutableMapOf<String, Any?>(
            "manufacturer" to metadata.deviceManufacturer,
            "model" to metadata.deviceModel,
            "sdkInt" to metadata.sdkInt,
            "deviceKey" to deviceKey,
            "lastSeenAt" to FieldValue.serverTimestamp(),
            "stats" to mapOf(
                "sessionCount" to stats.sessionCount,
                "passCount" to stats.passCount,
                "warnCount" to stats.warnCount,
                "failCount" to stats.failCount,
                "insufficientDataCount" to stats.insufficientDataCount,
                "avgCompleteness" to stats.avgCompleteness,
                "avgJitterP95Ms" to stats.avgJitterP95Ms,
                "avgMedianDtMs" to stats.avgMedianDtMs,
                "avgDurationS" to stats.avgDurationS,
                "totalGaps" to stats.totalGaps,
                "totalWatchdogResurrections" to stats.totalWatchdogResurrections,
            ),
            "compatibility" to mapOf(
                "score" to score,
                "category" to category,
                "lastVerdict" to verdict.name,
                "lastRawVerdict" to verdict.name,
            ),
        )
        if (!exists) {
            document["firstSeenAt"] = FieldValue.serverTimestamp()
        }
        return document
    }

    private fun DocumentSnapshot.toStats(): DeviceModelStats = DeviceModelStats(
        sessionCount = getLong("stats.sessionCount") ?: 0L,
        passCount = getLong("stats.passCount") ?: 0L,
        warnCount = getLong("stats.warnCount") ?: 0L,
        failCount = getLong("stats.failCount") ?: 0L,
        insufficientDataCount = getLong("stats.insufficientDataCount") ?: 0L,
        avgCompleteness = getDouble("stats.avgCompleteness") ?: 0.0,
        avgJitterP95Ms = getDouble("stats.avgJitterP95Ms") ?: 0.0,
        avgMedianDtMs = getDouble("stats.avgMedianDtMs") ?: 0.0,
        avgDurationS = getDouble("stats.avgDurationS") ?: 0.0,
        totalGaps = getLong("stats.totalGaps") ?: 0L,
        totalWatchdogResurrections = getLong("stats.totalWatchdogResurrections") ?: 0L,
    )

    private fun emptyStats(): DeviceModelStats = DeviceModelStats(
        sessionCount = 0L,
        passCount = 0L,
        warnCount = 0L,
        failCount = 0L,
        insufficientDataCount = 0L,
        avgCompleteness = 0.0,
        avgJitterP95Ms = 0.0,
        avgMedianDtMs = 0.0,
        avgDurationS = 0.0,
        totalGaps = 0L,
        totalWatchdogResurrections = 0L,
    )

    private fun DeviceModelStats.with(result: InspectionResult, verdict: Verdict): DeviceModelStats {
        val newCount = sessionCount + 1L
        return copy(
            sessionCount = newCount,
            passCount = passCount + if (verdict == Verdict.PASS) 1L else 0L,
            warnCount = warnCount + if (verdict == Verdict.WARN) 1L else 0L,
            failCount = failCount + if (verdict == Verdict.FAIL) 1L else 0L,
            insufficientDataCount = insufficientDataCount +
                if (verdict == Verdict.INSUFFICIENT_DATA) 1L else 0L,
            avgCompleteness = average(avgCompleteness, sessionCount, result.completenessPercent / 100.0),
            avgJitterP95Ms = average(avgJitterP95Ms, sessionCount, result.jitterP95Ms),
            avgMedianDtMs = average(avgMedianDtMs, sessionCount, result.dtMedianMs),
            avgDurationS = average(avgDurationS, sessionCount, result.durationS),
            totalGaps = totalGaps + result.gaps,
            totalWatchdogResurrections = totalWatchdogResurrections +
                (result.watchdogResurrections ?: 0),
        )
    }

    private fun average(oldAverage: Double, oldCount: Long, newValue: Double): Double =
        ((oldAverage * oldCount) + newValue) / (oldCount + 1L)

    private suspend fun <T> Task<T>.awaitTask(): T? = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            when {
                task.isSuccessful -> continuation.resume(task.result)
                else -> continuation.resumeWithException(
                    task.exception ?: IllegalStateException("Firebase task failed"),
                )
            }
        }
    }

    private companion object {
        const val SESSIONS_COLLECTION = "sessions"
        const val DEVICE_MODELS_COLLECTION = "deviceModels"
        const val ANALYSIS_SCHEMA_VERSION = 1
    }
}
