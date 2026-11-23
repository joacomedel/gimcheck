package app.gimcheck.rest

import app.gimcheck.data.settings.AlertMode
import app.gimcheck.data.settings.RestVibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La matriz de `alertMode` (GIM-23, `docs/especificacion.md` §5) **sin Android**: la
 * decisión es un valor puro y se verifica entera en la JVM.
 *
 * Lo que cubre:
 *
 * 1. las 8 combinaciones producen el conjunto exacto de acciones (pantalla / vibración /
 *    sonido);
 * 2. con la app en primer plano **nunca** se pide pantalla (el aviso es inline);
 * 3. la regla del bug real (GIM-44): con el teléfono **desbloqueado y en uso** el
 *    full-screen intent se degrada a heads-up, así que se usa overlay si está
 *    concedido, aunque el FSI también lo esté;
 * 4. con la pantalla apagada o el teléfono bloqueado el FSI sí se usa;
 * 5. sin FSI ni overlay queda el heads-up: nunca silencio;
 * 6. los tres patrones de `restVibration` son distintos.
 */
class RestAlertPlanTest {

    private fun state(
        foreground: Boolean = false,
        interactive: Boolean = true,
        locked: Boolean = false,
        fullScreen: Boolean = true,
        overlay: Boolean = false,
    ) = RestScreenState(
        appInForeground = foreground,
        deviceInteractive = interactive,
        keyguardLocked = locked,
        fullScreenAllowed = fullScreen,
        overlayAllowed = overlay,
    )

    private fun plan(
        mode: AlertMode,
        foreground: Boolean = false,
        interactive: Boolean = true,
        locked: Boolean = false,
        fullScreen: Boolean = true,
        overlay: Boolean = false,
    ) = RestAlertPlans.forMode(
        mode = mode,
        state = state(foreground, interactive, locked, fullScreen, overlay),
    )

    @Test
    fun `las 8 combinaciones producen exactamente las acciones de su nombre`() {
        val expected = mapOf(
            AlertMode.SCREEN to Triple(true, false, false),
            AlertMode.VIBRATE to Triple(false, true, false),
            AlertMode.SOUND to Triple(false, false, true),
            AlertMode.SCREEN_VIBRATE to Triple(true, true, false),
            AlertMode.SCREEN_SOUND to Triple(true, false, true),
            AlertMode.VIBRATE_SOUND to Triple(false, true, true),
            AlertMode.SCREEN_VIBRATE_SOUND to Triple(true, true, true),
            AlertMode.NONE to Triple(false, false, false),
        )
        AlertMode.entries.forEach { mode ->
            val (screen, vibrate, sound) = expected.getValue(mode)
            // Bloqueado para que lo único que se evalúe sea el conjunto de acciones.
            val resolved = plan(mode, locked = true)
            assertEquals("pantalla de $mode", screen, resolved.screen != RestScreenDelivery.NONE)
            assertEquals("vibración de $mode", vibrate, resolved.vibrate)
            assertEquals("sonido de $mode", sound, resolved.sound)
        }
    }

    @Test
    fun `NONE no dispara nada y aun asi no rompe la notificacion`() {
        val resolved = plan(AlertMode.NONE, locked = true)
        assertEquals(RestScreenDelivery.NONE, resolved.screen)
        assertFalse(resolved.vibrate)
        assertFalse(resolved.sound)
        assertFalse(resolved.hasSignal)
    }

    @Test
    fun `con la app en primer plano no se roba la pantalla`() {
        listOf(
            AlertMode.SCREEN,
            AlertMode.SCREEN_VIBRATE,
            AlertMode.SCREEN_SOUND,
            AlertMode.SCREEN_VIBRATE_SOUND,
        ).forEach { mode ->
            val resolved = plan(mode, foreground = true, locked = true, overlay = true)
            assertEquals("pantalla con la app abierta en $mode", RestScreenDelivery.NONE, resolved.screen)
        }
        // La señal que sí queda es la vibración/sonido del modo.
        val withVibration = plan(AlertMode.SCREEN_VIBRATE, foreground = true)
        assertTrue(withVibration.vibrate)
        assertFalse(withVibration.sound)
    }

    @Test
    fun `desbloqueado y en uso el FSI no sirve y sin overlay queda heads-up`() {
        // Este es el bug real: el FSI está concedido, pero Android lo degrada a heads-up
        // con el teléfono desbloqueado y otra app en primer plano (GIM-44).
        val resolved = plan(
            AlertMode.SCREEN_VIBRATE,
            interactive = true,
            locked = false,
            fullScreen = true,
            overlay = false,
        )
        assertEquals(RestScreenDelivery.HEADS_UP, resolved.screen)
        assertFalse(
            "el plan no debe creer que el FSI va a tomar la pantalla",
            state(interactive = true, locked = false, fullScreen = true).fullScreenCanTakeOver,
        )
    }

    @Test
    fun `desbloqueado y en uso con overlay concedido se dibuja encima aunque el FSI exista`() {
        val resolved = plan(
            AlertMode.SCREEN,
            interactive = true,
            locked = false,
            fullScreen = true,
            overlay = true,
        )
        assertEquals(RestScreenDelivery.OVERLAY, resolved.screen)
    }

    @Test
    fun `con la pantalla apagada o el telefono bloqueado se usa el FSI`() {
        assertEquals(
            "pantalla apagada",
            RestScreenDelivery.FULL_SCREEN_INTENT,
            plan(AlertMode.SCREEN, interactive = false, locked = false, fullScreen = true).screen,
        )
        assertEquals(
            "pantalla encendida pero bloqueada",
            RestScreenDelivery.FULL_SCREEN_INTENT,
            plan(AlertMode.SCREEN, interactive = true, locked = true, fullScreen = true).screen,
        )
        assertTrue(state(locked = true, fullScreen = true).fullScreenCanTakeOver)
    }

    @Test
    fun `bloqueado sin FSI el overlay no sirve porque no se ve sobre el keyguard`() {
        assertEquals(
            RestScreenDelivery.HEADS_UP,
            plan(AlertMode.SCREEN, locked = true, fullScreen = false, overlay = true).screen,
        )
    }

    @Test
    fun `sin pantalla pedida el permiso no cambia nada`() {
        assertEquals(
            RestScreenDelivery.NONE,
            plan(AlertMode.VIBRATE_SOUND, fullScreen = false, overlay = true).screen,
        )
    }

    @Test
    fun `los tres patrones de vibracion son distintos y no se repiten`() {
        val patterns = RestVibration.entries.map { RestVibrationPatterns.pattern(it) }
        assertEquals(3, patterns.map { it.toList() }.toSet().size)
        patterns.forEach { pattern ->
            assertTrue("un patrón vacío no vibra", pattern.isNotEmpty())
            assertEquals("arranca con la espera en 0", 0L, pattern.first())
        }
        // Corto vibra menos que largo; doble es el default de §8 y vibra dos veces.
        assertTrue(RestVibrationPatterns.pattern(RestVibration.SHORT).sum() <
            RestVibrationPatterns.pattern(RestVibration.LONG).sum())
        assertEquals(4, RestVibrationPatterns.pattern(RestVibration.DOUBLE).size)
    }
}
