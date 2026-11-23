package app.gimcheck.rest

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verificación **en Android real** del aviso de fin de descanso (GIM-44).
 *
 * Corre en el emulador de CI (`:app:connectedDebugAndroidTest`, API 34). Es la prueba
 * que faltaba: la JVM puede verificar la política y la notificación, pero no que el
 * overlay se dibuje **encima de otra app** ni que el `AlarmManager` despierte el
 * receptor con el proceso sin UI.
 *
 * Lo que se comprueba:
 *
 * 1. el canal `rest_done_v2` queda con importancia alta efectiva;
 * 2. con `SYSTEM_ALERT_WINDOW` concedido, el aviso con el teléfono desbloqueado y
 *    Ajustes del sistema en primer plano agrega una ventana `TYPE_APPLICATION_OVERLAY`
 *    de la app (lo dice `dumpsys window`), y el diagnóstico registra `overlay`;
 * 3. la alarma programada dispara el receptor y deja la notificación del canal.
 *
 * El permiso de overlay y el de alarmas exactas se conceden con `appops` (la misma
 * operación que hace el usuario en Ajustes, sin depender de tocar la UI).
 */
@RunWith(AndroidJUnit4::class)
class RestAlertDeviceTest {

    private lateinit var app: Application

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("appops set ${app.packageName} SCHEDULE_EXACT_ALARM allow")
        shell("pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        // Pantalla encendida y desbloqueada: el escenario del dueño.
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        RestAlertCenter.resetForTest(app)
        RestNotifications.ensureChannels(app)
        shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(500)
    }

    @After
    fun tearDown() {
        RestAlertCenter.dismiss(app)
        SystemRestOverlay.hideAny(app)
        shell("input keyevent KEYCODE_HOME")
    }

    @Test
    fun canalDeFinDeDescansoConImportanciaAlta() {
        val channels = app.getSystemService(NotificationManager::class.java)
        val channel = channels.getNotificationChannel(RestNotifications.CHANNEL_REST_DONE)

        assertNotNull("el canal vigente tiene que existir después de ensureChannels", channel)
        assertEquals(
            "una importancia menor degrada el aviso a heads-up silencioso",
            NotificationManager.IMPORTANCE_HIGH,
            channel!!.importance,
        )
    }

    @Test
    fun overlayApareceEncimaDeOtraAppConElTelefonoDesbloqueado() {
        // Otra app en primer plano: el mismo escenario del dueño (estaba en un juego).
        shell("am start -a android.settings.SETTINGS")
        SystemClock.sleep(2_000)
        assertTrue(
            "el permiso de overlay tiene que leerse como concedido",
            RestPermissions.overlayGranted(app),
        )

        RestAlertCenter.restFinished(app, inForeground = false)

        awaitTrue("ventana TYPE_APPLICATION_OVERLAY de ${app.packageName}") {
            val dump = shell("dumpsys window windows")
            dump.contains("APPLICATION_OVERLAY") && dump.contains(app.packageName)
        }
        assertTrue("la ventana tiene que seguir puesta", SystemRestOverlay.isShowing())
        assertEquals(
            "el diagnóstico tiene que decir que se usó el overlay",
            RestScreenDelivery.OVERLAY,
            RestAlertDiagnostics.lastFire(app)?.delivery,
        )
    }

    @Test
    fun laAlarmaExactaDespiertaElReceptorYDejaLaNotificacion() {
        val endsAt = System.currentTimeMillis() + 3_000
        assertTrue(
            "la alarma exacta tiene que quedar programada",
            RestAlarmScheduler(app).scheduleSelfTest(endsAt),
        )

        val fire = awaitValue("el aviso de la alarma") { RestAlertDiagnostics.lastFire(app) }
        assertFalse("la app no tenía UI: el aviso es en segundo plano", fire.inForeground)
        assertEquals(
            "desbloqueado con overlay concedido: la capa que aparece es el overlay",
            RestScreenDelivery.OVERLAY,
            fire.delivery,
        )
        awaitTrue("notificación activa de ${app.packageName} en rest_done_v2") {
            val dump = shell("dumpsys notification --noredact")
            dump.contains(RestNotifications.CHANNEL_REST_DONE) &&
                dump.contains("pkg=${app.packageName}")
        }
    }

    // --- helpers ---------------------------------------------------------------

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            stream.bufferedReader().readText()
        }
    }

    private fun awaitTrue(
        what: String,
        timeoutMillis: Long = 15_000,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(250)
        }
        fail("timeout esperando $what")
    }

    private fun <T : Any> awaitValue(
        what: String,
        timeoutMillis: Long = 15_000,
        condition: () -> T?,
    ): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            condition()?.let { return it }
            SystemClock.sleep(250)
        }
        fail("timeout esperando $what")
        error("unreachable")
    }
}
