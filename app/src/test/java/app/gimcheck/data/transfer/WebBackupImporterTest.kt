package app.gimcheck.data.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.SessionEntity
import app.gimcheck.data.db.SessionStatus
import app.gimcheck.data.db.inMemoryDatabase
import app.gimcheck.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Import de un backup de la web sobre una base con datos (GIM-45).
 *
 * El archivo de prueba es el **seed real** (`assets/seed.json`, el backup del dueño):
 * 2 rutinas, 12 días, 32 ejercicios y 15 músculos. Se importa sobre una base vacía y
 * sobre una que ya lo tenía para verificar que **reemplaza sin duplicar** y que el
 * historial de sesiones sobrevive.
 */
@RunWith(RobolectricTestRunner::class)
class WebBackupImporterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val seedJson: String by lazy {
        context.assets.open("seed.json").bufferedReader().use { it.readText() }
    }

    /** `curPlan` del backup, sin hardcodear un id de `uid()`. */
    private val activeId: String by lazy {
        org.json.JSONObject(seedJson).getString("curPlan")
    }

    private lateinit var db: AppDatabase
    private lateinit var importer: WebBackupImporter

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        importer = WebBackupImporter(db, SettingsRepository(db))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun counts(): Triple<Int, Int, Int> = runBlocking {
        Triple(db.routineDao().count(), db.exerciseDao().all().size, db.muscleDao().all().size)
    }

    @Test
    fun `importa las dos rutinas, los 12 dias y el catalogo completo`() = runBlocking {
        val outcome = importer.import(seedJson)

        assertTrue("se esperaba Imported y fue $outcome", outcome is WebImportOutcome.Imported)
        outcome as WebImportOutcome.Imported
        assertEquals(2, outcome.routines)
        assertEquals(12, outcome.days)
        assertEquals(32, outcome.exercises)
        assertEquals(15, outcome.muscles)

        assertEquals(Triple(2, 32, 15), counts())
        assertEquals(
            "el plan activo es el curPlan del backup",
            activeId,
            SettingsRepository(db).activeRoutineId.first(),
        )
    }

    @Test
    fun `importar dos veces no duplica rutinas ni ejercicios`() = runBlocking {
        importer.import(seedJson)
        val second = importer.import(seedJson)

        assertTrue(second is WebImportOutcome.Imported)
        assertEquals(Triple(2, 32, 15), counts())
        assertEquals(
            "12 días entre las dos rutinas, sin duplicar",
            12,
            db.routineDao().observeAll().first().sumOf { db.routineDao().daysOf(it.id).size },
        )
    }

    @Test
    fun `un archivo invalido no toca la base`() = runBlocking {
        importer.import(seedJson)

        val outcome = importer.import("{ esto no es un backup")

        assertTrue("se esperaba Invalid y fue $outcome", outcome is WebImportOutcome.Invalid)
        assertEquals(Triple(2, 32, 15), counts())
    }

    @Test
    fun `el historial de sesiones sobrevive al import`() = runBlocking {
        importer.import(seedJson)
        db.sessionDao().upsert(
            SessionEntity(
                id = "sesion-historica",
                routineId = activeId,
                routineDayId = null,
                startedAt = 1_700_000_000_000,
                endedAt = 1_700_000_600_000,
                status = SessionStatus.FINISHED,
            ),
        )

        importer.import(seedJson)

        assertNotNull(
            "el import no puede borrar entrenamientos ya guardados",
            db.sessionDao().getById("sesion-historica"),
        )
    }
}
