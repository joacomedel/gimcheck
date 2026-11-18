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
 * 3. la cadena de respaldo: full-screen intent → overlay → heads-up;
 * 4. los tres patrones de `restVibration` son distintos.
 */
class RestAlertPlanTest {

    private fun plan(
        mode: AlertMode,
        foreground: Boolean = false,
        fullScreen: Boolean = true,
        overlay: Boolean = false,
    ) = RestAlertPlans.forMode(
        mode = mode,
        appInForeground = foreground,
        fullScreenAllowed = fullScreen,
        overlayAllowed = overlay,
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
            val resolved = plan(mode)
            assertEquals("pantalla de $mode", screen, resolved.screen != RestScreenDelivery.NONE)
            assertEquals("vibración de $mode", vibrate, resolved.vibrate)
            assertEquals("sonido de $mode", sound, resolved.sound)
        }
    }

    @Test
    fun `NONE no dispara nada y aun asi no rompe la notificacion`() {
        val resolved = plan(AlertMode.NONE, fullScreen = true)
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
            val resolved = plan(mode, foreground = true, fullScreen = true, overlay = true)
            assertEquals("pantalla con la app abierta en $mode", RestScreenDelivery.NONE, resolved.screen)
        }
        // La señal que sí queda es la vibración/sonido del modo.
        val withVibration = plan(AlertMode.SCREEN_VIBRATE, foreground = true)
        assertTrue(withVibration.vibrate)
        assertFalse(withVibration.sound)
    }

    @Test
    fun `el respaldo es full-screen intent y despues overlay y despues heads-up`() {
        assertEquals(
            RestScreenDelivery.FULL_SCREEN_INTENT,
            plan(AlertMode.SCREEN, fullScreen = true, overlay = false).screen,
        )
        assertEquals(
            RestScreenDelivery.OVERLAY,
            plan(AlertMode.SCREEN, fullScreen = false, overlay = true).screen,
        )
        assertEquals(
            RestScreenDelivery.HEADS_UP,
            plan(AlertMode.SCREEN, fullScreen = false, overlay = false).screen,
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
