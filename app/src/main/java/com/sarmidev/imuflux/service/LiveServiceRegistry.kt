package com.sarmidev.imuflux.service

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Indicador proceso-local de si [RecordingService] está vivo ahora mismo.
 *
 * Lo actualiza el propio servicio en `onStartCommand` (true) y `onDestroy`
 * (false). El [WatchdogReceiver] lo consulta para decidir si debe relanzar
 * la grabación sin necesidad de un `bindService` costoso.
 *
 * Importante: el flag es **proceso-local** — si el proceso muere, el flag
 * se pierde y el watchdog interpretará que el servicio está muerto, que es
 * exactamente lo que queremos.
 */
object LiveServiceRegistry {
    private val alive = AtomicBoolean(false)

    fun markAlive() {
        alive.set(true)
    }

    fun markDead() {
        alive.set(false)
    }

    fun isAlive(): Boolean = alive.get()
}
