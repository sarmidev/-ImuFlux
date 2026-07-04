package com.sarmidev.imuflux.data.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica la lógica de timing del resampleo de rejilla — el punto más frágil
 * del pipeline (la decimación por periodo mínimo partía 122 → 61 Hz; emitir el
 * timestamp real producía `dt` bimodales que rompían el jitter).
 */
class GridResamplerTest {

    private val periodNs = 10_000_000L // 100 Hz

    /** Genera [count] timestamps equiespaciados a [hz] arrancando en [startNs]. */
    private fun inputAt(hz: Int, count: Int, startNs: Long = 0L): List<Long> {
        val dt = 1_000_000_000L / hz
        return List(count) { startNs + it * dt }
    }

    private fun run(resampler: GridResampler, input: List<Long>): List<Long> {
        val out = ArrayList<Long>()
        for (ts in input) resampler.onEvent(ts)?.let { out.add(it) }
        return out
    }

    private fun deltas(ts: List<Long>): List<Long> =
        ts.zipWithNext { a, b -> b - a }

    @Test
    fun input200Hz_producesExactly10msGridWithSynthesizedTimestamps() {
        val r = GridResampler().apply { periodNs = this@GridResamplerTest.periodNs }
        val out = run(r, inputAt(200, count = 2000)) // 10 s

        // Entrada 200 Hz → salida 100 Hz, dt clavado al periodo, jitter 0.
        assertTrue("esperado ~100 Hz, fue ${out.size}", out.size in 995..1001)
        deltas(out).forEach { assertEquals(periodNs, it) }
    }

    @Test
    fun input121Hz_downsamplesToCleanGrid_noBimodalJitter() {
        val r = GridResampler().apply { periodNs = this@GridResamplerTest.periodNs }
        val out = run(r, inputAt(121, count = 1210)) // 10 s

        // El caso del A35: 121 Hz crudos. Con timestamps sintetizados todos los
        // dt son exactamente el periodo (antes alternaban 8,3 / 16,5 ms).
        assertTrue("esperado ~100 Hz, fue ${out.size}", out.size in 995..1001)
        deltas(out).forEach { assertEquals(periodNs, it) }
    }

    @Test
    fun inputBelowTarget61Hz_followsInputWithoutFabricatingRate() {
        val r = GridResampler().apply { periodNs = this@GridResamplerTest.periodNs }
        val input = inputAt(61, count = 610) // 10 s
        val out = run(r, input)

        // Por debajo del objetivo no se fabrica frecuencia: se emite un frame
        // por evento (no se rellena la rejilla con muestras sintéticas).
        assertEquals(input.size, out.size)
        // La duración de salida no supera la de entrada (no inflamos completitud).
        assertTrue(out.last() - out.first() <= input.last() - input.first())
    }

    @Test
    fun gap_reanchorsWithoutSyntheticFill() {
        val r = GridResampler().apply { periodNs = this@GridResamplerTest.periodNs }
        val before = inputAt(120, count = 120, startNs = 0L) // ~1 s
        val gapStart = before.last()
        val gapNs = 1_000_000_000L // 1 s sin datos
        val after = inputAt(120, count = 120, startNs = gapStart + gapNs)
        val out = run(r, before + after)

        // Ningún frame etiquetado dentro del hueco (no se rellena con muestras obsoletas).
        val inGap = out.filter { it > gapStart && it < gapStart + gapNs }
        assertTrue("no debe haber frames dentro del hueco, hubo ${inGap.size}", inGap.isEmpty())
        // El hueco sigue siendo visible como un dt grande en la salida.
        assertTrue(deltas(out).any { it >= gapNs - periodNs })
    }

    @Test
    fun periodZero_isPassthrough() {
        val r = GridResampler() // periodNs = 0 → sin resampleo
        val input = inputAt(200, count = 50)
        input.forEach { ts -> assertEquals(ts, r.onEvent(ts)) }
    }

    @Test
    fun reset_reanchorsOnNextEvent() {
        val r = GridResampler().apply { periodNs = this@GridResamplerTest.periodNs }
        assertEquals(1_000L, r.onEvent(1_000L)) // primer evento ancla y emite su ts real
        r.reset()
        // Tras reset, el siguiente evento vuelve a anclar a su propio timestamp.
        assertEquals(5_000_000_000L, r.onEvent(5_000_000_000L))
    }
}
