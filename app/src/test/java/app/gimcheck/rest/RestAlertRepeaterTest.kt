package app.gimcheck.rest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `restAlertRepeat` (§5): "repetir la alerta cada N segundos hasta que el usuario
 * interactúe (default 0 = una vez)".
 *
 * El planificador real usa un `Handler`, así que el contrato se verifica con uno de
 * prueba que guarda los avisos programados: lo que importa es **si** se reprograma, no
 * esperar N segundos en CI.
 */
class RestAlertRepeaterTest {

    private class FakeScheduler : RestAlertScheduler {
        data class Pending(val delayMillis: Long, val action: () -> Unit)

        val pending = mutableListOf<Pending>()
        var cancelled = 0

        override fun schedule(delayMillis: Long, action: () -> Unit): RestAlertScheduler.Registration {
            val entry = Pending(delayMillis, action)
            pending += entry
            return object : RestAlertScheduler.Registration {
                override fun cancel() {
                    cancelled += 1
                    pending.remove(entry)
                }
            }
        }

        /** "Suena" el primer aviso programado: se saca de la cola y se ejecuta. */
        fun runNext() {
            pending.removeAt(0).action()
        }
    }

    @Test
    fun `con restAlertRepeat en 0 no se reprograma nada`() {
        val scheduler = FakeScheduler()
        val repeater = RestAlertRepeater(scheduler)
        var alerts = 0

        repeater.start(0) { alerts += 1 }

        assertFalse(repeater.isRunning)
        assertTrue(scheduler.pending.isEmpty())
        assertEquals(0, alerts)
    }

    @Test
    fun `con N segundos repite cada N hasta que se corte`() {
        val scheduler = FakeScheduler()
        val repeater = RestAlertRepeater(scheduler)
        var alerts = 0

        repeater.start(30) { alerts += 1 }
        assertTrue(repeater.isRunning)
        assertEquals(1, scheduler.pending.size)
        assertEquals(30_000L, scheduler.pending.single().delayMillis)

        // Suena el aviso programado y se vuelve a programar otro.
        scheduler.runNext()
        assertEquals(1, alerts)
        assertEquals(1, scheduler.pending.size)

        repeater.stop()
        assertFalse(repeater.isRunning)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun `un aviso que ya estaba en la cola no se reprograma despues de cortar`() {
        val scheduler = FakeScheduler()
        val repeater = RestAlertRepeater(scheduler)
        var alerts = 0

        repeater.start(15) { alerts += 1 }
        val queued = scheduler.pending.single().action
        repeater.stop()
        // El runnable se ejecuta igual (estaba en la cola del Handler).
        queued()

        assertEquals("el usuario ya interactuó: no se repite", 0, alerts)
        assertTrue(scheduler.pending.isEmpty())
    }
}
