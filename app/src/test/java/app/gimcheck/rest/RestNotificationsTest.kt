package app.gimcheck.rest

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * El canal y el diagnóstico del fin de descanso (GIM-43), en la JVM.
 *
 * Lo que se verifica acá es lo que sí se puede medir sin emulador: que el canal vigente
 * se cree con importancia alta y que el viejo (`rest_done`, del APK de GIM-22, con
 * settings inmutables) se borre; que la notificación del aviso sea una alarma de
 * prioridad máxima con su *full-screen intent*; y que el diagnóstico lea el estado real
 * del canal en vez de repetir lo que la app pidió.
 *
 * **Lo que no se puede verificar acá** (lo mira el dueño en su teléfono): que la pantalla
 * se encienda sobre el bloqueo y que Motorola no frene el FSI. Para eso está el bloque
 * "Diagnóstico de alertas".
 */
@RunWith(RobolectricTestRunner::class)
class RestNotificationsTest {

    private lateinit var app: Application

    private val manager: NotificationManager
        get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** El canal que ya existe en el teléfono, tal como lo dejó el APK de GIM-22. */
    private fun createLegacyChannel(importance: Int) {
        manager.createNotificationChannel(
            NotificationChannel(
                RestNotifications.LEGACY_CHANNEL_REST_DONE,
                "Fin de descanso (viejo)",
                importance,
            ),
        )
    }

    // --- canal -----------------------------------------------------------------

    @Test
    fun `ensureChannels crea el canal nuevo con importancia alta`() {
        RestNotifications.ensureChannels(app)

        val done = manager.getNotificationChannel(RestNotifications.CHANNEL_REST_DONE)
        assertNotNull("el canal vigente se crea en el primer arranque", done)
        assertEquals("rest_done_v2", done!!.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, done.importance)
        assertEquals(Notification.VISIBILITY_PUBLIC, done.lockscreenVisibility)
    }

    @Test
    fun `ensureChannels borra el canal viejo e inmutable`() {
        createLegacyChannel(NotificationManager.IMPORTANCE_DEFAULT)

        RestNotifications.ensureChannels(app)

        assertNull(
            "el canal de GIM-22 no se puede reconfigurar: se borra",
            manager.getNotificationChannel(RestNotifications.LEGACY_CHANNEL_REST_DONE),
        )
        assertEquals(
            "el cronómetro sigue con su canal",
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(RestNotifications.CHANNEL_REST)?.importance,
        )
    }

    // --- notificación del aviso -------------------------------------------------

    @Test
    fun `el aviso de fin es una alarma de prioridad maxima con full-screen intent`() {
        RestNotifications.postRestDone(app, fullScreen = true)

        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(RestNotifications.CHANNEL_REST_DONE, notification.channelId)
        assertEquals(NotificationCompat.CATEGORY_ALARM, notification.category)
        assertEquals(NotificationCompat.PRIORITY_MAX, notification.priority)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertNotNull("el FSI va sobre esta notificación", notification.fullScreenIntent)
    }

    @Test
    fun `sin full-screen intent concedido la notificacion sale igual`() {
        RestNotifications.postRestDone(app, fullScreen = false)

        val notification = shadowOf(manager).allNotifications.single()
        assertNull(notification.fullScreenIntent)
        assertEquals(NotificationCompat.CATEGORY_ALARM, notification.category)
    }

    // --- diagnóstico ------------------------------------------------------------

    @Test
    fun `el diagnostico lee la importancia efectiva del canal`() {
        RestNotifications.ensureChannels(app)

        val diagnosis = RestAlertDiagnostics.collect(app)

        assertEquals(RestNotifications.CHANNEL_REST_DONE, diagnosis.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, diagnosis.channelImportance)
    }

    @Test
    fun `el diagnostico no inventa un canal que no existe`() {
        val diagnosis = RestAlertDiagnostics.collect(app)

        assertNull(diagnosis.channelImportance)
    }

    @Test
    fun `el diagnostico refleja los permisos y la visibilidad del ultimo aviso`() {
        val diagnosis = RestAlertDiagnostics.collect(
            context = app,
            fullScreenIntent = false,
            exactAlarms = true,
            notifications = false,
            overlay = true,
            channelImportance = NotificationManager.IMPORTANCE_DEFAULT,
            fire = AlertFire(
                inForeground = false,
                deviceInteractive = true,
                keyguardLocked = false,
                delivery = RestScreenDelivery.OVERLAY,
            ),
        )

        assertFalse(diagnosis.fullScreenIntent)
        assertTrue(diagnosis.exactAlarms)
        assertFalse(diagnosis.notifications)
        assertTrue(diagnosis.overlay)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, diagnosis.channelImportance)
        assertEquals(false, diagnosis.inForegroundAtFire)
        assertEquals(RestScreenDelivery.OVERLAY, diagnosis.lastDelivery)
    }

    @Test
    fun `el ultimo aviso guarda el camino y el estado de la pantalla`() {
        RestAlertDiagnostics.clear(app)

        RestAlertDiagnostics.recordFire(
            context = app,
            inForeground = false,
            delivery = RestScreenDelivery.OVERLAY,
            deviceInteractive = true,
            keyguardLocked = false,
        )

        val fire = RestAlertDiagnostics.lastFire(app)
        assertEquals(false, fire?.inForeground)
        assertEquals(RestScreenDelivery.OVERLAY, fire?.delivery)
        assertEquals(true, fire?.deviceInteractive)
        assertEquals(false, fire?.keyguardLocked)
    }

    @Test
    fun `el diagnostico lee POST_NOTIFICATIONS del sistema`() {
        assertTrue(RestAlertDiagnostics.collect(app).notifications)

        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(
            "con el permiso revocado el diagnóstico tiene que decirlo",
            RestAlertDiagnostics.collect(app).notifications,
        )
    }

    @Test
    fun `el aviso recuerda si la app estaba en primer plano`() {
        assertNull(
            "sin avisos previos no hay dato, no un false inventado",
            RestAlertDiagnostics.lastFireInForeground(app),
        )

        RestAlertDiagnostics.recordFire(app, inForeground = false)
        assertEquals(false, RestAlertDiagnostics.lastFireInForeground(app))

        RestAlertDiagnostics.recordFire(app, inForeground = true)
        assertEquals(true, RestAlertDiagnostics.lastFireInForeground(app))
    }
}
