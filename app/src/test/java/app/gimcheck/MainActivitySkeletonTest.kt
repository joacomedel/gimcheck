package app.gimcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Esqueleto de `:app`: la activity existe, está en el package correcto y el
 * `applicationId` generado por el build coincide.
 *
 * No usa Robolectric a propósito: para GIM-35 el objetivo es que CI ejecute
 * `:app:testDebugUnitTest`, y un test JVM puro compila y corre sin descargar
 * `android-all`. Cuando GIM-9 (Room) necesite recursos y contexto se activa
 * `testOptions { unitTests.isIncludeAndroidResources = true }` y se agrega
 * `robolectric` (ya está en el catálogo).
 */
class MainActivitySkeletonTest {

    private val activityClass: Class<*> = Class.forName("app.gimcheck.MainActivity")

    @Test
    fun `el applicationId generado es el package de la app`() {
        assertEquals("app.gimcheck", BuildConfig.APPLICATION_ID)
        assertEquals("app.gimcheck", activityClass.packageName)
    }

    @Test
    fun `MainActivity es una ComponentActivity publica y concreta`() {
        assertTrue(
            "MainActivity debe extender ComponentActivity",
            ComponentActivity::class.java.isAssignableFrom(activityClass),
        )
        assertTrue("MainActivity debe ser public", Modifier.isPublic(activityClass.modifiers))
        assertTrue("MainActivity no debe ser abstract", !Modifier.isAbstract(activityClass.modifiers))
    }

    @Test
    fun `MainActivity declara onCreate con el Bundle del ciclo de vida`() {
        // getMethod (y no getDeclaredMethod) resuelve sólo métodos públicos: si
        // esto no lanza NoSuchMethodException, onCreate existe y es público.
        val onCreate = activityClass.getMethod("onCreate", Bundle::class.java)
        assertNotNull(onCreate)
        assertEquals(activityClass, onCreate.declaringClass)
    }
}
