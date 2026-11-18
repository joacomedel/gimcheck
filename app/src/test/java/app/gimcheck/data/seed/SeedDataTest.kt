package app.gimcheck.data.seed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gimcheck.core.DomainSnapshot
import app.gimcheck.core.Routine
import app.gimcheck.core.Settings
import app.gimcheck.core.dayStats
import app.gimcheck.core.importWeb
import app.gimcheck.core.valueOrThrow
import app.gimcheck.core.volume
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.inMemoryDatabase
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * El seed de `assets/seed.json`, de punta a punta.
 *
 * Los números esperados son los de `docs/paridad-web.md` (Rutina A del HTML con los
 * ajustes por defecto): **15.100 s** y **106 series** por semana, y el volumen por
 * músculo que publica la web (Pecho 16 directas, 1,75 indirectas, 3 días).
 *
 * El seed no se escribe a mano: lo genera `scripts/gen-seed.py` desde el mismo estado
 * de la web que validan los tests de `:core`, y la app lo importa con `importWeb`. Si
 * alguien edita el JSON a mano o cambia un default, estos asserts fallan.
 */
@RunWith(RobolectricTestRunner::class)
class SeedDataTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val seedJson: String by lazy {
        context.assets.open("seed.json").bufferedReader().use { it.readText() }
    }

    private val snapshot: DomainSnapshot by lazy { importWeb(seedJson).valueOrThrow().snapshot }

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val routineA: Routine
        get() = requireNotNull(snapshot.activeRoutine()) { "el seed no tiene rutina activa" }

    @Test
    fun `el seed tiene 6 dias y 32 ejercicios`() {
        assertEquals(1, snapshot.routines.size)
        assertEquals("Rutina A (original)", routineA.name)
        assertEquals(
            listOf("Día 1", "Día 2", "Día 3", "Día 4", "Día 5", "Día 6"),
            routineA.days.map { it.name },
        )
        assertEquals(6, routineA.days.size)
        assertEquals(39, routineA.days.sumOf { it.items.size })
        assertEquals(32, snapshot.exercises.size)
        assertEquals(15, snapshot.muscles.size)
    }

    @Test
    fun `las metricas del seed coinciden con la web`() {
        val days = routineA.days
        val settings: Settings = snapshot.settings

        val stats = days.map { dayStats(it.items, settings) }
        assertEquals(
            "total por día (web)",
            listOf(2505, 2565, 2490, 2885, 2535, 2120),
            stats.map { it.totalSeconds },
        )
        assertEquals(15100, stats.sumOf { it.totalSeconds })
        assertEquals(106, stats.sumOf { it.sets })

        val weekly = volume(days, settings, snapshot.muscles)
        val chest = weekly.getValue("Pecho")
        assertEquals(16.0, chest.direct, 1e-9)
        assertEquals(1.75, chest.indirect, 1e-9)
        assertEquals(3, chest.days)
        assertEquals(106.0, weekly.values.sumOf { it.direct }, 1e-9)
        assertEquals(28.0, weekly.values.sumOf { it.indirect }, 1e-9)
    }

    @Test
    fun `los ajustes del seed son los defaults de la seccion 8`() {
        // El seed no escribe ajustes: si la web y §8 divergieran, la app arrancaría con
        // números distintos a los de la web y este assert lo detecta.
        assertEquals(AppSettings.DEFAULT.toCoreSettings(), snapshot.settings)
        assertEquals(Settings.DEFAULT, snapshot.settings)
    }

    @Test
    fun `el seed se escribe una sola vez y conserva las metricas al pasar por Room`() {
        runBlocking {
            val importer = SeedImporter(db)

            assertTrue("la primera carga tiene que escribir", importer.seedIfEmpty(seedJson))
            assertEquals(1, db.routineDao().count())
            assertEquals(15, db.muscleDao().all().size)
            assertEquals(32, db.exerciseDao().all().size)
            assertEquals(6, db.routineDao().daysOf(routineA.id).size)

            // Segundo arranque: no pisa nada (idempotente).
            assertFalse(importer.seedIfEmpty(seedJson))
            assertEquals(1, db.routineDao().count())
            assertEquals(32, db.exerciseDao().all().size)

            // Lo que la app va a calcular sale de Room, no del JSON: si el mapeo
            // entidad ↔ dominio pierde una serie o un descanso, acá se ve.
            val loaded = RoutineRepository(db).observeRoutines().first().single()
            assertEquals(routineA.id, loaded.id)
            val stats = loaded.days.map { dayStats(it.items, snapshot.settings) }
            assertEquals(15100, stats.sumOf { it.totalSeconds })
            assertEquals(106, stats.sumOf { it.sets })
            assertEquals(
                routineA.days.map { day -> day.items.map { it.id to it.sets } },
                loaded.days.map { day -> day.items.map { it.id to it.sets } },
            )
        }
    }
}
