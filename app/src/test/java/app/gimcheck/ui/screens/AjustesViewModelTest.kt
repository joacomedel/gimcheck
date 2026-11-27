package app.gimcheck.ui.screens

import app.gimcheck.core.BodyMetric
import app.gimcheck.core.E1rmFormula
import app.gimcheck.core.Session
import app.gimcheck.core.SessionSet
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.DAY_ID
import app.gimcheck.data.db.ROUTINE_ID
import app.gimcheck.data.db.inMemoryDatabase
import app.gimcheck.data.db.seedPlan
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.MetricsRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SessionRepository
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.settings.AppSettings
import app.gimcheck.data.settings.showsScreen
import app.gimcheck.data.settings.sounds
import app.gimcheck.data.settings.vibrates
import app.gimcheck.data.settings.RestSound
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.data.transfer.BackupExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

/**
 * Ajustes (GIM-29) por estado: cada stepper/chip escribe `app_setting`, el tema y la
 * alerta se recomponen sin perder las otras capas, y el backup exportado es un JSON
 * válido con las sesiones y las rutinas.
 */
@RunWith(RobolectricTestRunner::class)
class AjustesViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var exercises: ExerciseRepository
    private lateinit var routines: RoutineRepository
    private lateinit var sessions: SessionRepository
    private lateinit var metrics: MetricsRepository
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var viewModel: AjustesViewModel

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        exercises = ExerciseRepository(db)
        routines = RoutineRepository(db)
        sessions = SessionRepository(db, zone = ZoneOffset.UTC)
        metrics = MetricsRepository(db, zone = ZoneOffset.UTC)
        settings = SettingsRepository(db)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        db.seedPlan()
        val exporter = BackupExporter(
            routines = routines,
            exercises = exercises,
            sessions = sessions,
            metrics = metrics,
            settings = settings,
            now = { LocalDateTime(2026, 11, 27, 10, 0) },
        )
        viewModel = AjustesViewModel(settings, exporter, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun awaitState(condition: (AjustesUiState) -> Boolean): AjustesUiState = runBlocking {
        withTimeout(5_000) { viewModel.state.first(condition) }
    }

    @Test
    fun `los steppers escriben el ajuste que corresponde`() = runBlocking {
        viewModel.start()
        awaitState { !it.loading }

        viewModel.setWarm(10)
        viewModel.setOptMin(12)
        viewModel.setFormula(E1rmFormula.BRZYCKI)

        val state = awaitState {
            it.settings.warm == 10 &&
                it.settings.optMin == 12 &&
                it.settings.e1rmFormula == E1rmFormula.BRZYCKI
        }
        assertEquals(10, state.settings.warm)
        assertEquals(12, state.settings.optMin)
        assertEquals(E1rmFormula.BRZYCKI, state.settings.e1rmFormula)
        assertEquals(E1rmFormula.BRZYCKI, settings.current().e1rmFormula)
    }

    @Test
    fun `el aviso de descanso conserva las capas que no se tocan`() = runBlocking {
        viewModel.start()
        awaitState { !it.loading }

        viewModel.toggleAlertSound(true)
        awaitState { it.settings.alertMode.sounds }

        viewModel.toggleAlertScreen(false)
        val state = awaitState { !it.settings.alertMode.showsScreen }
        assertTrue("la vibración sigue prendida", state.settings.alertMode.vibrates)
        assertTrue("el sonido sigue prendido", state.settings.alertMode.sounds)

        viewModel.setRestSound(RestSound.CHIME)
        val changed = awaitState { it.settings.restSound == RestSound.CHIME }
        assertEquals(RestSound.CHIME, changed.settings.restSound)
    }

    @Test
    fun `el tema se persiste para que la app repinte`() = runBlocking {
        viewModel.start()
        awaitState { !it.loading }
        viewModel.setTheme(ThemeMode.DARK)
        val state = awaitState { it.settings.theme == ThemeMode.DARK }
        assertEquals(ThemeMode.DARK, state.settings.theme)
    }

    @Test
    fun `volver a defaults no borra el historico`() = runBlocking {
        viewModel.start()
        awaitState { !it.loading }
        sessions.start(Session(id = "s1", startedAt = LocalDateTime(2026, 11, 18, 9, 0), routineId = ROUTINE_ID, routineDayId = DAY_ID))
        sessions.finish("s1", LocalDateTime(2026, 11, 18, 10, 0))

        viewModel.setWarm(20)
        awaitState { it.settings.warm == 20 }
        viewModel.resetSettings()

        val reset = awaitState { it.settings.warm == AppSettings.DEFAULT.warm }
        assertEquals(0, reset.settings.warm - AppSettings.DEFAULT.warm)
        assertEquals(1, sessions.allSessions().size)
    }

    @Test
    fun `el backup exportado es un JSON del contrato con sesiones y rutinas`() = runBlocking {
        sessions.start(Session(id = "s1", startedAt = LocalDateTime(2026, 11, 18, 9, 0), routineId = ROUTINE_ID, routineDayId = DAY_ID))
        sessions.recordSet(
            sessionId = "s1",
            set = SessionSet(
                id = "set1",
                exerciseId = "e1",
                weightKg = 100.0,
                reps = 5,
                completedAt = LocalDateTime(2026, 11, 18, 9, 5),
            ),
            dayItemId = "i1",
        )
        sessions.finish("s1", LocalDateTime(2026, 11, 18, 10, 0))
        metrics.save(BodyMetric(date = LocalDate(2026, 11, 18), weightKg = 80.0, updatedAt = "2026-11-18T08:00"))

        viewModel.start()
        awaitState { !it.loading }
        viewModel.buildBackup()

        val ready = awaitState { it.export is BackupExportResult.Ready }.export as BackupExportResult.Ready
        assertEquals("gimcheck-backup-2026-11-27.json", ready.name)
        assertTrue(ready.json.contains("\"schema\""))
        assertTrue(ready.json.contains("s1"))
        assertTrue(ready.json.contains("Rutina A"))
        assertTrue(ready.json.contains("2026-11-18"))
    }
}
