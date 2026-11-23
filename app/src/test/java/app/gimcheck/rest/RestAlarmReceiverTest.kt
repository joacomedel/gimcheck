package app.gimcheck.rest

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * El camino real del **respaldo por alarma** (GIM-44), en la JVM: el broadcast del
 * `AlarmManager` entra por [RestAlarmReceiver] → [RestAlertCenter] → la notificación del
 * canal `rest_done_v2`.
 *
 * Es el camino que corre cuando el proceso estaba muerto. Lo que la JVM no puede probar
 * (que la ventana del overlay se dibuje encima de otra app) lo cubre el test instrumentado
 * de `app/src/androidTest`.
 */
@RunWith(RobolectricTestRunner::class)
class RestAlarmReceiverTest {

    private lateinit var app: Application

    private val manager: NotificationManager
        get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        RestAlertCenter.resetForTest(app)
    }

    @Test
    fun `la alarma deja una notificacion de alarma con el proceso sin ui`() {
        // Robolectric no tiene activity: `AppVisibility` está en false, igual que con la
        // app cerrada cuando el AlarmManager despierta el proceso.
        RestAlarmReceiver().onReceive(app, RestAlarmReceiver.intent(app))

        val notification = awaitNotification()

        assertEquals(RestNotifications.CHANNEL_REST_DONE, notification.channelId)
        assertEquals(NotificationCompat.CATEGORY_ALARM, notification.category)
        assertEquals(NotificationCompat.PRIORITY_MAX, notification.priority)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertEquals(
            "la alarma debe quedar registrada en el diagnóstico",
            false,
            RestAlertDiagnostics.lastFire(app)?.inForeground,
        )
        assertNotNull(
            "el diagnóstico tiene que decir qué capa se usó",
            RestAlertDiagnostics.lastFire(app)?.delivery,
        )
    }

    private fun awaitNotification(): Notification {
        repeat(80) {
            shadowOf(Looper.getMainLooper()).idle()
            val all = shadowOf(manager).allNotifications
            if (all.isNotEmpty()) return all.single()
            Thread.sleep(25)
        }
        fail("la notificación nunca se publicó: el camino alarma → aviso está roto")
        error("unreachable")
    }
}
