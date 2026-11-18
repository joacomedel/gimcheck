package app.gimcheck.core

import java.time.Duration
import java.time.Instant
import kotlinx.datetime.LocalDateTime

/**
 * Máquina de estados de la sesión guiada — `docs/especificacion.md` §4.
 *
 * Es **el contrato del producto**: superserie sin descanso dentro del bloque,
 * descanso al cerrar la ronda (o el bloque) y cursor que sobrevive al proceso
 * muerto. Por eso vive en `:core` (ADR 0006) y por eso es una función pura:
 *
 * ```
 * reduce(state, event) -> SessionTransition(state, effects)
 * ```
 *
 * Decisiones de esta capa, todas deliberadas:
 *
 *  - **Sin reloj adentro.** Ningún evento lee `Instant.now()`: el tiempo entra por
 *    parámetro (`StartSession.now`, `LogSet.now`, `Tick.now`, `Restore.now`). Un test
 *    puede recorrer una sesión entera sin esperar un segundo.
 *  - **Sin efectos laterales.** Lo que el llamador tiene que hacer (guardar, arrancar
 *    o cancelar el cronómetro, navegar) sale como [SessionEffect]; la máquina sólo
 *    devuelve una lista. El orden de la lista es estable y está documentado en §4.1.
 *  - **El plan vive en el estado.** `GuidedSession.day` es el día que se está
 *    ejecutando; `blocks` se deriva con [groups], así que las superseries son las
 *    mismas que usan `dayStats` y los gráficos. No hay una segunda noción de grupo.
 *  - **El estado es serializable tal cual.** `blockIndex`, `roundIndex`, `itemIndex`,
 *    `restEndsAt` (instante absoluto) y `sets` cubren todo lo que hace falta para
 *    reconstruir la sesión después de un reboot (GIM-11).
 *  - **Tipos de tiempo, dos y con motivo.** `restEndsAt` es [Instant] (punto absoluto:
 *    comparar `Tick`/`Restore` no depende de zona ni de DST, y Room lo guarda como
 *    epoch millis). [SessionSet.completedAt] sigue siendo [LocalDateTime] porque el
 *    resto de `:core` ya trabaja "por día calendario / semana ISO" en hora local
 *    (ver `Sessions.kt`); el llamador lo pasa en [LogSet.at]. No se convierte entre
 *    los dos acá: convertir exige una zona y la zona no es asunto del dominio.
 */

/** Estado de la sesión desde el punto de vista de la máquina. */
enum class SessionStatus {
    /** Creada a partir de un día, todavía sin [StartSession]. */
    NOT_STARTED,

    /** Anotando series. Es el único estado en el que se puede loguear sin cancelar nada. */
    RUNNING,

    /** Cuenta regresiva corriendo; el cursor ya apunta al próximo ítem. */
    RESTING,

    /** Último bloque terminado o [FinishSession]. Estado terminal. */
    FINISHED,

    /** [AbandonSession]. Estado terminal; no emite [SessionEffect.SessionFinished]. */
    ABANDONED,
}

/**
 * Una serie anotada, con la posición del plan a la que pertenece.
 *
 * [set] es el mismo [SessionSet] que consume el resto de `:core` (volumen, e1RM, PR):
 * no hay un modelo paralelo. [itemId] y [roundIndex] existen porque un día puede
 * repetir el mismo ejercicio y hace falta saber **qué** serie del plan se completó
 * (progreso de la pantalla y corrección de una serie).
 */
data class LoggedSet(
    val itemId: String,
    val blockIndex: Int,
    val roundIndex: Int,
    val set: SessionSet,
)

/**
 * Estado completo de la sesión guiada.
 *
 * @param day día que se está ejecutando: de acá salen los bloques ([groups]) y los
 *   descansos (con [DayItem.restOverrideSeconds] pisando el del ejercicio).
 * @param status ver [SessionStatus].
 * @param restEndsAt instante absoluto de fin del descanso; `null` si no hay descanso.
 * @param restSeconds duración vigente del descanso (la que muestran `+15 s`/`-15 s`);
 *   se mantiene aunque `restEndsAt` ya esté fijado.
 * @param sets series anotadas en orden de ejecución (calentamientos incluidos).
 */
data class GuidedSession(
    val day: RoutineDay,
    val sessionId: String = "",
    val status: SessionStatus = SessionStatus.NOT_STARTED,
    val startedAt: Instant? = null,
    val blockIndex: Int = 0,
    val roundIndex: Int = 0,
    val itemIndex: Int = 0,
    val restEndsAt: Instant? = null,
    val restSeconds: Int? = null,
    val sets: List<LoggedSet> = emptyList(),
) {
    /** Bloques del día: ítems consecutivos unidos por `supersetNext` (misma regla que `dayStats`). */
    val blocks: List<List<DayItem>> get() = groups(day.items)

    /** Bloque actual. */
    val currentBlock: List<DayItem> get() = blocks.getOrElse(blockIndex) { emptyList() }

    /** Ítem al que apunta el cursor: el que hay que anotar ahora. */
    val currentItem: DayItem? get() = currentBlock.getOrNull(itemIndex)

    /** Rondas del bloque actual (la del ítem más largo; 0 sin bloque). */
    val rounds: Int get() = currentBlock.maxOfOrNull { it.sets } ?: 0

    /** Series anotadas del ítem pedido, en orden. */
    fun setsFor(itemId: String): List<SessionSet> =
        sets.filter { it.itemId == itemId }.map { it.set }

    /** Series anotadas listas para volumen/e1RM/PR. */
    val sessionSets: List<SessionSet> get() = sets.map { it.set }

    /** `true` mientras la sesión admite eventos de ejecución. */
    val isActive: Boolean get() = status == SessionStatus.RUNNING || status == SessionStatus.RESTING
}

/** Eventos que mueven la máquina. */
sealed interface SessionEvent

/**
 * Arranca la sesión en el primer ítem del primer bloque.
 *
 * @param now reloj del llamador; se guarda en [GuidedSession.startedAt].
 */
data class StartSession(val sessionId: String, val now: Instant) : SessionEvent

/**
 * Anota una serie del ítem al que apunta el cursor.
 *
 * @param at hora local de la serie (va a [SessionSet.completedAt]).
 * @param now reloj del llamador; sólo se usa para el deadline del descanso
 *   (calentamiento). No se guarda como marca de la serie.
 */
data class LogSet(
    val weightKg: Double,
    val reps: Int,
    val isWarmup: Boolean = false,
    val at: LocalDateTime,
    val now: Instant,
) : SessionEvent

/** "Empezar ya": corta el descanso y deja el cursor donde está. */
data object SkipRest : SessionEvent

/** Suma (o resta, con delta negativo) segundos al descanso vigente. */
data class ExtendRest(val deltaSecs: Int) : SessionEvent

/** Pulso del cronómetro: cierra el descanso si [now] llegó a `restEndsAt`. */
data class Tick(val now: Instant) : SessionEvent

/**
 * Reconstrucción al abrir la app. Si el descanso venció mientras el proceso estaba
 * muerto, entra directo a "descanso terminado" y **no** mueve el cursor.
 */
data class Restore(val now: Instant) : SessionEvent

/** Termina la sesión a pedido del usuario (aunque falten series). */
data object FinishSession : SessionEvent

/** Descarta la sesión; no cuenta como entrenamiento terminado. */
data object AbandonSession : SessionEvent

/** Cosas que el llamador tiene que hacer. La máquina no las hace. */
sealed interface SessionEffect {
    /** Guardar [GuidedSession] (incluido `restEndsAt`) para sobrevivir al proceso muerto. */
    data object PersistState : SessionEffect

    /** (Re)programar el cronómetro con [seconds]; también se usa al reanudar con [Restore]. */
    data class StartRest(val seconds: Int) : SessionEffect

    /** Detener el cronómetro y la alarma de respaldo. */
    data object CancelRest : SessionEffect

    /** El descanso llegó a cero (por [Tick]): alerta según `alertMode` (§5). */
    data object RestFinished : SessionEffect

    /** La sesión terminó por el último bloque: persistir el cierre y salir. */
    data object SessionFinished : SessionEffect

    /** El cursor pasó a otro ítem del mismo bloque: navegar sin descanso. */
    data object ShowNextInSuperset : SessionEffect

    /** Cambió la ronda o el bloque: navegar al primer ítem del nuevo cursor. */
    data object ShowNextExercise : SessionEffect

    /** Al abrir con un descanso vencido: mostrar el aviso de "descanso terminado". */
    data object ShowRestDone : SessionEffect
}

/** Resultado de [reduce]: estado nuevo y efectos a ejecutar, en orden. */
data class SessionTransition(
    val state: GuidedSession,
    val effects: List<SessionEffect> = emptyList(),
) {
    /** `true` si hubo cambio de estado (los eventos ignorados devuelven el mismo estado). */
    val changed: Boolean get() = effects.isNotEmpty()
}

/**
 * Aplica un evento. Nunca lanza por un evento fuera de estado: devuelve el mismo
 * estado y sin efectos, así el llamador puede reenviar eventos sin mirar antes
 * (por ejemplo `Tick` desde el servicio de cronómetro).
 */
fun reduce(state: GuidedSession, event: SessionEvent): SessionTransition = when (event) {
    is StartSession -> startSession(state, event)
    is LogSet -> logSet(state, event)
    SkipRest -> skipRest(state)
    is ExtendRest -> extendRest(state, event)
    is Tick -> tick(state, event)
    is Restore -> restore(state, event)
    FinishSession -> finishSession(state)
    AbandonSession -> abandonSession(state)
}

/**
 * Descanso de un calentamiento: la mitad del descanso del ejercicio, redondeado a
 * 15 s (§4). `120 → 60`, `90 → 45`, `100 → 45`, `30 → 15`. Con `rest = 0` no hay
 * descanso. El redondeo es `floor(x + 0,5)` (el mismo `Math.round` de la web).
 */
fun warmupRestSeconds(restSeconds: Int): Int {
    if (restSeconds <= 0) return 0
    val steps = Math.round(restSeconds / 2.0 / 15.0).toInt()
    return maxOf(0, steps * 15)
}

// --- transiciones -----------------------------------------------------------

private fun startSession(state: GuidedSession, event: StartSession): SessionTransition {
    if (state.status != SessionStatus.NOT_STARTED) return SessionTransition(state)

    val started = state.copy(sessionId = event.sessionId, startedAt = event.now)
    val blocks = started.blocks
    if (blocks.isEmpty()) {
        // Día sin ítems: no hay nada que ejecutar, pero la sesión existe.
        return SessionTransition(
            started.copy(status = SessionStatus.FINISHED),
            listOf(SessionEffect.PersistState, SessionEffect.SessionFinished),
        )
    }
    return SessionTransition(
        started.copy(
            status = SessionStatus.RUNNING,
            blockIndex = 0,
            roundIndex = 0,
            itemIndex = firstParticipant(blocks[0], 0).coerceAtLeast(0),
        ),
        listOf(SessionEffect.PersistState),
    )
}

private fun logSet(state: GuidedSession, event: LogSet): SessionTransition {
    if (!state.isActive) return SessionTransition(state)

    val blocks = state.blocks
    val block = blocks.getOrElse(state.blockIndex) { return SessionTransition(state) }
    val item = block.getOrNull(state.itemIndex) ?: return SessionTransition(state)

    // Anotar durante el descanso es la forma normal de "empezar ya".
    val restingBefore = state.status == SessionStatus.RESTING
    val effects = mutableListOf<SessionEffect>(SessionEffect.PersistState)
    fun cancelRest() {
        if (restingBefore) effects += SessionEffect.CancelRest
    }

    val working = state.copy(status = SessionStatus.RUNNING, restEndsAt = null, restSeconds = null)
    val set = SessionSet(
        id = setKey(working, item),
        exerciseId = item.exerciseId,
        weightKg = event.weightKg,
        reps = event.reps,
        completedAt = event.at,
        isWarmup = event.isWarmup,
    )
    val logged = working.copy(
        sets = working.sets + LoggedSet(
            itemId = item.id,
            blockIndex = working.blockIndex,
            roundIndex = working.roundIndex,
            set = set,
        ),
    )

    if (event.isWarmup) {
        // El calentamiento no mueve el cursor: se repite la serie de trabajo después.
        val seconds = warmupRestSeconds(restSecondsOf(item))
        if (seconds <= 0) cancelRest()
        return restTransition(logged, seconds, event.now, effects)
    }

    val round = logged.roundIndex
    val nextItem = (logged.itemIndex + 1 until block.size).firstOrNull { block[it].sets > round }
    if (nextItem != null) {
        cancelRest()
        effects += SessionEffect.ShowNextInSuperset
        return SessionTransition(logged.copy(itemIndex = nextItem), effects)
    }

    // Se cerró la ronda: el descanso es el máximo entre los que participaron en ella.
    // Es la misma regla con la que `dayStats` cuenta el descanso de la ronda.
    val restSeconds = roundRestSeconds(block, round)
    val rounds = block.maxOf { it.sets }
    val nextRound = round + 1
    val nextBlockIndex = logged.blockIndex + 1

    if (nextRound >= rounds && nextBlockIndex >= blocks.size) {
        // Última serie del último bloque: no hay descanso, se termina.
        cancelRest()
        effects += SessionEffect.SessionFinished
        return SessionTransition(logged.copy(status = SessionStatus.FINISHED), effects)
    }

    effects += SessionEffect.ShowNextExercise
    if (restSeconds <= 0) cancelRest()

    if (nextRound < rounds) {
        return restTransition(
            logged.copy(roundIndex = nextRound, itemIndex = firstParticipant(block, nextRound).coerceAtLeast(0)),
            restSeconds,
            event.now,
            effects,
        )
    }

    val nextBlock = blocks[nextBlockIndex]
    return restTransition(
        logged.copy(
            blockIndex = nextBlockIndex,
            roundIndex = 0,
            itemIndex = firstParticipant(nextBlock, 0).coerceAtLeast(0),
        ),
        restSeconds,
        event.now,
        effects,
    )
}

private fun skipRest(state: GuidedSession): SessionTransition {
    if (state.status != SessionStatus.RESTING) return SessionTransition(state)
    return SessionTransition(
        state.copy(status = SessionStatus.RUNNING, restEndsAt = null, restSeconds = null),
        listOf(SessionEffect.PersistState, SessionEffect.CancelRest),
    )
}

private fun extendRest(state: GuidedSession, event: ExtendRest): SessionTransition {
    if (state.status != SessionStatus.RESTING || event.deltaSecs == 0) return SessionTransition(state)
    val end = state.restEndsAt ?: return SessionTransition(state)
    val seconds = (state.restSeconds ?: 0) + event.deltaSecs
    if (seconds <= 0) {
        return SessionTransition(
            state.copy(status = SessionStatus.RUNNING, restEndsAt = null, restSeconds = null),
            listOf(SessionEffect.PersistState, SessionEffect.CancelRest),
        )
    }
    return SessionTransition(
        state.copy(restSeconds = seconds, restEndsAt = end.plusSeconds(event.deltaSecs.toLong())),
        listOf(SessionEffect.PersistState, SessionEffect.StartRest(seconds)),
    )
}

private fun tick(state: GuidedSession, event: Tick): SessionTransition {
    if (state.status != SessionStatus.RESTING) return SessionTransition(state)
    val end = state.restEndsAt ?: return SessionTransition(state)
    if (event.now.isBefore(end)) return SessionTransition(state)
    return SessionTransition(
        state.copy(status = SessionStatus.RUNNING, restEndsAt = null, restSeconds = null),
        listOf(SessionEffect.PersistState, SessionEffect.RestFinished),
    )
}

private fun restore(state: GuidedSession, event: Restore): SessionTransition {
    if (state.status != SessionStatus.RESTING) return SessionTransition(state)
    val end = state.restEndsAt ?: return SessionTransition(state)
    if (event.now.isBefore(end)) {
        // Sigue corriendo: se reanuda el cronómetro con lo que falta, sin tocar el cursor.
        val millis = Duration.between(event.now, end).toMillis()
        val remaining = ((millis + 999) / 1000).toInt().coerceAtLeast(1)
        return SessionTransition(state, listOf(SessionEffect.StartRest(remaining)))
    }
    return SessionTransition(
        state.copy(status = SessionStatus.RUNNING, restEndsAt = null, restSeconds = null),
        listOf(SessionEffect.PersistState, SessionEffect.RestFinished, SessionEffect.ShowRestDone),
    )
}

private fun finishSession(state: GuidedSession): SessionTransition {
    if (!state.isActive) return SessionTransition(state)
    val effects = mutableListOf<SessionEffect>(SessionEffect.PersistState)
    if (state.status == SessionStatus.RESTING) effects += SessionEffect.CancelRest
    effects += SessionEffect.SessionFinished
    return SessionTransition(
        state.copy(status = SessionStatus.FINISHED, restEndsAt = null, restSeconds = null),
        effects,
    )
}

private fun abandonSession(state: GuidedSession): SessionTransition {
    if (!state.isActive) return SessionTransition(state)
    val effects = mutableListOf<SessionEffect>(SessionEffect.PersistState)
    if (state.status == SessionStatus.RESTING) effects += SessionEffect.CancelRest
    return SessionTransition(
        state.copy(status = SessionStatus.ABANDONED, restEndsAt = null, restSeconds = null),
        effects,
    )
}

// --- auxiliares -------------------------------------------------------------

/** Abre (o no) un descanso después de los efectos ya acumulados. */
private fun restTransition(
    state: GuidedSession,
    seconds: Int,
    now: Instant,
    effects: List<SessionEffect>,
): SessionTransition =
    if (seconds <= 0) {
        SessionTransition(state.copy(status = SessionStatus.RUNNING, restEndsAt = null, restSeconds = null), effects)
    } else {
        SessionTransition(
            state.copy(
                status = SessionStatus.RESTING,
                restSeconds = seconds,
                restEndsAt = now.plusSeconds(seconds.toLong()),
            ),
            effects + SessionEffect.StartRest(seconds),
        )
    }

/** Índice del primer ítem del bloque que participa en la ronda, o -1. */
private fun firstParticipant(block: List<DayItem>, round: Int): Int =
    block.indexOfFirst { it.sets > round }

/** Descanso al cerrar una ronda: máximo entre los ítems que participaron en ella. */
private fun roundRestSeconds(block: List<DayItem>, round: Int): Int =
    block.filter { it.sets > round }.maxOfOrNull { restSecondsOf(it) } ?: 0

/**
 * Id de la serie: determinístico (`sesión/ítem/número`), así un replay del mismo
 * evento no inventa ids nuevos y la corrección posterior puede ubicar la serie.
 */
private fun setKey(state: GuidedSession, item: DayItem): String =
    "${state.sessionId}/${item.id}/${state.sets.count { it.itemId == item.id } + 1}"
