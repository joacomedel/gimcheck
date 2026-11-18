package app.gimcheck.ui.screens

import app.gimcheck.AppContainer
import app.gimcheck.core.BodyMetric
import app.gimcheck.core.GuidedSession
import app.gimcheck.core.Routine
import app.gimcheck.core.RoutineDay
import app.gimcheck.core.Session
import app.gimcheck.core.StartSession
import app.gimcheck.core.dayStats
import app.gimcheck.core.formatDuration
import app.gimcheck.core.formatNumber
import app.gimcheck.core.sessionVolume
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.MetricsRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SessionRepository
import app.gimcheck.data.repository.SessionResumer
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.repository.ActiveSession
import app.gimcheck.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Estado de la pantalla **Hoy** (§6 fila 1).
 *
 * Todo lo que la pantalla muestra sale de acá: la pantalla sólo compone. Los números
 * (`duración`, `series`, `volumen`) los calcula `:core` (`dayStats`, `sessionVolume`) y
 * los formatea el borde (`formatDuration`, `formatNumber`), no el composable.
 */
data class HoyUiState(
    val loading: Boolean = true,
    /** `Buenos días` / `Buenas tardes` / `Buenas noches` según [hour]. */
    val greeting: String = "",
    /** `miércoles 18 de noviembre`: día de la semana + número, en español. */
    val dateLabel: String = "",
    val hasRoutine: Boolean = false,
    val routineName: String = "",
    val days: List<HoyDayUi> = emptyList(),
    val selectedDayId: String? = null,
    val selectedDayName: String = "",
    /** Duración estimada del día (`dayStats.totalSeconds`) ya formateada: `42 min`. */
    val durationLabel: String = "—",
    /** Series totales del día (`dayStats.sets`): `18`. */
    val setsLabel: String = "—",
    /** Peso corporal anotado hoy, o `null` si todavía no hay `body_metric`. */
    val bodyWeightLabel: String? = null,
    /** Valor del stepper; arranca en el peso de hoy, el último cargado o 80 kg. */
    val bodyWeightDraft: Double = 80.0,
    /** `true` si ya existe la `body_metric` de hoy (el botón pasa a "Corregir"). */
    val bodyWeightSaved: Boolean = false,
    /** Sesión `ACTIVE` reconstruida por [SessionResumer] (`null` si no hay). */
    val active: HoyActiveUi? = null,
    /** `true` si la sesión abierta es de otro día: la pantalla lo avisa en `warn`. */
    val activeOtherDay: Boolean = false,
    /** Últimos 3 entrenamientos, más reciente primero. */
    val recent: List<HoyRecentUi> = emptyList(),
)

/** Chip de día. */
data class HoyDayUi(
    val id: String,
    val name: String,
    val selected: Boolean,
)

/**
 * Sesión abierta lista para continuar.
 *
 * @param minutesFromStart minutos desde `session.started_at` (`Continuar sesión (12 min)`).
 */
data class HoyActiveUi(
    val sessionId: String,
    val dayId: String,
    val dayName: String,
    val minutesFromStart: Int,
)

/** Fila de "Últimos": fecha corta, volumen y series. */
data class HoyRecentUi(
    val dateLabel: String,
    val volumeLabel: String,
    val setCount: Int,
)

/**
 * ViewModel de **Hoy** con `StateFlow` (sin Hilt: recibe el [AppContainer]).
 *
 * **Selección rutina → día**: `routine.active_day` no se persiste (`Mappers` lo deja en
 * `null` a propósito: "la rutina/día activo lo decide la UI", GIM-16). Acá el día
 * elegido arranca en `activeDayId ?: primer día` y vive en memoria mientras la pantalla
 * está viva; el chip lo cambia sin escribir en la base.
 *
 * **Peso corporal**: [setBodyWeight] mueve el borrador en memoria; [saveBodyWeight]
 * escribe la `body_metric` de **hoy** (clave natural de §2). Si ya existía, la
 * sobrescribe, que es la corrección que pide el ticket.
 *
 * **Empezar sesión**: no reimplementa la máquina. Arma el [GuidedSession] del día
 * elegido y le pasa [StartSession] a [SessionRepository.dispatch]; el repositorio
 * persiste la fila `session` y el `session_state` del primer bloque (efecto
 * `PersistState`). Después refresca [SessionResumer] para que el botón pase a
 * "Continuar sesión" sin esperar un `ON_START`.
 */
class HoyViewModel internal constructor(
    private val routines: RoutineRepository,
    private val exercises: ExerciseRepository,
    private val sessions: SessionRepository,
    private val metrics: MetricsRepository,
    private val settings: SettingsRepository,
    private val resumer: SessionResumer,
    private val scope: CoroutineScope,
    private val now: () -> LocalDateTime = { java.time.LocalDateTime.now().toKotlinLocalDateTime() },
    private val nowInstant: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    constructor(container: AppContainer, scope: CoroutineScope) : this(
        routines = container.routines,
        exercises = container.exercises,
        sessions = container.sessions,
        metrics = container.metrics,
        settings = container.settings,
        resumer = container.sessionResumer,
        scope = scope,
    )

    private val today: LocalDate = now().date

    private val _state = MutableStateFlow(
        HoyUiState(
            greeting = greetingFor(now().hour),
            dateLabel = dateLabel(today),
        ),
    )

    /** Lo que compone la pantalla. */
    val state: StateFlow<HoyUiState> = _state.asStateFlow()

    /** Peso que el usuario está tocando en el stepper; `null` = todavía no lo tocó. */
    private var draft: Double? = null

    /** Día elegido; `null` = todavía no se eligió (se resuelve con el primero del plan). */
    private var selectedDayId: String? = null

    private var observation: Job? = null
    private var lastData: HoyData? = null

    /** Empieza a observar repositorios. Idempotente: la pantalla la llama una vez. */
    fun start() {
        if (observation != null) return
        observation = scope.launch {
            combine(
                routines.observeRoutines(),
                exercises.observeExercises(),
                settings.settings,
                sessions.observeHistory(),
                metrics.observeAll(),
            ) { plans, catalog, prefs, history, bodyMetrics ->
                HoyData(plans, catalog, prefs, history, bodyMetrics, resumer.resumed.value)
            }.combine(resumer.resumed) { data, resumed -> data.copy(resumed = resumed) }
                .collect { data ->
                    lastData = data
                    publish(data)
                }
        }
    }

    fun stop() {
        observation?.cancel()
        observation = null
    }

    /** Cambia el día del plan sin tocar la base (ver KDoc de la clase). */
    fun selectDay(dayId: String) {
        selectedDayId = dayId
        lastData?.let(::publish)
    }

    /** Borrador del peso corporal (paso 0,1 kg). No escribe hasta [saveBodyWeight]. */
    fun setBodyWeight(value: Double) {
        val rounded = roundTenth(value)
        draft = rounded
        _state.value = _state.value.copy(bodyWeightDraft = rounded)
    }

    /** Escribe (o corrige) la `body_metric` de hoy. */
    fun saveBodyWeight() {
        scope.launch { saveBodyWeightNow() }
    }

    internal suspend fun saveBodyWeightNow() {
        val weight = draft ?: return
        metrics.save(
            BodyMetric(
                date = today,
                weightKg = roundTenth(weight),
                updatedAt = now().toString(),
            ),
        )
    }

    /**
     * Crea la sesión del día elegido y su cursor. Devuelve `false` si no hay rutina/día.
     *
     * La pantalla navega a Entrenar sólo cuando esto devuelve `true`: no se abre una
     * pantalla de sesión sin sesión en la base.
     */
    fun startSession(onResult: (Boolean) -> Unit) {
        scope.launch { onResult(startSessionNow()) }
    }

    internal suspend fun startSessionNow(): Boolean {
        val dayId = selectedDayId ?: _state.value.selectedDayId ?: return false
        val day = routines.day(dayId) ?: return false
        val sessionId = newId()
        sessions.dispatch(
            state = GuidedSession(day = day, sessionId = sessionId),
            event = StartSession(sessionId = sessionId, now = nowInstant()),
            now = nowInstant(),
        )
        resumer.resume()
        return true
    }

    /** Descarta la sesión abierta (la deja `ABANDONED`) y refresca el estado. */
    fun discardActiveSession() {
        scope.launch {
            val active = resumer.resumed.value
            val sessionId = when (active) {
                is ActiveSession.Resumable -> active.state.sessionId
                is ActiveSession.MissingPlan -> active.sessionId
                else -> null
            } ?: return@launch
            sessions.abandon(sessionId, now())
            resumer.resume()
        }
    }

    // --- proyección a la UI -----------------------------------------------------

    private fun publish(data: HoyData) {
        val routine = data.routines.firstOrNull { !it.archived } ?: data.routines.firstOrNull()
        if (routine == null) {
            _state.value = _state.value.copy(loading = false, hasRoutine = false, days = emptyList())
            return
        }

        val day = routine.dayFor(selectedDayId) ?: run {
            _state.value = _state.value.copy(loading = false, hasRoutine = false, days = emptyList())
            return
        }
        selectedDayId = day.id
        val coreSettings = data.settings.toCoreSettings()
        val stats = dayStats(day.items, coreSettings)
        val todayMetric = data.bodyMetrics.firstOrNull { it.date == today }
        val weight = draft ?: todayMetric?.weightKg ?: data.bodyMetrics.firstOrNull()?.weightKg ?: DEFAULT_WEIGHT_KG
        draft = weight

        val active = data.resumed.toUi()

        _state.value = HoyUiState(
            loading = false,
            greeting = greetingFor(now().hour),
            dateLabel = dateLabel(today),
            hasRoutine = true,
            routineName = routine.name,
            days = routine.days.map { HoyDayUi(it.id, it.name, it.id == day.id) },
            selectedDayId = day.id,
            selectedDayName = day.name,
            durationLabel = formatDuration(stats.totalSeconds),
            setsLabel = stats.sets.toString(),
            bodyWeightLabel = todayMetric?.let { formatNumber(it.weightKg) },
            bodyWeightDraft = roundTenth(weight),
            bodyWeightSaved = todayMetric != null,
            active = active,
            activeOtherDay = active != null && active.dayId != day.id,
            recent = data.history.take(RECENT_COUNT).map { session ->
                session.toRecent(data.exercises, coreSettings)
            },
        )
    }

    private fun Routine.dayFor(selected: String?): RoutineDay? =
        days.firstOrNull { it.id == selected } ?: days.firstOrNull()

    private fun ActiveSession?.toUi(): HoyActiveUi? = when (val session = this) {
        is ActiveSession.Resumable -> {
            val startedAt = session.state.startedAt
            val minutes = if (startedAt != null) {
                Duration.between(startedAt, nowInstant()).toMinutes().coerceAtLeast(0).toInt()
            } else {
                0
            }
            HoyActiveUi(
                sessionId = session.state.sessionId,
                dayId = session.state.day.id,
                dayName = session.state.day.name,
                minutesFromStart = minutes,
            )
        }
        // Sin plan no hay nada que guiar; el botón vuelve a "Empezar" y descartar
        // sigue disponible desde `discardActiveSession`.
        is ActiveSession.MissingPlan, ActiveSession.None, null -> null
    }

    private fun Session.toRecent(exercises: List<app.gimcheck.core.Exercise>, settings: app.gimcheck.core.Settings): HoyRecentUi =
        HoyRecentUi(
            dateLabel = shortDateLabel(startedAt.date),
            volumeLabel = formatNumber(sessionVolume(this, exercises, settings)) + " kg",
            setCount = sets.count { !it.isWarmup },
        )

    private data class HoyData(
        val routines: List<Routine>,
        val exercises: List<app.gimcheck.core.Exercise>,
        val settings: AppSettings,
        val history: List<Session>,
        val bodyMetrics: List<BodyMetric>,
        val resumed: ActiveSession?,
    )

    companion object {
        /** Series/filas de "Últimos" que pide §6 fila 1. */
        const val RECENT_COUNT: Int = 3

        /** Punto de partida del stepper cuando no hay ningún peso cargado. */
        const val DEFAULT_WEIGHT_KG: Double = 80.0

        private val WEEKDAYS = listOf(
            "lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo",
        )
        private val MONTHS = listOf(
            "enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
        )
        private val MONTHS_SHORT = listOf(
            "ene", "feb", "mar", "abr", "may", "jun",
            "jul", "ago", "sep", "oct", "nov", "dic",
        )

        /** `miércoles 18 de noviembre`. Sin librerías nuevas: dos listas y `ordinal`. */
        fun dateLabel(date: LocalDate): String =
            "${WEEKDAYS[date.dayOfWeek.ordinal]} ${date.day} de ${MONTHS[date.month.number - 1]}"

        /** `18 nov`, para las filas de "Últimos". */
        fun shortDateLabel(date: LocalDate): String =
            "${date.day} ${MONTHS_SHORT[date.month.number - 1]}"

        fun greetingFor(hour: Int): String = when {
            hour < 12 -> "Buenos días"
            hour < 20 -> "Buenas tardes"
            else -> "Buenas noches"
        }

        /** Paso del contrato para peso corporal: 0,1 kg. */
        fun roundTenth(value: Double): Double = (value * 10.0).roundToInt() / 10.0
    }
}
