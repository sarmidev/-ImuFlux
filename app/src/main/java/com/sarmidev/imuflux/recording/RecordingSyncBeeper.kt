package com.sarmidev.imuflux.recording

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Reproduce un pitido agudo y corto al arrancar una grabación, para que una
 * cámara externa con micrófono (a un par de metros del dispositivo) pueda
 * identificar en el vídeo/audio el instante exacto de inicio.
 *
 * Genera directamente una onda senoidal por [AudioTrack] en lugar de usar
 * [android.media.ToneGenerator]: así se controla la frecuencia — aguda,
 * para destacar sobre ruido ambiente (voces, motor) de baja frecuencia — y
 * la amplitud se satura al máximo digital posible (±[Short.MAX_VALUE]), sin
 * depender de la escala relativa 0-100 de `ToneGenerator`, que en muchos
 * dispositivos resulta demasiado silenciosa incluso al 100%.
 *
 * Usa el constructor "legacy" de [AudioTrack] ligado directamente a
 * [AudioManager.STREAM_MUSIC] con `MODE_STREAM` — la combinación más probada
 * y compatible entre fabricantes — en lugar de `AudioAttributes` + `Builder` +
 * `MODE_STATIC`, que en algunos dispositivos (p. ej. ciertos Samsung con
 * políticas de audio propias) puede quedarse en silencio sin lanzar
 * excepción. Sigue respetando el volumen de música del usuario, pero fuerza
 * el volumen del propio `AudioTrack` al máximo dentro de ese volumen.
 */
object RecordingSyncBeeper {

    private const val TAG = "RecordingSyncBeeper"

    private const val SAMPLE_RATE_HZ = 44_100

    /** Tono agudo: corta mejor sobre ruido ambiente y lo captan bien los micros de cámara. */
    private const val TONE_FREQUENCY_HZ = 3_000.0
    private const val TONE_DURATION_MS = 250

    /** Fade in/out corto para evitar "clicks" al empezar/terminar la onda. */
    private const val FADE_MS = 8

    /** Margen tras la duración nominal antes de liberar el AudioTrack. */
    private const val RELEASE_MARGIN_MS = 80L

    /** Hilo dedicado: genera el buffer y reproduce sin bloquear quien llame a [play]. */
    private val playbackExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RecordingSyncBeeper").apply { priority = Thread.MAX_PRIORITY }
    }

    fun play() {
        playbackExecutor.execute {
            runCatching { playBlocking() }
                .onFailure { Log.e(TAG, "No se pudo reproducir el pitido de inicio de grabación", it) }
        }
    }

    @Suppress("DEPRECATION") // Constructor legado: más compatible que Builder+AudioAttributes para este caso.
    private fun playBlocking() {
        val samples = buildToneSamples()
        val minBufferBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(0)
        val bufferBytes = maxOf(minBufferBytes, samples.size * 2)

        val audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
            AudioTrack.MODE_STREAM,
        )

        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack no se inicializó (state=${audioTrack.state}) — se omite el pitido")
            audioTrack.release()
            return
        }

        audioTrack.setVolume(1.0f)
        val written = audioTrack.write(samples, 0, samples.size)
        if (written < 0) {
            Log.w(TAG, "AudioTrack.write() devolvió error ($written) — se omite el pitido")
            audioTrack.release()
            return
        }
        audioTrack.play()
        Thread.sleep(TONE_DURATION_MS + RELEASE_MARGIN_MS)
        runCatching { audioTrack.stop() }
        audioTrack.release()
    }

    /** Onda senoidal a máxima amplitud digital, con fade in/out para evitar clicks. */
    private fun buildToneSamples(): ShortArray {
        val totalSamples = SAMPLE_RATE_HZ * TONE_DURATION_MS / 1000
        val fadeSamples = (SAMPLE_RATE_HZ * FADE_MS / 1000).coerceAtLeast(1)
        return ShortArray(totalSamples) { i ->
            val angle = 2.0 * PI * TONE_FREQUENCY_HZ * i / SAMPLE_RATE_HZ
            val envelope = when {
                i < fadeSamples -> i / fadeSamples.toDouble()
                i > totalSamples - fadeSamples -> (totalSamples - i) / fadeSamples.toDouble()
                else -> 1.0
            }
            (envelope * Short.MAX_VALUE * sin(angle)).toInt().toShort()
        }
    }
}
