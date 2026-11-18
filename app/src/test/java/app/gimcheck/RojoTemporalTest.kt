package app.gimcheck

import org.junit.Assert.assertTrue
import org.junit.Test

/** Temporal: verifica que un test rojo de :app pone el run de CI en rojo. */
class RojoTemporalTest {
    @Test
    fun rojo() {
        assertTrue(false)
    }
}
