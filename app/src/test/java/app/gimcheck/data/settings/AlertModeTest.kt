package app.gimcheck.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las tres capas del aviso de fin de descanso son una vista de `alertMode` (§5):
 * prender o apagar una no puede pisar las otras dos. Es la regla que usa la pantalla
 * de Ajustes y la que evita que un toque deje el aviso en silencio sin querer.
 */
class AlertModeTest {

    @Test
    fun `las banderas leen las ocho combinaciones`() {
        assertTrue(AlertMode.SCREEN_VIBRATE_SOUND.showsScreen)
        assertTrue(AlertMode.SCREEN_VIBRATE_SOUND.vibrates)
        assertTrue(AlertMode.SCREEN_VIBRATE_SOUND.sounds)

        assertTrue(AlertMode.SCREEN_SOUND.showsScreen)
        assertFalse(AlertMode.SCREEN_SOUND.vibrates)
        assertTrue(AlertMode.SCREEN_SOUND.sounds)

        assertTrue(AlertMode.VIBRATE_SOUND.sounds)
        assertTrue(AlertMode.VIBRATE_SOUND.vibrates)
        assertFalse(AlertMode.VIBRATE_SOUND.showsScreen)

        assertFalse(AlertMode.NONE.showsScreen)
        assertFalse(AlertMode.NONE.vibrates)
        assertFalse(AlertMode.NONE.sounds)
    }

    @Test
    fun `recomponer es total y no pierde ninguna capa`() {
        val modes = mutableSetOf<AlertMode>()
        for (screen in listOf(false, true)) {
            for (vibrate in listOf(false, true)) {
                for (sound in listOf(false, true)) {
                    val mode = alertModeOf(screen, vibrate, sound)
                    modes += mode
                    assertEquals(screen, mode.showsScreen)
                    assertEquals(vibrate, mode.vibrates)
                    assertEquals(sound, mode.sounds)
                }
            }
        }
        assertEquals(8, modes.size)
    }
}
