package app.gimcheck.ui

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test del flujo que reportó el dueño: entrar a Entrenar, tocar "Salir" y volver a
 * entrar. En el emulador de CI, un crash o una navegación vacía dejan el test en rojo.
 *
 * No verifica píxeles ni el cronómetro (eso es JVM + teléfono); verifica que la app
 * **siga viva** después de salir y que se pueda volver a abrir.
 */
@RunWith(AndroidJUnit4::class)
class EntrenarExitDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("appops set ${app.packageName} SCHEDULE_EXACT_ALARM allow")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        // Arranque limpio: sin una activity restaurada en Entrenar por un test anterior.
        shell("am force-stop ${app.packageName}")
        SystemClock.sleep(500)
    }

    @Test
    fun entrarSalirYVolverAAbrirLaApp() {
        launchApp()

        val entrenar = device.wait(Until.findObject(By.text("Entrenar")), 20_000)
            ?: device.wait(Until.findObject(By.textContains("Seguir entrenando")), 20_000)
        assertNotNull("Hoy tiene que ofrecer Entrenar", entrenar)
        entrenar.click()

        // El prompt de permisos del aviso puede aparecer al entrar; rechazarlo una vez
        // no cambia el flujo de salida.
        device.wait(Until.findObject(By.text("Ahora no")), 1_500)?.click()

        val salir = device.wait(Until.findObject(By.text("Salir")), 15_000)
        assertNotNull("Entrenar tiene que ofrecer Salir", salir)
        salir.click()

        val confirmar = device.wait(Until.findObject(By.text("Salir")), 5_000)
        assertNotNull("Salir tiene que pedir confirmación", confirmar)
        confirmar.click()

        // Si la navegación quedó vacía o el proceso murió, esto no aparece.
        val hoy = device.wait(Until.findObject(By.text("Hoy")), 10_000)
        assertNotNull("después de Salir la app tiene que estar en Hoy", hoy)

        // Y tiene que poder volver a abrirse: el segundo síntoma del reporte.
        device.pressHome()
        SystemClock.sleep(700)
        launchApp()
        val reabierta = device.wait(Until.findObject(By.text("Hoy")), 15_000)
        assertNotNull("la app tiene que volver a abrir después de Salir", reabierta)
    }

    private fun launchApp() {
        val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: error("sin launcher para ${app.packageName}")
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            stream.bufferedReader().readText()
        }
    }
}
