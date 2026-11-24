package app.gimcheck.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gimcheck.core.LogSet
import app.gimcheck.core.SessionEffect
import app.gimcheck.core.dayStats
import app.gimcheck.core.formatDuration
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.inMemoryDatabase
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.MetricsRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SessionRepository
import app.gimcheck.data.repository.SessionResumer
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.seed.SeedImporter
import app.gimcheck.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

/**
 * El ViewModel de **Hoy** contra el seed real, sin render.
 *
 * Los tres criterios del ticket que se pueden verificar en la JVM:
 * 1. con el seed, la pantalla expone **6 días** y la duración/series del día elegido
 *    son exactamente `dayStats` (no una segunda fórmula en la UI);
 * 2. el peso corporal escribe la `body_metric` de **hoy** y se relee (y se puede
 *    corregir);
 * 3. "Empezar sesión" crea la fila `session` + su `session_state` en el primer bloque.
 *
 * La verificación visual no existe en este equipo (`docs/build.md`): lo de abajo es
 * estado, no píxeles.
 */
@RunWith(RobolectricTestRunner::class)
class HoyViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val seedJson: String by lazy {
        context.assets.open("seed.json").bufferedReader().use { it.readText() }
    }

    /** Miércoles 18 de noviembre de 2026, 09:00, en UTC para que la fecha no dependa del equipo. */
    private val now: LocalDateTime = LocalDateTime(2026, 11, 18, 9, 0)
    private val t0: Instant = Instant.parse("2026-11-18T09:00:00Z")

    private lateinit var db: AppDatabase
    private lateinit var routines: RoutineRepository
    private lateinit var exercises: ExerciseRepository
    private lateinit var sessions: SessionRepository
    private lateinit var metrics: MetricsRepository
    private lateinit var settings: SettingsRepository
    private lateinit var resumer: SessionResumer
    private lateinit var scope: CoroutineScope
    private lateinit var viewModel: HoyViewModel

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        routines = RoutineRepository(db)
        exercises = ExerciseRepository(db)
        sessions = SessionRepository(db, zone = ZoneOffset.UTC)
        metrics = MetricsRepository(db, zone = ZoneOffset.UTC)
        settings = SettingsRepository(db)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        resumer = SessionResumer(sessions, scope, now = { t0 })
        SeedImporter(db).seedIfEmpty(seedJson)

        var id = 0
        viewModel = HoyViewModel(
            routines = routines,
            exercises = exercises,
            sessions = sessions,
            metrics = metrics,
            settings = settings,
            resumer = resumer,
            scope = scope,
            now = { now },
            nowInstant = { t0 },
            newId = { "session-${id++}" },
        )
        viewModel.start()
    }

    @After
    fun tearDown() {
        viewModel.stop()
        scope.cancel()
        db.close()
    }

    private suspend fun loadedState(): HoyUiState =
        withTimeout(5_000) { viewModel.state.first { !it.loading } }

    @Test
    fun `con el seed expone los 6 dias y las metricas del dia iguales a dayStats`() = runBlocking {
        val state = loadedState()

        assertTrue("el seed trae un plan", state.hasRoutine)
        assertEquals(6, state.days.size)
        assertEquals("Buenos días", state.greeting)
        assertEquals("miércoles 18 de noviembre", state.dateLabel)

        val all = routines.observeRoutines().first()
        val routine = all.first { candidate -> candidate.days.any { it.id == state.selectedDayId } }
        val day = requireNotNull(routine.days.firstOrNull { it.id == state.selectedDayId })
        val stats = dayStats(day.items, AppSettings.CORE_DEFAULT)

        assertEquals(day.name, state.selectedDayName)
        assertEquals(formatDuration(stats.totalSeconds), state.durationLabel)
        assertEquals(stats.sets.toString(), state.setsLabel)
        assertEquals(day.id, state.days.single { it.selected }.id)
    }

    @Test
    fun `con la rutina B activa Hoy arranca su dia y la superserie salta`() = runBlocking {
        loadedState()
        val all = routines.observeRoutines().first()
        val routineB = requireNotNull(all.firstOrNull { it.name.startsWith("Rutina B") }) {
            "el seed tiene que traer la Rutina B"
        }
        settings.setActiveRoutine(routineB.id)

        // El plan activo es una fila aparte: la pantalla tiene que reaccionar al cambio.
        val state = withTimeout(5_000) {
            viewModel.state.first { !it.loading && it.routineName == routineB.name }
        }
        assertEquals(6, state.days.size)

        val day = requireNotNull(routineB.days.firstOrNull { it.id == state.selectedDayId })
        val press = requireNotNull(day.items.firstOrNull())
        assertEquals("Press plano", press.exercise.name)
        assertTrue("la Rutina B del seed tiene superserie en el press plano", press.supersetNext)

        assertTrue("la sesión tiene que arrancar desde Hoy", viewModel.startSessionNow())
        val guided = requireNotNull(sessions.guidedNow()) { "no se creó la sesión guiada" }
        assertEquals(day.id, guided.day.id)

        val transition = sessions.dispatch(
            guided,
            LogSet(weightKg = 60.0, reps = 5, isWarmup = false, at = now, now = t0),
            t0,
        )
        assertEquals(
            "anotar el press plano tiene que dejar el cursor en el siguiente de la superserie",
            "Antebrazo",
            transition.state.currentItem?.exercise?.name,
        )
        assertTrue(SessionEffect.ShowNextInSuperset in transition.effects)
    }

    @Test
    fun `el peso corporal de hoy se escribe y se relee`() = runBlocking {
        loadedState()

        viewModel.setBodyWeight(81.3)
        viewModel.saveBodyWeightNow()

        val saved = requireNotNull(metrics.get(now.date)) { "no se escribió la body_metric de hoy" }
        assertEquals(81.3, saved.weightKg, 1e-9)

        val afterSave = withTimeout(5_000) { viewModel.state.first { it.bodyWeightSaved } }
        assertEquals("81,3", afterSave.bodyWeightLabel)
        assertEquals(81.3, afterSave.bodyWeightDraft, 1e-9)

        // "Corregir": la misma fecha se sobrescribe, no se duplica.
        viewModel.setBodyWeight(80.8)
        viewModel.saveBodyWeightNow()
        val corrected = withTimeout(5_000) { viewModel.state.first { it.bodyWeightLabel == "80,8" } }
        assertEquals(80.8, corrected.bodyWeightDraft, 1e-9)
        assertEquals(1, metrics.observeAll().first().count { it.date == now.date })
    }

    @Test
    fun `empezar sesion crea session y session_state en el primer bloque`() = runBlocking {
        val state = loadedState()

        assertTrue(viewModel.startSessionNow())

        val active = withTimeout(5_000) { sessions.observeActive().first { it != null } }
        assertNotNull(active)
        assertEquals(state.selectedDayId, active?.routineDayId)

        val guided = withTimeout(5_000) { sessions.observeGuided().first { it != null } }
        assertNotNull(guided)
        assertEquals(0, guided?.blockIndex)
        assertEquals(0, guided?.roundIndex)
        assertEquals(0, guided?.itemIndex)
    }
}
