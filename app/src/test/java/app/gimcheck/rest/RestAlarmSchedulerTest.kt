package app.gimcheck.rest

import android.app.AlarmManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Programación de la alarma de respaldo y de la **prueba de un toque** (GIM-44).
 *
 * El caso importante es el primero: sin `SCHEDULE_EXACT_ALARM` no se programa nada y el
 * servicio en primer plano es el único cronómetro. La prueba de Ajustes usa un
 * `PendingIntent` propio para no pisar un descanso en curso.
 */
@RunWith(RobolectricTestRunner::class)
class RestAlarmSchedulerTest {

    private lateinit var app: Application

    private val alarmManager: AlarmManager
        get() = app.getSystemService(AlarmManager::class.java)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun scheduled(): Int = shadowOf(alarmManager).scheduledAlarms.size

    @Test
    fun `programa la alarma exacta del descanso y la cancela`() {
        val scheduler = RestAlarmScheduler(app, canScheduleExact = true)

        assertTrue(scheduler.schedule(System.currentTimeMillis() + 30_000))
        assertEquals(1, scheduled())

        scheduler.cancel()
        assertEquals(0, scheduled())
    }

    @Test
    fun `sin permiso de alarmas exactas no programa nada`() {
        val scheduler = RestAlarmScheduler(app, canScheduleExact = false)

        assertFalse(scheduler.schedule(System.currentTimeMillis() + 30_000))
        assertEquals(0, scheduled())
    }

    @Test
    fun `un deadline en el pasado no se programa`() {
        val scheduler = RestAlarmScheduler(app, canScheduleExact = true)

        assertFalse(scheduler.schedule(System.currentTimeMillis() - 1_000))
        assertEquals(0, scheduled())
    }

    @Test
    fun `la prueba de un toque usa su propia alarma y el descanso no la pisa`() {
        val scheduler = RestAlarmScheduler(app, canScheduleExact = true)

        assertTrue(RestAlertCenter.scheduleSelfTest(app, seconds = 10))
        assertTrue(scheduler.schedule(System.currentTimeMillis() + 60_000))
        assertEquals("dos PendingIntent distintos, dos alarmas", 2, scheduled())

        scheduler.cancel()
        assertEquals("cancelar el descanso no cancela la prueba", 1, scheduled())
    }
}
