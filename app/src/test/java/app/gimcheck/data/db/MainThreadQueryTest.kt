package app.gimcheck.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Ninguna consulta en el hilo principal" (§ criterio de GIM-9).
 *
 * Se prueba por los dos lados:
 *  - una consulta **bloqueante** en el hilo principal revienta (la base no se
 *    construye con `allowMainThreadQueries`);
 *  - las escrituras **suspend** de los DAO sí se pueden lanzar desde el hilo
 *    principal, porque Room las despacha a su executor.
 */
@RunWith(RobolectricTestRunner::class)
class MainThreadQueryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(AppDatabase.FILE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(AppDatabase.FILE_NAME)
    }

    @Test
    fun `una consulta bloqueante en el hilo principal lanza IllegalStateException`() {
        val db = AppDatabase.build(context)
        try {
            val thrown = assertThrows(IllegalStateException::class.java) {
                db.query("SELECT 1", null)
            }
            assertTrue(
                "mensaje inesperado: ${thrown.message}",
                thrown.message.orEmpty().contains("main thread"),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `las escrituras suspend de los DAO si corren desde el hilo principal`() {
        val db = AppDatabase.build(context)
        try {
            runBlocking {
                db.muscleDao().upsert(muscle("m1", "Pecho"))
                assertEquals(1, db.muscleDao().countActive())
            }
        } finally {
            db.close()
        }
    }
}
