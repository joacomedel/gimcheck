package app.gimcheck.ui

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.gimcheck.rest.RestAlertCenter
import app.gimcheck.rest.SystemRestOverlay
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val APP_ID = "app.gimcheck"
private const val ACTIVITY = "$APP_ID/.MainActivity"

/**
 * Smoke test del flujo que reportó el dueño: entrar a Entrenar, tocar "Salir" y volver a
 * entrar. En el emulador de CI, un crash o una navegación vacía dejan el test en rojo.
 *
 * No verifica píxeles ni el cronómetro (eso es JVM + teléfono); verifica que la app
 * **siga viva** después de salir y que se pueda volver a abrir.
 *
 * **Cómo se lanza la app**: con `am start` desde la shell, no con `startActivity` desde
 * el proceso de instrumentación. Android 10+ restringe los "background activity start":
 * el segundo deja la Activity creada y dibujada pero el launcher conserva el foco, así
 * que UiAutomator busca en la ventana equivocada (pasó en el run 46052826436). La shell
 * sí puede traer la tarea al frente.
 */
@RunWith(AndroidJUnit4::class)
class EntrenarExitDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Un aviso de fin de descanso del test anterior (RestAlertDeviceTest) deja una
        // ventana APPLICATION_OVERLAY encima de todo: UiAutomator vería "Descanso
        // terminado" y no la pantalla de la app. Se retira antes de empezar.
        RestAlertCenter.dismiss(app)
        SystemRestOverlay.hideAny(app)
        shell("pm grant $APP_ID android.permission.POST_NOTIFICATIONS")
        shell("appops set $APP_ID SYSTEM_ALERT_WINDOW allow")
        shell("appops set $APP_ID SCHEDULE_EXACT_ALARM allow")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        // El emulador se queda sin foco de pantalla a mitad de un test largo; el cambio
        // de estado de display es justo lo que dispara el NPE del renderer.
        shell("svc power stayon true")
        SystemClock.sleep(500)
        // No usar `am force-stop`: el test corre en el proceso de la app y se mataría a
        // sí mismo. `am start` reutiliza la tarea y trae la Activity al frente.
    }

    @Test
    fun entrarSalirYVolverAAbrirLaApp() {
        launchApp()

        val entrenar = device.wait(Until.findObject(By.text("Entrenar")), 20_000)
            ?: device.wait(Until.findObject(By.textContains("Seguir entrenando")), 5_000)
            ?: abort("Hoy no ofreció Entrenar")
        entrenar.click()

        // Al entrar aparece el prompt de permisos del aviso (uno por permiso). Se
        // rechazan todos los que haya: la salida no depende de ellos.
        for (intento in 0 until 4) {
            val ahoraNo = device.wait(Until.findObject(By.text("Ahora no")), 1_500) ?: break
            ahoraNo.click()
            SystemClock.sleep(300)
        }

        val salir = device.wait(Until.findObject(By.text("Salir")), 15_000)
            ?: abort("Entrenar no ofreció Salir")
        salir.click()

        // La confirmación es **inline** y reusa la etiqueta "Salir": primero se espera
        // la pregunta y recién ahí se busca el botón, para no clickear el de atrás.
        val pregunta = "¿Salir? El entrenamiento sigue abierto."
        if (!device.wait(Until.hasObject(By.text(pregunta)), 5_000)) {
            abort("Salir tiene que pedir confirmación")
        }
        val confirmar = device.wait(Until.findObject(By.text("Salir")), 5_000)
            ?: abort("no apareció el botón de confirmar")
        confirmar.click()

        // Con el entrenamiento todavía abierto, Hoy calcula "Seguir entrenando (N min)".
        // Ahí estaba el crash real: Duration.between(LocalDateTime, Instant) lanzaba
        // DateTimeException y el proceso moría al volver de Entrenar.
        device.wait(Until.findObject(By.textContains("Seguir entrenando")), 10_000)
            ?: abort("después de Salir Hoy no mostró el entrenamiento abierto")

        // Si la navegación quedó vacía o el proceso murió, esto tampoco aparece.
        device.wait(Until.findObject(By.text("Hoy")), 10_000)
            ?: abort("después de Salir la app no volvió a Hoy")

        // Y tiene que poder volver a abrirse: el segundo síntoma del reporte. Como la
        // sesión sigue activa, la prueba de fuego es que Hoy vuelva a calcular los
        // minutos sin caerse.
        device.pressHome()
        SystemClock.sleep(700)
        launchApp()
        device.wait(Until.findObject(By.textContains("Seguir entrenando")), 15_000)
            ?: abort("la app no volvió a abrir con el entrenamiento abierto")
    }

    /**
     * Trae la app al frente con la shell y espera a que su ventana sea la activa.
     *
     * `-W` espera el lanzamiento; si la tarea ya existe, el sistema la trae al frente y
     * devuelve "Warning: Activity not started". Las dos salidas son válidas.
     */
    private fun launchApp() {
        val output = shell("am start -W -n $ACTIVITY")
        val started = output.contains("Status: ok") ||
            output.contains("has been brought to the front")
        if (!started) abort("am start no pudo abrir $ACTIVITY:\n$output")
        if (!device.wait(Until.hasObject(By.pkg(APP_ID)), 20_000)) {
            abort("la ventana de $APP_ID no quedó visible")
        }
    }

    private fun abort(what: String): Nothing =
        throw AssertionError("$what.\n${screenState()}")

    /** Jerarquía primero (lo más útil), más un resumen corto de ventanas. */
    private fun screenState(): String {
        val tree = try {
            val file = File(app.cacheDir, "window-dump.xml")
            device.dumpWindowHierarchy(file)
            file.readText().take(6_000)
        } catch (error: Throwable) {
            "no se pudo volcar la jerarquía: $error"
        }
        val overlays = shell("dumpsys window windows | grep -c 'APPLICATION_OVERLAY'").trim()
        return "paquete=${device.currentPackageName}\noverlays=$overlays\njerarquía:\n$tree"
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            stream.bufferedReader().readText()
        }
    }
}
