package app.gimcheck.rest

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.gimcheck.data.settings.AlertMode
import app.gimcheck.data.settings.AppSettings
import app.gimcheck.data.settings.RestSound
import app.gimcheck.data.settings.RestVibration
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
import org.robolectric.shadows.ShadowNotificationManager

/**
 * La ejecución del aviso de fin de descanso (GIM-23), en la JVM.
 *
 * Dos capas, porque son dos contratos distintos:
 *
 * 1. **Qué pide cada `alertMode`**: [RestAlerter] con dobles de vibración, sonido y
 *    overlay. Acá se verifica el conjunto exacto de acciones y que `NONE` no dispare
 *    ninguna pero **sí** deje la notificación.
 * 2. **Qué notificación sale**: la implementación real con `ShadowNotificationManager`:
 *    el canal `rest_done_v2`, el *full-screen intent* sólo cuando corresponde y **nunca**
 *    con la app en primer plano.
 *
 * Lo que no se puede verificar acá (lo mira el dueño en su teléfono): que la pantalla se
 * encienda de verdad, que el teléfono vibre, que suene y que el overlay se dibuje. CI no
 * tiene emulador.
 */
@RunWith(RobolectricTestRunner::class)
class RestAlerterTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // Sin POST_NOTIFICATIONS el aviso se descarta a propósito (decisión del ADR):
        // para verificar la notificación hay que concederlo.
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    // --- dobles -----------------------------------------------------------------

    private class FakeNotifier : RestDoneNotifier {
        val plans = mutableListOf<RestAlertPlan>()

        override fun post(plan: RestAlertPlan) {
            plans += plan
        }
    }

    private class FakeVibrator : RestVibrator {
        val patterns = mutableListOf<RestVibration>()

        override fun vibrate(kind: RestVibration): Boolean {
            patterns += kind
            return true
        }
    }

    private class FakeSound : RestSoundPlayer {
        val played = mutableListOf<RestSound>()

        override fun play(sound: RestSound) {
            played += sound
        }

        override fun stop() = Unit
    }

    private class FakeOverlay : RestOverlayPresenter {
        var shown = 0

        override fun show(message: String, onClick: () -> Unit) {
            shown += 1
        }

        override fun hide() = Unit
    }

    private class Fixture(
        val notifier: FakeNotifier = FakeNotifier(),
        val vibrator: FakeVibrator = FakeVibrator(),
        val sound: FakeSound = FakeSound(),
        val overlay: FakeOverlay = FakeOverlay(),
    )

    private fun alerter(
        fixture: Fixture,
        fullScreen: Boolean = true,
        overlayAllowed: Boolean = false,
        interactive: Boolean = true,
        locked: Boolean = false,
        notifier: RestDoneNotifier = fixture.notifier,
    ): RestAlerter = RestAlerter(
        context = app,
        notifier = notifier,
        vibrator = fixture.vibrator,
        sound = fixture.sound,
        overlay = fixture.overlay,
        fullScreenAllowed = { fullScreen },
        overlayAllowed = { overlayAllowed },
        deviceInteractive = { interactive },
        keyguardLocked = { locked },
    )

    private fun settings(
        mode: AlertMode,
        vibration: RestVibration = RestVibration.DOUBLE,
        sound: RestSound = RestSound.DEFAULT,
        repeat: Int = 0,
    ) = AppSettings.DEFAULT.copy(
        alertMode = mode,
        restVibration = vibration,
        restSound = sound,
        restAlertRepeat = repeat,
    )

    private fun notifications(): List<Notification> =
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    // --- 1. matriz de acciones ---------------------------------------------------

    @Test
    fun `SCREEN_VIBRATE pide pantalla completa y vibra, sin sonido`() {
        val fixture = Fixture()
        val plan = alerter(fixture, locked = true)
            .alert(settings(AlertMode.SCREEN_VIBRATE), inForeground = false)

        assertEquals(RestScreenDelivery.FULL_SCREEN_INTENT, plan.screen)
        assertEquals(1, fixture.notifier.plans.size)
        assertEquals(listOf(RestVibration.DOUBLE), fixture.vibrator.patterns)
        assertTrue(fixture.sound.played.isEmpty())
        assertEquals(0, fixture.overlay.shown)
    }

    @Test
    fun `desbloqueado con otra app se dibuja encima aunque el FSI este concedido`() {
        // El bug real del dueño (GIM-44): estaba en un juego con la pantalla encendida.
        val fixture = Fixture()
        val plan = alerter(fixture, fullScreen = true, overlayAllowed = true, locked = false)
            .alert(settings(AlertMode.SCREEN_VIBRATE), inForeground = false)

        assertEquals(RestScreenDelivery.OVERLAY, plan.screen)
        assertEquals(1, fixture.overlay.shown)
        assertEquals(1, fixture.notifier.plans.size)
    }

    @Test
    fun `desbloqueado sin overlay cae a heads-up aunque el FSI este concedido`() {
        val fixture = Fixture()
        val plan = alerter(fixture, fullScreen = true, overlayAllowed = false, locked = false)
            .alert(settings(AlertMode.SCREEN), inForeground = false)

        assertEquals(RestScreenDelivery.HEADS_UP, plan.screen)
        assertEquals(0, fixture.overlay.shown)
        assertEquals(1, fixture.notifier.plans.size)
    }

    @Test
    fun `VIBRATE y SOUND usan los ajustes de patron y tono`() {
        val fixture = Fixture()
        alerter(fixture).alert(
            settings(AlertMode.VIBRATE_SOUND, vibration = RestVibration.LONG, sound = RestSound.CHIME),
            inForeground = false,
        )
        assertEquals(listOf(RestVibration.LONG), fixture.vibrator.patterns)
        assertEquals(listOf(RestSound.CHIME), fixture.sound.played)
    }

    @Test
    fun `SOUND solo suena`() {
        val fixture = Fixture()
        val plan = alerter(fixture).alert(settings(AlertMode.SOUND), inForeground = false)

        assertEquals(listOf(RestSound.DEFAULT), fixture.sound.played)
        assertTrue(fixture.vibrator.patterns.isEmpty())
        assertEquals(RestScreenDelivery.NONE, plan.screen)
    }

    @Test
    fun `NONE no dispara nada pero deja la notificacion`() {
        val fixture = Fixture()
        val plan = alerter(fixture).alert(settings(AlertMode.NONE), inForeground = false)

        assertEquals(1, fixture.notifier.plans.size)
        assertFalse(fixture.notifier.plans.single().hasSignal)
        assertEquals(RestScreenDelivery.NONE, plan.screen)
        assertTrue(fixture.vibrator.patterns.isEmpty())
        assertTrue(fixture.sound.played.isEmpty())
        assertEquals(0, fixture.overlay.shown)
    }

    @Test
    fun `sin full-screen intent ni overlay cae a heads-up sin dejar de vibrar`() {
        val fixture = Fixture()
        val plan = alerter(fixture, fullScreen = false, overlayAllowed = false)
            .alert(settings(AlertMode.SCREEN_VIBRATE), inForeground = false)

        assertEquals(RestScreenDelivery.HEADS_UP, plan.screen)
        assertEquals(1, fixture.notifier.plans.size)
        assertEquals(1, fixture.vibrator.patterns.size)
        assertEquals(0, fixture.overlay.shown)
    }

    @Test
    fun `sin full-screen intent pero con overlay se dibuja encima`() {
        val fixture = Fixture()
        val plan = alerter(fixture, fullScreen = false, overlayAllowed = true)
            .alert(settings(AlertMode.SCREEN), inForeground = false)

        assertEquals(RestScreenDelivery.OVERLAY, plan.screen)
        assertEquals(1, fixture.overlay.shown)
    }

    @Test
    fun `con la app en primer plano no se pide pantalla`() {
        val fixture = Fixture()
        val plan = alerter(fixture, fullScreen = true, overlayAllowed = true)
            .alert(settings(AlertMode.SCREEN_VIBRATE), inForeground = true)

        assertEquals(RestScreenDelivery.NONE, plan.screen)
        assertEquals(0, fixture.overlay.shown)
        assertEquals(listOf(RestVibration.DOUBLE), fixture.vibrator.patterns)
    }

    @Test
    fun `la repeticion vuelve a vibrar y sonar sin republicar ni abrir pantalla`() {
        val fixture = Fixture()
        val target = alerter(fixture, overlayAllowed = true, locked = true)
        target.alert(settings(AlertMode.SCREEN_VIBRATE_SOUND), inForeground = false)
        target.repeat(settings(AlertMode.SCREEN_VIBRATE_SOUND))

        assertEquals("la notificación se publica una sola vez", 1, fixture.notifier.plans.size)
        assertEquals(2, fixture.vibrator.patterns.size)
        assertEquals(2, fixture.sound.played.size)
        assertEquals(0, fixture.overlay.shown)
    }

    // --- 2. notificación real ----------------------------------------------------

    @Test
    fun `la notificacion sale por el canal de fin de descanso`() {
        val fixture = Fixture()
        alerter(fixture, locked = true, notifier = SystemRestDoneNotifier(app) { true })
            .alert(settings(AlertMode.SCREEN_VIBRATE), inForeground = false)

        val notification = notifications().single()
        assertEquals(RestNotifications.CHANNEL_REST_DONE, notification.channelId)
        assertNotNull("con FSI concedido la notificación lo lleva", notification.fullScreenIntent)
    }

    @Test
    fun `el overlay publicado con el telefono desbloqueado no finge un FSI`() {
        val fixture = Fixture()
        alerter(fixture, overlayAllowed = true, locked = false, notifier = SystemRestDoneNotifier(app) { true })
            .alert(settings(AlertMode.SCREEN), inForeground = false)

        val notification = notifications().single()
        assertEquals(RestNotifications.CHANNEL_REST_DONE, notification.channelId)
        assertNull(
            "el camino es el overlay; un FSI acá se degradaría a heads-up",
            notification.fullScreenIntent,
        )
        assertEquals(1, fixture.overlay.shown)
    }

    @Test
    fun `con la app abierta la notificacion no lleva full-screen intent`() {
        val fixture = Fixture()
        alerter(fixture, notifier = SystemRestDoneNotifier(app) { true })
            .alert(settings(AlertMode.SCREEN_VIBRATE), inForeground = true)

        val notification = notifications().single()
        assertEquals(RestNotifications.CHANNEL_REST_DONE, notification.channelId)
        assertNull("en primer plano el aviso es inline", notification.fullScreenIntent)
    }

    @Test
    fun `si el sistema nego el full-screen intent la notificacion no lo lleva`() {
        val fixture = Fixture()
        alerter(fixture, notifier = SystemRestDoneNotifier(app) { false })
            .alert(settings(AlertMode.SCREEN), inForeground = false)

        assertNull(notifications().single().fullScreenIntent)
    }

    @Test
    fun `NONE deja la notificacion igual`() {
        val fixture = Fixture()
        alerter(fixture, notifier = SystemRestDoneNotifier(app) { true })
            .alert(settings(AlertMode.NONE), inForeground = false)

        assertEquals(1, notifications().size)
    }
}
