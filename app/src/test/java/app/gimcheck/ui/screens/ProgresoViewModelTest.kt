package app.gimcheck.ui.screens

import app.gimcheck.core.BodyMetric
import app.gimcheck.core.ChartRange
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
import app.gimcheck.data.repository.SessionRepository
import app.gimcheck.data.repository.SettingsRepository
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
 * Progreso (GIM-26/27/28) por estado: puntos de e1RM, PR actual, volumen semanal y
 * peso corporal con promedio de 7 días.
 *
 * Los números salen de `:core` y los tests los comparan con la fórmula a mano: si la
 * pantalla empieza a calcular por su cuenta, acá se ve.
 */
@RunWith(RobolectricTestRunner::class)
class ProgresoViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var exercises: ExerciseRepository
    private lateinit var sessions: SessionRepository
    private lateinit var metrics: MetricsRepository
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var viewModel: ProgresoViewModel

    private val today = LocalDate(2026, 11, 18)
    private val now = LocalDateTime(2026, 11, 18, 20, 0)

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        exercises = ExerciseRepository(db)
        sessions = SessionRepository(db, zone = ZoneOffset.UTC)
        metrics = MetricsRepository(db, zone = ZoneOffset.UTC)
        settings = SettingsRepository(db)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        db.seedPlan()
        viewModel = ProgresoViewModel(
            exercises = exercises,
            sessions = sessions,
            metrics = metrics,
            settings = settings,
            scope = scope,
            today = { today },
            now = { now },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private suspend fun session(id: String, date: LocalDateTime, weight: Double, reps: Int = 5) {
        sessions.start(
            Session(
                id = id,
                startedAt = date,
                routineId = ROUTINE_ID,
                routineDayId = DAY_ID,
            ),
        )
        sessions.recordSet(
            sessionId = id,
            set = SessionSet(
                id = "set-$id",
                exerciseId = "e1",
                weightKg = weight,
                reps = reps,
                completedAt = date,
            ),
            dayItemId = "i1",
        )
        sessions.finish(id, LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, 10, 0))
    }

    private fun awaitState(condition: (ProgresoUiState) -> Boolean): ProgresoUiState = runBlocking {
        withTimeout(5_000) { viewModel.state.first(condition) }
    }

    @Test
    fun `el PR muestra la mejor marca y contra que se supero`() = runBlocking {
        session("s1", LocalDateTime(2026, 11, 4, 9, 0), 100.0)
        session("s2", LocalDateTime(2026, 11, 11, 9, 0), 105.0)
        session("s3", LocalDateTime(2026, 11, 18, 9, 0), 110.0)
        viewModel.start()

        val state = awaitState { it.prValues.size == 3 }
        assertEquals(3, state.prValues.size)
        assertEquals(116.66666666666667, state.prValues[0], 1e-9)
        assertEquals(122.5, state.prValues[1], 1e-9)
        assertEquals(128.33333333333334, state.prValues[2], 1e-9)
        val pr = requireNotNull(state.pr)
        assertEquals("128,33 kg", pr.valueLabel)
        assertEquals("110 kg × 5", pr.setLabel)
        assertEquals("Fórmula Epley", pr.formulaLabel)
        assertTrue("el delta compara contra 122,5: ${pr.deltaLabel}", pr.deltaLabel!!.contains("122,5"))
    }

    @Test
    fun `cambiar la formula cambia la curva y el PR sin tocar datos`() = runBlocking {
        session("s1", LocalDateTime(2026, 11, 4, 9, 0), 100.0)
        session("s2", LocalDateTime(2026, 11, 11, 9, 0), 110.0)
        viewModel.start()
        awaitState { it.prValues.isNotEmpty() }

        settings.update { it.copy(e1rmFormula = E1rmFormula.BRZYCKI) }
        val state = awaitState { it.pr?.formulaLabel == "Fórmula Brzycki" }
        assertEquals(123.75, state.prValues.last(), 1e-9) // 110 * 36 / 32
    }

    @Test
    fun `el volumen suma por semana y muestra el delta contra la anterior`() = runBlocking {
        session("s1", LocalDateTime(2026, 11, 4, 9, 0), 100.0)
        session("s2", LocalDateTime(2026, 11, 11, 9, 0), 105.0)
        session("s3", LocalDateTime(2026, 11, 18, 9, 0), 110.0)
        viewModel.start()

        val state = awaitState { it.volumeValues.size == 3 }
        assertEquals(listOf(500.0, 525.0, 550.0), state.volumeValues)
        assertEquals("550 kg", state.currentWeekLabel)
        assertTrue(state.currentWeekDelta!!.startsWith("+25 kg"))

        viewModel.selectRange(ChartRange.THREE_MONTHS)
        assertTrue(viewModel.state.value.range == ChartRange.THREE_MONTHS)
    }

    @Test
    fun `las series por musculo de la semana actual salen del historico`() = runBlocking {
        session("s1", LocalDateTime(2026, 11, 11, 9, 0), 105.0)
        session("s2", LocalDateTime(2026, 11, 18, 9, 0), 110.0)
        viewModel.start()

        val state = awaitState { it.muscles.isNotEmpty() }
        val muscle = state.muscles.single()
        assertEquals("Pecho", muscle.name)
        assertEquals("1", muscle.totalLabel)
        assertTrue(muscle.detailLabel.contains("1"))
    }

    @Test
    fun `el peso se registra, se corrige y arma promedio de 7 dias`() = runBlocking {
        metrics.save(BodyMetric(date = LocalDate(2026, 11, 10), weightKg = 80.0, updatedAt = "2026-11-10T08:00"))
        metrics.save(BodyMetric(date = LocalDate(2026, 11, 11), weightKg = 79.5, updatedAt = "2026-11-11T08:00"))
        metrics.save(BodyMetric(date = LocalDate(2026, 11, 12), weightKg = 79.0, updatedAt = "2026-11-12T08:00"))
        viewModel.start()

        val state = awaitState { it.bodyValues.size == 3 }
        assertEquals(listOf(80.0, 79.5, 79.0), state.bodyValues)
        assertEquals("79 kg", state.bodyLatestLabel)
        assertTrue(state.bodyDeltaLabel!!.startsWith("−1 kg"))
        assertEquals(79.5, state.bodyAverage[2]!!, 1e-9)

        viewModel.editBodyRecord(LocalDate(2026, 11, 12), 78.0)
        viewModel.saveBody()
        val corrected = awaitState { it.bodyValues.lastOrNull() == 78.0 }
        assertEquals(listOf(80.0, 79.5, 78.0), corrected.bodyValues)
    }
}
