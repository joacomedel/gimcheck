package app.gimcheck.core

import app.gimcheck.core.SessionEffect.CancelRest
import app.gimcheck.core.SessionEffect.PersistState
import app.gimcheck.core.SessionEffect.RestFinished
import app.gimcheck.core.SessionEffect.SessionFinished
import app.gimcheck.core.SessionEffect.ShowNextExercise
import app.gimcheck.core.SessionEffect.ShowNextInSuperset
import app.gimcheck.core.SessionEffect.ShowRestDone
import app.gimcheck.core.SessionEffect.StartRest
import java.time.Instant
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Máquina de estados de la sesión guiada — `docs/especificacion.md` §4 y §4.1.
 *
 * Los números están calculados a mano desde la especificación (no desde la
 * implementación): descanso = máximo entre los que participaron en la ronda, cero
 * descanso dentro del bloque, sin descanso al terminar el último bloque.
 */
class SessionMachineTest {

    private val t0: Instant = Instant.parse("2026-11-18T10:00:00Z")
    private val at: LocalDateTime = LocalDateTime(2026, 11, 18, 7, 0)

    // --- fixtures -----------------------------------------------------------

    private fun dayItem(
        id: String,
        sets: Int,
        exerciseRest: Int = 120,
        restOverride: Int? = null,
        supersetNext: Boolean = false,
        work: Int = 45,
    ) = DayItem(
        id = id,
        exercise = Exercise(
            id = id,
            name = id,
            primaryMuscleId = "Pecho",
            secondsPerSet = work,
            restSeconds = exerciseRest,
        ),
        sets = sets,
        supersetNext = supersetNext,
        restOverrideSeconds = restOverride,
    )

    private fun session(vararg items: DayItem) =
        GuidedSession(day = RoutineDay(id = "d1", name = "Día 1", items = items.toList()))

    private fun GuidedSession.start(id: String = "s1") = reduce(this, StartSession(id, t0))

    private fun GuidedSession.log(
        weightKg: Double = 50.0,
        reps: Int = 8,
        now: Instant = t0,
    ) = reduce(this, LogSet(weightKg = weightKg, reps = reps, at = at, now = now))

    private fun GuidedSession.warmup(
        weightKg: Double = 20.0,
        reps: Int = 10,
        now: Instant = t0,
    ) = reduce(this, LogSet(weightKg = weightKg, reps = reps, isWarmup = true, at = at, now = now))

    private fun GuidedSession.tick(now: Instant) = reduce(this, Tick(now))

    private fun assertCursor(t: SessionTransition, block: Int, round: Int, item: Int) {
        assertEquals("blockIndex", block, t.state.blockIndex)
        assertEquals("roundIndex", round, t.state.roundIndex)
        assertEquals("itemIndex", item, t.state.itemIndex)
    }

    private fun assertCursor(state: GuidedSession, block: Int, round: Int, item: Int) =
        assertCursor(SessionTransition(state), block, round, item)

    private fun assertRest(t: SessionTransition, seconds: Int) {
        assertEquals(SessionStatus.RESTING, t.state.status)
        assertEquals(seconds, t.state.restSeconds)
        assertEquals(t0.plusSeconds(seconds.toLong()), t.state.restEndsAt)
    }

    // --- 1. superserie de 2 con 3 rondas ------------------------------------

    @Test
    fun `superserie de 2 con 3 rondas alterna sin descanso y descansa al cerrar la ronda`() {
        val items = arrayOf(
            dayItem("a", sets = 3, exerciseRest = 120, supersetNext = true),
            dayItem("b", sets = 3, exerciseRest = 75),
        )

        val start = session(*items).start()
        assertEquals(SessionStatus.RUNNING, start.state.status)
        assertEquals(t0, start.state.startedAt)
        assertEquals("s1", start.state.sessionId)
        assertEquals(3, start.state.rounds)
        assertCursor(start, 0, 0, 0)
        assertEquals(listOf<SessionEffect>(PersistState), start.effects)

        // Ronda 0: dentro del bloque no hay descanso, sólo se mueve el cursor.
        val set1 = start.state.log()
        assertCursor(set1, 0, 0, 1)
        assertEquals(listOf<SessionEffect>(PersistState, ShowNextInSuperset), set1.effects)
        assertNull(set1.state.restEndsAt)
        assertNull(set1.state.restSeconds)
        assertEquals(SessionStatus.RUNNING, set1.state.status)

        // Último de la ronda: cierra la ronda y arranca el descanso del máximo (120, 75).
        val set2 = set1.state.log()
        assertCursor(set2, 0, 1, 0)
        assertRest(set2, 120)
        assertEquals(listOf<SessionEffect>(PersistState, ShowNextExercise, StartRest(120)), set2.effects)
        assertEquals(2, set2.state.sets.size)

        // El cronómetro cierra la ronda cuando llega la hora; el cursor no se mueve.
        val beforeEnd = set2.state.tick(t0.plusSeconds(119))
        assertEquals(listOf<SessionEffect>(), beforeEnd.effects)
        assertEquals(set2.state, beforeEnd.state)

        val restDone = set2.state.tick(t0.plusSeconds(120))
        assertEquals(listOf<SessionEffect>(PersistState, RestFinished), restDone.effects)
        assertEquals(SessionStatus.RUNNING, restDone.state.status)
        assertNull(restDone.state.restEndsAt)
        assertCursor(restDone, 0, 1, 0)

        // Ronda 1 y ronda 2 completas.
        val r1a = restDone.state.log()
        assertCursor(r1a, 0, 1, 1)
        val r1b = r1a.state.log()
        assertCursor(r1b, 0, 2, 0)
        assertRest(r1b, 120)
        val r2a = r1b.state.log()
        assertCursor(r2a, 0, 2, 1)
        val last = r2a.state.log()

        // Última serie del último bloque: no hay descanso, se termina.
        assertEquals(SessionStatus.FINISHED, last.state.status)
        assertNull(last.state.restEndsAt)
        assertEquals(listOf<SessionEffect>(PersistState, SessionFinished), last.effects)
        assertEquals(6, last.state.sets.size)
        assertEquals(listOf("a", "b", "a", "b", "a", "b"), last.state.sessionSets.map { it.exerciseId })
    }

    // --- 2. superserie con series desiguales --------------------------------

    @Test
    fun `superserie 3 mas 2 - el item corto no participa en la ronda extra`() {
        val a = dayItem("a", sets = 3, exerciseRest = 120, supersetNext = true)
        val b = dayItem("b", sets = 2, exerciseRest = 75)

        val r0a = session(a, b).start().state.log()
        assertCursor(r0a, 0, 0, 1)
        val r0b = r0a.state.log()
        assertCursor(r0b, 0, 1, 0)
        assertRest(r0b, 120) // max(120, 75) entre los dos participantes

        val r1a = r0b.state.log()
        val r1b = r1a.state.log()
        assertCursor(r1b, 0, 2, 0)
        assertRest(r1b, 120) // max(120, 75) otra vez: los dos participaron

        // Ronda 3: sólo "a" participa. El cursor no vuelve a "b" y la ronda cierra con "a".
        // Se anota durante el descanso (el usuario arrancó la serie sin esperar): primero
        // cancela el cronómetro y después cierra la sesión.
        val r2a = r1b.state.log()
        assertCursor(r2a, 0, 2, 0)
        assertEquals(SessionStatus.FINISHED, r2a.state.status)
        assertEquals(listOf<SessionEffect>(PersistState, CancelRest, SessionFinished), r2a.effects)
        assertEquals(5, r2a.state.sets.size) // 3 + 2, no 6
        assertEquals(2, r2a.state.setsFor("b").size)
        assertEquals(3, r2a.state.setsFor("a").size)
    }

    @Test
    fun `ronda extra con un solo participante descansa con el rest de ese item`() {
        // A descansa 90 y B 60; en la tercera ronda sólo participa A → 90, no 60.
        val a = dayItem("a", sets = 3, exerciseRest = 90, supersetNext = true)
        val b = dayItem("b", sets = 2, exerciseRest = 60)

        var state = session(a, b).start().state
        state = state.log().state // a r0
        state = state.log().state // b r0 → ronda 1
        state = state.log().state // a r1
        state = state.log().state // b r1 → ronda 2, descanso max(90,60)

        assertEquals(2, state.roundIndex)
        assertEquals(90, state.restSeconds)
        assertEquals(0, state.itemIndex)
        assertEquals(listOf(3, 2), state.blocks.single().map { it.sets })
    }

    // --- 3. dos bloques seguidos --------------------------------------------

    @Test
    fun `dos bloques - descanso entre bloques y cursor al primero del siguiente`() {
        val a = dayItem("a", sets = 2, exerciseRest = 90, supersetNext = true)
        val b = dayItem("b", sets = 2, exerciseRest = 60)
        val c = dayItem("c", sets = 2, exerciseRest = 150)

        var state = session(a, b, c).start().state
        assertEquals(2, state.blocks.size)

        state = state.log().state // a r0
        val afterRound0 = state.log() // b r0
        assertEquals(listOf<SessionEffect>(PersistState, ShowNextExercise, StartRest(90)), afterRound0.effects)
        assertCursor(afterRound0, 0, 1, 0)
        state = afterRound0.state

        state = state.log().state // a r1
        val blockClose = state.log() // b r1 → bloque cerrado
        assertEquals(listOf<SessionEffect>(PersistState, ShowNextExercise, StartRest(90)), blockClose.effects)
        assertRest(blockClose, 90)
        assertCursor(blockClose, 1, 0, 0)
        assertEquals(4, blockClose.state.sets.size)

        // El bloque suelto descansa 150 entre sus dos series y termina con la segunda.
        val single = blockClose.state.log()
        assertCursor(single, 1, 1, 0)
        assertRest(single, 150)
        val last = single.state.log()
        assertEquals(SessionStatus.FINISHED, last.state.status)
        assertEquals(listOf<SessionEffect>(PersistState, CancelRest, SessionFinished), last.effects)
        assertEquals(6, last.state.sets.size)
    }

    @Test
    fun `ultima serie del ultimo bloque no arranca descanso`() {
        val start = session(dayItem("a", sets = 1)).start()
        val last = start.state.log()

        assertEquals(SessionStatus.FINISHED, last.state.status)
        assertEquals(listOf<SessionEffect>(PersistState, SessionFinished), last.effects)
        assertNull(last.state.restEndsAt)
        assertEquals(1, last.state.sets.size)
    }

    // --- 4. Restore ---------------------------------------------------------

    @Test
    fun `restore con descanso vencido emite RestFinished y ShowRestDone sin perder el cursor`() {
        val a = dayItem("a", sets = 3, supersetNext = true)
        val b = dayItem("b", sets = 3)
        // Cursor en el ítem 1 dentro de la superserie y descanso abierto por un calentamiento:
        // es el único caso en el que se descansa con itemIndex > 0.
        var state = session(a, b).start().state
        state = state.log().state // a r0 → cursor en b (item 1)
        val warm = state.warmup()
        assertCursor(warm, 0, 0, 1)
        assertRest(warm, 60)
        state = warm.state

        val restored = reduce(state, Restore(t0.plusSeconds(600)))
        assertEquals(SessionStatus.RUNNING, restored.state.status)
        assertNull(restored.state.restEndsAt)
        assertNull(restored.state.restSeconds)
        assertCursor(restored, 0, 0, 1)
        assertEquals(listOf<SessionEffect>(PersistState, RestFinished, ShowRestDone), restored.effects)
        assertEquals(2, restored.state.sets.size) // no se pierde nada
    }

    @Test
    fun `restore con descanso vigente reprograma el cronometro sin persistir`() {
        val start = session(dayItem("a", sets = 2), dayItem("c", sets = 1)).start()
        val rest = start.state.log() // a1 cierra ronda → 120 s
        assertRest(rest, 120)

        val resumed = reduce(rest.state, Restore(t0.plusSeconds(30)))
        assertEquals(rest.state, resumed.state)
        assertEquals(listOf<SessionEffect>(StartRest(90)), resumed.effects)

        // Redondea hacia arriba al segundo: nunca reanuda con 0 s.
        val partial = reduce(rest.state, Restore(t0.plusSeconds(119)))
        assertEquals(listOf<SessionEffect>(StartRest(1)), partial.effects)
        val almost = reduce(rest.state, Restore(t0.plusSeconds(0).plusMillis(29_500)))
        assertEquals(listOf<SessionEffect>(StartRest(91)), almost.effects)
    }

    @Test
    fun `restore sin descanso o en estado terminal no hace nada`() {
        val notStarted = session(dayItem("a", sets = 1))
        assertEquals(SessionTransition(notStarted), reduce(notStarted, Restore(t0)))

        val running = notStarted.start().state
        assertEquals(SessionTransition(running), reduce(running, Restore(t0.plusSeconds(999))))

        val finished = session(dayItem("a", sets = 1)).start().state.log().state
        assertEquals(SessionStatus.FINISHED, finished.status)
        assertEquals(SessionTransition(finished), reduce(finished, Restore(t0.plusSeconds(999))))
        assertEquals(1, finished.sets.size)
    }

    // --- 5. SkipRest / ExtendRest ------------------------------------------

    @Test
    fun `skip rest cancela el cronometro y deja el cursor donde esta`() {
        val start = session(dayItem("a", sets = 2), dayItem("c", sets = 1)).start()
        val rest = start.state.log()
        assertRest(rest, 120)

        val skipped = reduce(rest.state, SkipRest)
        assertEquals(SessionStatus.RUNNING, skipped.state.status)
        assertNull(skipped.state.restEndsAt)
        assertNull(skipped.state.restSeconds)
        assertCursor(skipped, 0, 1, 0)
        assertEquals(listOf<SessionEffect>(PersistState, CancelRest), skipped.effects)

        // Fuera de descanso es un no-op: "empezar ya" dos veces no rompe nada.
        assertEquals(SessionTransition(skipped.state), reduce(skipped.state, SkipRest))
    }

    @Test
    fun `extend rest suma y resta segundos sobre el deadline`() {
        val start = session(dayItem("a", sets = 2), dayItem("c", sets = 1)).start()
        val rest = start.state.log() // 120 s, deadline t0+120

        val plus = reduce(rest.state, ExtendRest(15))
        assertEquals(135, plus.state.restSeconds)
        assertEquals(t0.plusSeconds(135), plus.state.restEndsAt)
        assertEquals(listOf<SessionEffect>(PersistState, StartRest(135)), plus.effects)

        val minus = reduce(plus.state, ExtendRest(-15))
        assertEquals(120, minus.state.restSeconds)
        assertEquals(t0.plusSeconds(120), minus.state.restEndsAt)
        assertEquals(listOf<SessionEffect>(PersistState, StartRest(120)), minus.effects)

        // Bajar por debajo de cero equivale a "empezar ya".
        val zero = reduce(minus.state, ExtendRest(-120))
        assertEquals(SessionStatus.RUNNING, zero.state.status)
        assertNull(zero.state.restEndsAt)
        assertEquals(listOf<SessionEffect>(PersistState, CancelRest), zero.effects)

        assertEquals(SessionTransition(minus.state), reduce(minus.state, ExtendRest(0)))
        val notResting = minus.state.copy(status = SessionStatus.RUNNING)
        assertEquals(SessionTransition(notResting), reduce(notResting, ExtendRest(15)))
    }

    // --- 6. sesión de un solo ejercicio ------------------------------------

    @Test
    fun `sesion de un solo ejercicio descansa entre cada serie`() {
        val a = dayItem("a", sets = 3, exerciseRest = 120)
        val start = session(a).start()
        assertEquals(1, start.state.blocks.size)

        val s1 = start.state.log()
        assertCursor(s1, 0, 1, 0)
        assertRest(s1, 120)
        assertEquals(listOf<SessionEffect>(PersistState, ShowNextExercise, StartRest(120)), s1.effects)

        val s2 = s1.state.log()
        assertRest(s2, 120)
        val s3 = s2.state.log()
        assertEquals(SessionStatus.FINISHED, s3.state.status)
        assertEquals(3, s3.state.sets.size)
    }

    @Test
    fun `rest override del item manda sobre el del ejercicio`() {
        val solo = session(dayItem("a", sets = 2, exerciseRest = 120, restOverride = 45))
        val afterFirst = solo.start().state.log()
        assertRest(afterFirst, 45)
        assertEquals(45, afterFirst.state.restSeconds)
    }

    @Test
    fun `en una superserie el override es por item y el maximo sigue ganando`() {
        // A(120, override 30) + B(60) → 60: el override de A no se come el de B.
        val a = dayItem("a", sets = 2, exerciseRest = 120, restOverride = 30, supersetNext = true)
        val b = dayItem("b", sets = 2, exerciseRest = 60)
        var state = session(a, b).start().state
        state = state.log().state // a r0
        val closed = state.log() // b r0
        assertEquals(60, closed.state.restSeconds)

        // B con override 45 deja el máximo en 120 (el de A).
        val a2 = dayItem("a", sets = 2, exerciseRest = 120, supersetNext = true)
        val b2 = dayItem("b", sets = 2, exerciseRest = 60, restOverride = 45)
        var state2 = session(a2, b2).start().state
        state2 = state2.log().state
        assertEquals(120, state2.log().state.restSeconds)
    }

    // --- 7. calentamiento ---------------------------------------------------

    @Test
    fun `calentamiento no mueve el cursor y descansa la mitad redondeado a 15 s`() {
        val a = dayItem("a", sets = 3, exerciseRest = 120, supersetNext = true)
        val b = dayItem("b", sets = 3, exerciseRest = 90)

        val warm = session(a, b).start().state.warmup(weightKg = 20.0, reps = 10)
        assertCursor(warm, 0, 0, 0) // sigue en el primer ítem
        assertRest(warm, 60) // 120 / 2
        assertEquals(listOf<SessionEffect>(PersistState, StartRest(60)), warm.effects)

        val set = warm.state.sets.single().set
        assertTrue(set.isWarmup)
        assertEquals("a", set.exerciseId)
        assertEquals(20.0, set.weightKg, 1e-9)
        assertEquals(at, set.completedAt)

        // Con el cursor en el segundo ítem, el calentamiento usa el rest de ese ítem.
        val second = warm.state.log().state // trabajo en a → cursor en b
        val warmB = second.warmup()
        assertCursor(warmB, 0, 0, 1)
        assertRest(warmB, 45) // 90 / 2
    }

    @Test
    fun `warmup sin descanso declarado no abre cronometro`() {
        val warm = session(dayItem("a", sets = 1, exerciseRest = 0)).start().state.warmup()
        assertEquals(SessionStatus.RUNNING, warm.state.status)
        assertNull(warm.state.restEndsAt)
        assertEquals(listOf<SessionEffect>(PersistState), warm.effects)
    }

    @Test
    fun `formula del descanso de calentamiento`() {
        assertEquals(0, warmupRestSeconds(0))
        assertEquals(0, warmupRestSeconds(10))
        assertEquals(15, warmupRestSeconds(20))
        assertEquals(15, warmupRestSeconds(30))
        assertEquals(45, warmupRestSeconds(90))
        assertEquals(45, warmupRestSeconds(100))
        assertEquals(60, warmupRestSeconds(120))
        assertEquals(75, warmupRestSeconds(150))
    }

    @Test
    fun `anotar durante el descanso equivale a empezar ya`() {
        val a = dayItem("a", sets = 2, supersetNext = true)
        val b = dayItem("b", sets = 2)
        val rest = session(a, b).start().state.log().state.log() // cierra ronda 0 → 120 s
        assertRest(rest, 120)

        val early = rest.state.log(weightKg = 55.0)
        assertEquals(SessionStatus.RUNNING, early.state.status)
        assertNull(early.state.restEndsAt)
        assertCursor(early, 0, 1, 1)
        assertEquals(listOf<SessionEffect>(PersistState, CancelRest, ShowNextInSuperset), early.effects)
        assertEquals(55.0, early.state.sets.last().set.weightKg, 1e-9)
    }

    // --- 8. fin y abandono --------------------------------------------------

    @Test
    fun `finish session a mitad de bloque termina sin descanso`() {
        val day = session(dayItem("a", sets = 3, supersetNext = true), dayItem("b", sets = 3))
        val afterOne = day.start().state.log().state // cursor en "b", sin descanso todavía
        assertCursor(afterOne, 0, 0, 1)
        assertEquals(SessionStatus.RUNNING, afterOne.status)

        val finished = reduce(afterOne, FinishSession)
        assertEquals(SessionStatus.FINISHED, finished.state.status)
        assertNull(finished.state.restEndsAt)
        assertEquals(listOf<SessionEffect>(PersistState, SessionFinished), finished.effects)
        assertEquals(1, finished.state.sets.size) // las series anotadas quedan

        // Desde FINISHED nada vuelve a moverse.
        assertEquals(SessionTransition(finished.state), reduce(finished.state, FinishSession))
        assertEquals(SessionTransition(finished.state), reduce(finished.state, SkipRest))
    }

    @Test
    fun `finish session durante el descanso cancela el cronometro`() {
        val rest = session(dayItem("a", sets = 2)).start().state.log()
        assertRest(rest, 120)

        val finished = reduce(rest.state, FinishSession)
        assertEquals(SessionStatus.FINISHED, finished.state.status)
        assertEquals(listOf<SessionEffect>(PersistState, CancelRest, SessionFinished), finished.effects)
    }

    @Test
    fun `abandonar desde distintos puntos`() {
        val base = session(dayItem("a", sets = 3, supersetNext = true), dayItem("b", sets = 3))

        // Sin arrancar no hay nada que abandonar.
        assertEquals(SessionTransition(base), reduce(base, AbandonSession))

        // A mitad de bloque: la superserie ya pasó al segundo ítem, sin descanso.
        val running = base.start().state.log().state
        assertEquals(SessionStatus.RUNNING, running.status)
        val abandoned = reduce(running, AbandonSession)
        assertEquals(SessionStatus.ABANDONED, abandoned.state.status)
        assertEquals(listOf<SessionEffect>(PersistState), abandoned.effects)
        assertNull(abandoned.state.restEndsAt)
        assertEquals(1, abandoned.state.sets.size)

        // Durante el descanso: se cancela el cronómetro.
        val resting = running.log().state // b cierra la ronda
        assertEquals(SessionStatus.RESTING, resting.status)
        val abandonedRest = reduce(resting, AbandonSession)
        assertEquals(SessionStatus.ABANDONED, abandonedRest.state.status)
        assertEquals(listOf<SessionEffect>(PersistState, CancelRest), abandonedRest.effects)
        assertNull(abandonedRest.state.restEndsAt)

        // Abandonar dos veces (o una sesión ya terminada) es no-op.
        assertEquals(SessionTransition(abandonedRest.state), reduce(abandonedRest.state, AbandonSession))

        val finished = session(dayItem("a", sets = 1)).start().state.log().state
        assertEquals(SessionTransition(finished), reduce(finished, AbandonSession))
    }

    // --- 9. bordes y pureza -------------------------------------------------

    @Test
    fun `dia vacio termina la sesion al arrancar`() {
        val start = session().start()
        assertEquals(SessionStatus.FINISHED, start.state.status)
        assertEquals(listOf<SessionEffect>(PersistState, SessionFinished), start.effects)
        assertTrue(start.state.blocks.isEmpty())
        assertEquals(SessionStatus.NOT_STARTED, session().status)
    }

    @Test
    fun `eventos fuera de estado no rompen la maquina`() {
        val base = session(dayItem("a", sets = 2))
        val noop = SessionTransition(base)

        assertEquals(noop, reduce(base, Tick(t0)))
        assertEquals(noop, reduce(base, SkipRest))
        assertEquals(noop, reduce(base, ExtendRest(15)))
        assertEquals(noop, reduce(base, LogSet(50.0, 8, false, at, t0)))
        assertEquals(noop, reduce(base, FinishSession))
        val abandoned = base.copy(status = SessionStatus.ABANDONED)
        assertEquals(SessionTransition(abandoned), reduce(abandoned, Tick(t0)))

        // StartSession es idempotente: reenviarlo no reinicia el cursor.
        val started = base.start().state.log().state
        assertEquals(SessionTransition(started), reduce(started, StartSession("otra", t0)))
        assertEquals("s1", started.sessionId)
    }

    @Test
    fun `reduce es puro - mismo estado y evento dan el mismo resultado`() {
        val start = session(dayItem("a", sets = 3), dayItem("b", sets = 3)).start()
        val once = start.state.log()
        val twice = start.state.log()

        assertEquals(once, twice)
        assertEquals(0, start.state.sets.size) // el estado de entrada no se muta
        assertEquals(SessionStatus.RUNNING, start.state.status)
        assertNull(start.state.restEndsAt)
    }

    @Test
    fun `ids de series son deterministas y unicos`() {
        var state = session(dayItem("a", sets = 2, supersetNext = true), dayItem("b", sets = 2))
            .start().state
        repeat(4) { state = state.log().state }

        val ids = state.sets.map { it.set.id }
        assertEquals(4, ids.size)
        assertEquals(4, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("s1/") })
        assertEquals("s1/a/1", ids[0])
        assertEquals("s1/b/1", ids[1])
        assertEquals("s1/a/2", ids[2])
        assertEquals("s1/b/2", ids[3])

        // El round queda registrado en la serie anotada (para progreso y corrección).
        assertEquals(listOf(0, 0, 1, 1), state.sets.map { it.roundIndex })
        assertEquals(listOf("a", "b", "a", "b"), state.sets.map { it.itemId })
    }

    @Test
    fun `el estado persistido alcanza para retomar la sesion`() {
        // Simula el proceso muerto: se guardan los campos del §2 y se reconstruye.
        val full = session(dayItem("a", sets = 3, supersetNext = true), dayItem("b", sets = 3))
        var state = full.start().state
        repeat(2) { state = state.log().state } // ronda 0 completa → descanso de 120 s

        assertEquals(SessionStatus.RESTING, state.status)
        assertCursor(state, 0, 1, 0)

        val persisted = GuidedSession(
            day = state.day,
            sessionId = state.sessionId,
            status = state.status,
            startedAt = state.startedAt,
            blockIndex = state.blockIndex,
            roundIndex = state.roundIndex,
            itemIndex = state.itemIndex,
            restEndsAt = state.restEndsAt,
            restSeconds = state.restSeconds,
            sets = state.sets,
        )
        assertEquals(state, persisted)

        val resumed = reduce(persisted, Restore(t0.plusSeconds(200)))
        assertEquals(SessionStatus.RUNNING, resumed.state.status)
        assertCursor(resumed, 0, 1, 0)
        assertEquals(2, resumed.state.sets.size)

        // Y sigue funcionando después de retomar.
        val next = resumed.state.log()
        assertCursor(next, 0, 1, 1)
        assertEquals(3, next.state.sets.size)
    }

    @Test
    fun `rest de cero no abre cronometro y no pierde la ronda`() {
        val a = dayItem("a", sets = 2, exerciseRest = 0)
        val start = session(a).start()

        val first = start.state.log()
        assertEquals(SessionStatus.RUNNING, first.state.status)
        assertNull(first.state.restEndsAt)
        assertCursor(first, 0, 1, 0)
        assertEquals(listOf<SessionEffect>(PersistState, ShowNextExercise), first.effects)

        val second = first.state.log()
        assertEquals(SessionStatus.FINISHED, second.state.status)
        assertEquals(2, second.state.sets.size)
    }
}
