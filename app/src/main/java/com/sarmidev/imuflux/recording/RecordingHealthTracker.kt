package com.sarmidev.imuflux.recording

import com.sarmidev.imuflux.domain.model.RecordingHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Calcula y publica métricas de salud de una sesión de grabación.
 *
 * Uso esperado: una única instancia por sesión. [onFrame] se invoca desde
 * el hilo consumidor (`Dispatchers.IO.limitedParallelism(1)`), por tanto
 * no necesita sincronización interna.
 *
 * Ventana móvil de 10 s para `samplesPerSecond` y `jitterP95Ns`.
 */
class RecordingHealthTracker(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
) {

    private val _state = MutableStateFlow(RecordingHealth())
    val state: StateFlow<RecordingHealth> = _state.asStateFlow()

    private val deltaRing = LongArray(windowSize)
    private var deltaCount: Int = 0
    private var deltaHead: Int = 0

    private var firstTimestampNs: Long = 0L
    private var lastTimestampNs: Long = 0L
    private var framesWritten: Long = 0L
    private var framesDropped: Long = 0L
    private var lastHubEmitted: Long = 0L
    private var lastPublishBootMs: Long = 0L

    private var rawSamplesByHub: Long = 0L
    private var lastRawCountAtPublish: Long = 0L
    private var lastRawWallMs: Long = 0L
    private var rawSamplesPerSecond: Float = 0f

    private val sortBuffer = LongArray(windowSize)

    /**
     * Debe llamarse al escribir cada frame. Recalcula métricas; publica a
     * [state] como máximo cada [PUBLISH_INTERVAL_MS] para no saturar el flujo.
     */
    fun onFrame(
        timestampNs: Long,
        framesEmittedByHub: Long,
        rawEmittedByHub: Long,
        bytesWritten: Long,
        chunkIndex: Int,
        framesQueued: Int,
    ) {
        rawSamplesByHub = rawEmittedByHub
        if (lastTimestampNs != 0L) {
            val delta = timestampNs - lastTimestampNs
            deltaRing[deltaHead] = delta
            deltaHead = (deltaHead + 1) % windowSize
            if (deltaCount < windowSize) deltaCount += 1
        } else {
            firstTimestampNs = timestampNs
        }
        lastTimestampNs = timestampNs
        framesWritten += 1

        val approxDrops = (framesEmittedByHub - framesWritten - framesQueued).coerceAtLeast(0L)
        framesDropped = approxDrops
        lastHubEmitted = framesEmittedByHub

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastPublishBootMs >= PUBLISH_INTERVAL_MS) {
            updateRawSamplesPerSecond(nowMs)
            lastPublishBootMs = nowMs
            publish(bytesWritten, chunkIndex, framesQueued)
        }
    }

    /** Tasa cruda del sensor sobre el intervalo transcurrido desde la última publicación. */
    private fun updateRawSamplesPerSecond(nowMs: Long) {
        if (lastRawWallMs == 0L) {
            lastRawWallMs = nowMs
            lastRawCountAtPublish = rawSamplesByHub
            return
        }
        val elapsedMs = nowMs - lastRawWallMs
        if (elapsedMs <= 0L) return
        val deltaRaw = (rawSamplesByHub - lastRawCountAtPublish).coerceAtLeast(0L)
        rawSamplesPerSecond = (deltaRaw * 1000.0 / elapsedMs).toFloat()
        lastRawWallMs = nowMs
        lastRawCountAtPublish = rawSamplesByHub
    }

    /** Publica la última foto de métricas incluso si no ha pasado el intervalo. */
    fun flushPublish(bytesWritten: Long, chunkIndex: Int, framesQueued: Int) {
        publish(bytesWritten, chunkIndex, framesQueued)
    }

    fun reset() {
        deltaCount = 0
        deltaHead = 0
        firstTimestampNs = 0L
        lastTimestampNs = 0L
        framesWritten = 0L
        framesDropped = 0L
        lastHubEmitted = 0L
        lastPublishBootMs = 0L
        rawSamplesByHub = 0L
        lastRawCountAtPublish = 0L
        lastRawWallMs = 0L
        rawSamplesPerSecond = 0f
        logCounter = 0
        _state.value = RecordingHealth()
    }

    private var logCounter: Int = 0

    private fun publish(bytesWritten: Long, chunkIndex: Int, framesQueued: Int) {
        val samplesPerSecond = computeSamplesPerSecond()
        val jitterP95 = computeJitterP95Ns()
        val health = RecordingHealth(
            samplesPerSecond = samplesPerSecond,
            rawSamplesPerSecond = rawSamplesPerSecond,
            jitterP95Ns = jitterP95,
            framesQueued = framesQueued,
            framesDropped = framesDropped,
            bytesWritten = bytesWritten,
            currentChunkIndex = chunkIndex,
            framesWritten = framesWritten,
        )
        _state.value = health
        logCounter++
        if (logCounter % LOG_EVERY_N_PUBLISHES == 0) {
            android.util.Log.d("RecHealthTracker",
                "publish #$logCounter — Hz=$samplesPerSecond raw=$rawSamplesPerSecond " +
                "jitNs=$jitterP95 written=$framesWritten bytes=$bytesWritten chunk=$chunkIndex")
        }
    }

    private fun computeSamplesPerSecond(): Float {
        if (deltaCount == 0) return 0f
        var sum = 0L
        for (i in 0 until deltaCount) sum += deltaRing[i]
        if (sum <= 0L) return 0f
        val meanPeriodSeconds = (sum.toDouble() / deltaCount) / 1_000_000_000.0
        return (1.0 / meanPeriodSeconds).toFloat()
    }

    private fun computeJitterP95Ns(): Long {
        if (deltaCount < 2) return 0L
        // Copia en sortBuffer como |delta - 10ms|.
        val nominal = 10_000_000L
        for (i in 0 until deltaCount) {
            sortBuffer[i] = abs(deltaRing[i] - nominal)
        }
        // Sort parcial hasta deltaCount.
        java.util.Arrays.sort(sortBuffer, 0, deltaCount)
        val idx = ((deltaCount - 1) * 0.95).toInt()
        return sortBuffer[idx]
    }

    companion object {
        private const val DEFAULT_WINDOW_SIZE: Int = 1000 // ≈ 10 s a 100 Hz
        private const val PUBLISH_INTERVAL_MS: Long = 500L
        private const val LOG_EVERY_N_PUBLISHES: Int = 20 // ~every 10 seconds
    }
}
