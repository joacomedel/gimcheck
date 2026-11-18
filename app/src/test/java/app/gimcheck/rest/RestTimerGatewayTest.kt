package app.gimcheck.rest

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * El cronómetro de descanso fuera de la pantalla (GIM-22), en la JVM y sin dispositivo.
 *
 * Lo que se puede verificar acá es **el contrato**, que es donde se rompen las cosas:
 *
 * 1. el servicio arranca con el **deadline absoluto** de `:core` y la alarma exacta
 *    apunta a ese mismo instante (`AlarmManager` + su `PendingIntent`);
 * 2. saltar / terminar cancelan la alarma y paran el servicio (no quedan huérfanas);
 * 3. sin `canScheduleExactAlarms()` **no explota**: no se programa la alarma y el
 *    servicio sigue siendo el cronómetro.
 *
 * Lo que **no** se puede verificar acá y lo mira el dueño en su teléfono: que la
 * notificación siga visible con la app cerrada, que el sistema no corte el servicio y
 * que la alarma despierte el proceso muerto. CI no tiene emulador.
 */
@RunWith(RobolectricTestRunner::class)
@Suppress("DEPRECATION")
class RestTimerGatewayTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private val alarmManager: AlarmManager
        get() = app.getSystemService(AlarmManager::class.java)

    private fun scheduledAlarms(): List<ShadowAlarmManager.ScheduledAlarm> =
        shadowOf(alarmManager).scheduledAlarms

    @Test
    fun `el cronometro arranca con el deadline y la alarma apunta al mismo instante`() {
        val timer = FakeTimer()
        val gateway = AndroidRestGateway(
            app,
            RestAlarmScheduler(app, canScheduleExact = true),
            timer,
        )
        val endsAt = Instant.ofEpochMilli(System.currentTimeMillis() + 90_000)

        gateway.onStartRest(seconds = 90, endsAt = endsAt)

        assertEquals("el servicio recibe el deadline, no una cuenta regresiva", listOf(endsAt.toEpochMilli()), timer.started)
        assertEquals(90, timer.totalSeconds)

        val alarm = scheduledAlarms().single()
        assertEquals(endsAt.toEpochMilli(), alarm.triggerAtTime)
        val operation = alarm.operation
        assertNotNull("la alarma tiene PendingIntent", operation)
        assertEquals(
            "el PendingIntent despierta al receptor del descanso",
            RestAlarmReceiver.ACTION_REST_ALARM,
            shadowOf(operation).savedIntent.action,
        )
    }

    @Test
    fun `saltar y terminar cancelan la alarma y paran el servicio`() {
        val timer = FakeTimer()
        val gateway = AndroidRestGateway(
            app,
            RestAlarmScheduler(app, canScheduleExact = true),
            timer,
        )
        val endsAt = Instant.ofEpochMilli(System.currentTimeMillis() + 90_000)

        gateway.onStartRest(seconds = 90, endsAt = endsAt)
        assertEquals(1, scheduledAlarms().size)

        // "Saltar" (SkipRest) y "terminar" (FinishSession) emiten CancelRest.
        gateway.onCancelRest()
        assertTrue("la alarma queda cancelada", scheduledAlarms().isEmpty())
        assertEquals(1, timer.stops)

        // El descanso llega a cero con la app abierta: RestFinished también limpia todo.
        gateway.onStartRest(seconds = 60, endsAt = endsAt)
        assertEquals(1, scheduledAlarms().size)
        gateway.onRestFinished()
        assertTrue("la alarma queda cancelada", scheduledAlarms().isEmpty())
        assertEquals(2, timer.stops)
    }

    @Test
    fun `sin alarmas exactas no explota y el servicio sigue siendo el cronometro`() {
        val timer = FakeTimer()
        val gateway = AndroidRestGateway(
            app,
            RestAlarmScheduler(app, canScheduleExact = false),
            timer,
        )
        val endsAt = Instant.ofEpochMilli(System.currentTimeMillis() + 45_000)

        gateway.onStartRest(seconds = 45, endsAt = endsAt)

        assertEquals("el servicio arranca igual", listOf(endsAt.toEpochMilli()), timer.started)
        assertTrue("no se programa alarma exacta", scheduledAlarms().isEmpty())

        // Cancelar cuando nunca se programó alarma tampoco puede romper.
        gateway.onCancelRest()
        assertTrue(scheduledAlarms().isEmpty())
        assertEquals(1, timer.stops)
    }

    @Test
    fun `el intent del servicio lleva el deadline y el total`() {
        val endsAt = 1_700_000_000_000L

        val intent = RestTimerService.startIntent(app, endsAt, totalSeconds = 120)

        assertEquals(RestTimerService.ACTION_START, intent.action)
        assertEquals(RestTimerService::class.java.name, intent.component?.className)
        assertEquals(endsAt, intent.getLongExtra(RestTimerService.EXTRA_ENDS_AT, 0L))
        assertEquals(120, intent.getIntExtra(RestTimerService.EXTRA_TOTAL_SECONDS, 0))
    }

    @Test
    fun `el restante se calcula desde el deadline y nunca es negativo`() {
        assertEquals(90, RestTimerService.remainingSeconds(90_000L, 0L))
        assertEquals(1, RestTimerService.remainingSeconds(1L, 0L))
        // Redondeo hacia arriba: el mismo criterio que la pantalla.
        assertEquals(1, RestTimerService.remainingSeconds(1_001L, 1_000L))
        assertEquals(0, RestTimerService.remainingSeconds(1_000L, 1_000L))
        assertEquals(0, RestTimerService.remainingSeconds(1L, 5_000L))
    }

    @Test
    fun `el fin de descanso publica en el canal de fin`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        RestNotifications.showRestDone(app)

        val manager = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        assertEquals(RestNotifications.CHANNEL_REST_DONE, posted.first().channelId)
    }

    /** Registra qué deadline se mandó y cuántas veces se paró, sin ciclo de vida real. */
    private class FakeTimer : RestTimerStarter {
        val started = mutableListOf<Long>()
        var totalSeconds: Int = 0
        var stops: Int = 0

        override fun start(context: Context, endsAtMillis: Long, totalSeconds: Int) {
            started += endsAtMillis
            this.totalSeconds = totalSeconds
        }

        override fun stop(context: Context) {
            stops++
        }
    }
}
