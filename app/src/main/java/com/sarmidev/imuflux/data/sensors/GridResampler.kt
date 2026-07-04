package com.sarmidev.imuflux.data.sensors

/**
 * Resampleo de una serie de timestamps a una **rejilla regular** de periodo
 * fijo, con timestamps de salida **sintetizados** (los propios puntos de la
 * rejilla), no los del evento de entrada.
 *
 * Clase pura, sin dependencias de Android, para poder testear la lógica de
 * timing de forma aislada — históricamente el punto más frágil del pipeline:
 *  - la decimación por periodo mínimo partía la tasa a la mitad (122 → 61 Hz),
 *  - emitir el timestamp real del evento producía `dt` bimodales (8/16 ms) y
 *    disparaba el FAIL por jitter/mediana pese a grabar 100 Hz de media.
 *
 * ## Comportamiento
 *
 * - Con [periodNs] `<= 0` desactiva el resampleo: [onEvent] devuelve el
 *   timestamp de entrada tal cual (un frame por evento).
 * - Con [periodNs] `> 0` emite como mucho un frame por punto de rejilla,
 *   etiquetado con el **timestamp de la rejilla** (`nextGridNs`), no con el del
 *   evento. La rejilla avanza en pasos fijos de [periodNs] anclados a tiempo
 *   absoluto, así que ante una entrada limpia y ≥ objetivo (p.ej. 200 Hz) la
 *   salida tiene `dt` exactamente igual al periodo y jitter ~0.
 * - Se **re-ancla** al timestamp del evento en el arranque y cuando el evento
 *   llega más de un periodo por delante de la rejilla. Esto: (1) evita rellenar
 *   un hueco con muestras sintéticas obsoletas (el hueco sigue siendo visible),
 *   y (2) hace que una entrada por debajo del objetivo siga a la entrada en vez
 *   de fabricar frecuencia.
 *
 * No es thread-safe. En [com.sarmidev.imuflux.data.sensors.SensorHub] [onEvent]
 * se llama siempre desde el hilo dedicado de sensores; [periodNs] se fija desde
 * el hilo de configuración (por eso es `@Volatile`) y [reset] se invoca en
 * puntos seguros del ciclo de vida (deja `anchored=false` para que el siguiente
 * [onEvent] re-ancle en su propio hilo).
 */
class GridResampler {

    /** Periodo de la rejilla (ns). `<= 0` desactiva el resampleo. */
    @Volatile
    var periodNs: Long = 0L

    @Volatile
    private var anchored: Boolean = false

    private var nextGridNs: Long = 0L

    /** Descarta el anclaje; el próximo evento re-ancla la rejilla a su timestamp. */
    fun reset() {
        anchored = false
        nextGridNs = 0L
    }

    /**
     * Procesa un evento del maestro con timestamp [ts] (ns). Devuelve el
     * timestamp con el que emitir el frame, o `null` si el evento debe
     * descartarse por no cruzar aún un punto de rejilla.
     */
    fun onEvent(ts: Long): Long? {
        val period = periodNs
        if (period <= 0L) return ts
        if (!anchored || ts - nextGridNs > period) {
            nextGridNs = ts
            anchored = true
        }
        if (ts >= nextGridNs) {
            val gridTs = nextGridNs
            nextGridNs += period
            return gridTs
        }
        return null
    }
}
