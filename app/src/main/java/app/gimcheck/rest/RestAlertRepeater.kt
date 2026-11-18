package app.gimcheck.rest

import android.os.Handler
import android.os.Looper

/**
 * Repetición del aviso de fin de descanso (`restAlertRepeat`, §8; §5: "repetir la
 * alerta cada N segundos hasta que el usuario interactúe", default 0 = una vez).
 *
 * Se repite **sólo la señal** (vibración y/o sonido) y sólo cuando el aviso llegó con
 * la app en segundo plano: en primer plano el aviso es inline y el usuario ya lo está
 * viendo. [dismiss] (abrir la app o la pantalla de descanso) corta la repetición.
 *
 * El planificador es una interfaz para que el test verifique el contrato sin esperar
 * N segundos: con `restAlertRepeat = 0` **no se programa nada**.
 */
class RestAlertRepeater(private val scheduler: RestAlertScheduler) {

    private var registration: RestAlertScheduler.Registration? = null
    private var action: (() -> Unit)? = null
    private var seconds: Int = 0
    private var active: Boolean = false

    /** `true` mientras haya una repetición programada. */
    val isRunning: Boolean get() = active

    fun start(seconds: Int, action: () -> Unit) {
        stop()
        if (seconds <= 0) return
        this.seconds = seconds
        this.action = action
        active = true
        scheduleNext()
    }

    fun stop() {
        active = false
        registration?.cancel()
        registration = null
        action = null
        seconds = 0
    }

    private fun scheduleNext() {
        if (!active) return
        val next = action ?: return
        registration = scheduler.schedule(seconds * 1_000L) {
            registration = null
            // Un `stop()` pudo llegar mientras el runnable estaba en la cola.
            if (active) {
                next()
                scheduleNext()
            }
        }
    }
}

/** Programación de un aviso diferido; los tests la sustituyen por una lista. */
interface RestAlertScheduler {

    fun schedule(delayMillis: Long, action: () -> Unit): Registration

    interface Registration {
        fun cancel()
    }
}

/** La real: `Handler` del hilo principal, el mismo en el que vive la UI. */
class MainThreadRestAlertScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : RestAlertScheduler {

    override fun schedule(delayMillis: Long, action: () -> Unit): RestAlertScheduler.Registration {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMillis)
        return object : RestAlertScheduler.Registration {
            override fun cancel() {
                handler.removeCallbacks(runnable)
            }
        }
    }
}
