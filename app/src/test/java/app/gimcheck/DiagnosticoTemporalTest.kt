package app.gimcheck

import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Modifier

class DiagnosticoTemporalTest {
    @Test
    fun dump() {
        val c = Class.forName("app.gimcheck.MainActivity")
        val ms = c.declaredMethods.joinToString(" | ") {
            "${it.name}(${it.parameterTypes.joinToString { p -> p.name }}) mod=${Modifier.toString(it.modifiers)}/${it.modifiers} syn=${it.isSynthetic}"
        }
        val sup = c.superclass.name
        fail("SUPER=$sup :: $ms :: src=${c.protectionDomain?.codeSource?.location} :: bundle=${android.os.Bundle::class.java.name} cl=${android.os.Bundle::class.java.classLoader}")
    }
}
