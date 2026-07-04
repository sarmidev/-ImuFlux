package com.sarmidev.imuflux.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sarmidev.imuflux.data.analysis.CompatibilityVerdictStore
import com.sarmidev.imuflux.data.analysis.QualityReport
import com.sarmidev.imuflux.data.analysis.SessionQualityAnalyzer
import com.sarmidev.imuflux.data.analysis.Verdict
import com.sarmidev.imuflux.data.storage.SessionFileManager
import com.sarmidev.imuflux.recording.RecordingEngine
import com.sarmidev.imuflux.recording.RecordingTuningStore
import com.sarmidev.imuflux.service.RecordingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Orquesta el "Test de compatibilidad" de 30 min con pantalla apagada.
 *
 * Por qué 30 min y no 10:
 *  - Android "Doze" light no se activa plenamente hasta ~15–20 min de
 *    pantalla apagada y dispositivo quieto.
 *  - Los killers agresivos de OxygenOS/ColorOS/MIUI aplican una ventana de
 *    gracia antes de empezar a matar foreground services ajenos.
 *  - 30 min cruza ambos umbrales con margen sin castigar en exceso al
 *    usuario. Con sesiones más cortas un móvil Tier C (ej. OnePlus Nord
 *    2T 5G) puede dar falso PASS porque ni Doze ni el killer del OEM han
 *    entrado aún en juego.
 *
 * Diseño:
 *  - Reutiliza el mismo pipeline real ([RecordingService] → [RecordingEngine]).
 *    Así el test mide exactamente lo que mide una grabación de producción:
 *    incluye el foreground service, el wake-lock, el watchdog, batching HW,
 *    rotación de CSV, todo.
 *  - Marca la sesión con los sentinel `forkliftModel = "__diagnostic__"` y
 *    `warehouse = "__diagnostic__"` para distinguirla de sesiones reales.
 *  - Al terminar, analiza el último CSV escrito con [SessionQualityAnalyzer],
 *    persiste el veredicto en [CompatibilityVerdictStore] y **borra la
 *    sesión** del disco — un test no debe acumular datos en la lista de
 *    sesiones reales.
 *  - Si el usuario cancela antes de que termine, se recoge lo que haya y se
 *    marca como INSUFFICIENT_DATA si no hay datos suficientes.
 *
 * No observa `isRecording` directamente para arrancar el countdown: lo lanza
 * en el mismo `startTest()` con un offset fijo. Así evitamos condiciones de
 * carrera si el sistema tarda en arrancar el service.
 */
@HiltViewModel
class CompatibilityTestViewModel @Inject constructor(
    application: Application,
    private val recordingEngine: RecordingEngine,
    private val sessionFileManager: SessionFileManager,
    private val analyzer: SessionQualityAnalyzer,
    private val verdictStore: CompatibilityVerdictStore,
    private val tuning: RecordingTuningStore,
) : AndroidViewModel(application) {

    enum class Phase { IDLE, RUNNING, ANALYZING, FINISHED, ERROR }

    /** Foto de la configuración de muestreo para el panel avanzado. */
    data class TuningState(
        val samplingHz: Int = 200,
        val gridEnabled: Boolean = true,
        val batchingEnabled: Boolean = true,
        val wakeupEnabled: Boolean = true,
    )

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val elapsedMs: Long = 0L,
        val totalDurationMs: Long = TEST_DURATION_MS,
        val report: QualityReport? = null,
        val errorMessage: String? = null,
        val deviceLabel: String = "",
        val tuning: TuningState = TuningState(),
    )

    private val _uiState = MutableStateFlow(
        UiState(deviceLabel = buildDeviceLabel(), tuning = readTuning()),
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private fun readTuning(): TuningState = TuningState(
        samplingHz = tuning.samplingHz(),
        gridEnabled = tuning.gridResampleEnabled(),
        batchingEnabled = tuning.batchingEnabled(),
        wakeupEnabled = tuning.preferWakeupSensors(),
    )

    /** Cambia la frecuencia de muestreo solicitada al HW (100/200/400 Hz). */
    fun setSamplingHz(hz: Int) {
        tuning.setSamplingHz(hz)
        _uiState.update { it.copy(tuning = readTuning()) }
    }

    /** Activa/desactiva el resampleo de rejilla a 100 Hz. */
    fun setGridEnabled(enabled: Boolean) {
        tuning.setGridResampleEnabled(enabled)
        _uiState.update { it.copy(tuning = readTuning()) }
    }

    /** Activa/desactiva el batching HW. */
    fun setBatchingEnabled(enabled: Boolean) {
        tuning.setBatchingEnabled(enabled)
        _uiState.update { it.copy(tuning = readTuning()) }
    }

    /** Prefiere sensores wake-up (o fuerza non-wakeup si se desactiva). */
    fun setWakeupEnabled(enabled: Boolean) {
        tuning.setPreferWakeupSensors(enabled)
        _uiState.update { it.copy(tuning = readTuning()) }
    }

    private var testJob: Job? = null
    private var testSessionId: String? = null

    /** Arranca el test. Llamada idempotente: no-op si ya hay un test en curso. */
    fun startTest() {
        if (_uiState.value.phase == Phase.RUNNING || _uiState.value.phase == Phase.ANALYZING) {
            Log.w(TAG, "startTest ignorado: ya hay un test en curso")
            return
        }
        if (recordingEngine.isRecording.value) {
            _uiState.update {
                it.copy(
                    phase = Phase.ERROR,
                    errorMessage = "Hay una grabación activa. Detenla antes de lanzar el test.",
                )
            }
            return
        }

        _uiState.update {
            UiState(
                phase = Phase.RUNNING,
                elapsedMs = 0L,
                totalDurationMs = TEST_DURATION_MS,
                report = null,
                errorMessage = null,
                deviceLabel = buildDeviceLabel(),
                tuning = readTuning(),
            )
        }

        val context = getApplication<Application>()
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_FORKLIFT, DIAGNOSTIC_SENTINEL)
            putExtra(RecordingService.EXTRA_WAREHOUSE, DIAGNOSTIC_SENTINEL)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure {
            Log.e(TAG, "No se pudo arrancar RecordingService para el test", it)
            _uiState.update { s ->
                s.copy(
                    phase = Phase.ERROR,
                    errorMessage = "No se pudo arrancar la grabación: ${it.message}",
                )
            }
            return
        }

        testJob = viewModelScope.launch {
            // Espera a que el engine reporte un sessionId — el service puede
            // tardar ~100 ms en arrancar la captura.
            val capturedId = waitForSessionId()
            testSessionId = capturedId

            val start = System.currentTimeMillis()
            while (isActive && System.currentTimeMillis() - start < TEST_DURATION_MS) {
                val elapsed = System.currentTimeMillis() - start
                _uiState.update { it.copy(elapsedMs = elapsed) }
                delay(TICK_INTERVAL_MS)
            }
            // Tiempo agotado — procedemos a parar y analizar.
            finishTest()
        }
    }

    /**
     * Cancela el test si está corriendo. Si ya ha generado ≥ 30 s de datos,
     * los analiza igualmente; si no, lo descarta como INSUFFICIENT_DATA.
     */
    fun cancelTest() {
        val job = testJob ?: return
        job.cancel()
        viewModelScope.launch { finishTest() }
    }

    /** Limpia el estado tras mostrar el veredicto; vuelve a IDLE. */
    fun acknowledgeResult() {
        _uiState.update { UiState(deviceLabel = buildDeviceLabel(), tuning = readTuning()) }
    }

    private suspend fun waitForSessionId(): String? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < SESSION_ID_WAIT_MS) {
            val id = recordingEngine.currentSessionId.value
            if (id != null) return id
            delay(100L)
        }
        return null
    }

    private suspend fun finishTest() {
        _uiState.update { it.copy(phase = Phase.ANALYZING) }

        val context = getApplication<Application>()
        val stopIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        runCatching { context.startService(stopIntent) }
            .onFailure { Log.w(TAG, "stop service falló en finishTest", it) }

        // Espera a que el engine confirme `isRecording=false` (máx 5 s).
        val stopStart = System.currentTimeMillis()
        while (recordingEngine.isRecording.value &&
            System.currentTimeMillis() - stopStart < STOP_WAIT_MS) {
            delay(100L)
        }

        val sid = testSessionId
        if (sid == null) {
            _uiState.update {
                it.copy(
                    phase = Phase.ERROR,
                    errorMessage = "No se pudo capturar el id de la sesión.",
                )
            }
            return
        }

        val report = withContext(Dispatchers.IO) {
            runCatching { analyzer.analyze(sid, manufacturer = Build.MANUFACTURER) }
                .onFailure { Log.e(TAG, "analyze($sid) falló", it) }
                .getOrNull()
        }

        if (report != null) {
            verdictStore.saveVerdict(report)
        }

        // Limpieza: borrar la sesión diagnóstica (incluye CSVs + metadata + lock).
        withContext(Dispatchers.IO) {
            runCatching { sessionFileManager.deleteSession(sid) }
                .onFailure { Log.w(TAG, "deleteSession($sid) falló", it) }
        }

        _uiState.update {
            it.copy(
                phase = if (report != null) Phase.FINISHED else Phase.ERROR,
                report = report,
                errorMessage = if (report == null) "El análisis no devolvió resultados." else null,
            )
        }
        testSessionId = null
        testJob = null
    }

    private fun buildDeviceLabel(): String {
        val mfr = (Build.MANUFACTURER ?: "").trim()
        val mdl = (Build.MODEL ?: "").trim()
        return when {
            mdl.isEmpty() -> mfr
            mfr.isEmpty() -> mdl
            mdl.startsWith(mfr, ignoreCase = true) -> mdl
            else -> "$mfr $mdl"
        }
    }

    companion object {
        private const val TAG = "CompatibilityTestVM"
        /**
         * 30 min = valor nominal del test.
         *
         * Mínimo necesario para cruzar el umbral de Doze light (~15–20 min)
         * y darle margen al killer del OEM (OxygenOS/ColorOS/MIUI suelen
         * disparar entre los 10 y los 25 min de screen-off). Con 10 min un
         * OnePlus Nord 2T 5G pasa el test sin que ni Doze ni el killer hayan
         * entrado aún: veredicto PASS falso.
         */
        const val TEST_DURATION_MS: Long = 30L * 60L * 1000L
        /** UI refresca cada 200 ms — suficiente para que el countdown no se vea a saltos. */
        private const val TICK_INTERVAL_MS: Long = 200L
        /** Margen de espera a que el service arranque y el engine publique sessionId. */
        private const val SESSION_ID_WAIT_MS: Long = 5_000L
        /** Margen de espera a que el engine confirme stop. */
        private const val STOP_WAIT_MS: Long = 5_000L
        /** Sentinel usado en `forkliftModel` y `warehouse` para identificar sesiones diagnósticas. */
        private const val DIAGNOSTIC_SENTINEL: String = "__diagnostic__"
    }
}

/** Traduce el [Verdict] a una etiqueta humana corta. Se usa en la UI y el banner. */
fun Verdict.label(): String = when (this) {
    Verdict.PASS -> "APTO"
    Verdict.WARN -> "MARGINAL"
    Verdict.FAIL -> "NO APTO"
    Verdict.INSUFFICIENT_DATA -> "SIN DATOS"
}
