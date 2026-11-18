package app.gimcheck.data.db

import app.gimcheck.core.LoadType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reglas de integridad de `docs/especificacion.md` §2, ejercitadas sobre la base
 * real (en memoria, Robolectric).
 *
 * Cada test arma el plan con [seedPlan] y verifica una regla; nada de asserts
 * sobre código generado.
 */
@RunWith(RobolectricTestRunner::class)
class IntegrityRulesTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `borrar un ejercicio lo quita de los day_item y recalcula superset_next del ultimo item`() {
        runBlocking { db.seedPlan() }

        // e2 estrena un secundario: el CASCADE de exercise_secondary también se prueba.
        runBlocking {
            db.exerciseDao().upsertWithSecondaries(
                exercise("e2"),
                listOf(ExerciseSecondaryEntity(exerciseId = "e2", muscleId = "m2", factor = 0.5)),
            )
        }

        runBlocking { db.exerciseDao().deleteExerciseCascade("e2") }

        runBlocking {
            val afterE2 = db.dayDao().items(DAY_ID)
            assertEquals(listOf("i1", "i3"), afterE2.map { it.id })
            // i1 sigue encadenado con el ítem que ahora le sigue (i3)
            assertTrue(afterE2.first { it.id == "i1" }.supersetNext)
            // i3 sigue siendo el último y no puede encadenar
            assertFalse(afterE2.last().supersetNext)
            assertTrue(
                "los secundarios del ejercicio borrado deben desaparecer",
                db.exerciseDao().secondariesOf("e2").isEmpty(),
            )
        }

        // Al borrar e3, i1 queda último: su superset_next tiene que recalcularse a false.
        runBlocking { db.exerciseDao().deleteExerciseCascade("e3") }

        runBlocking {
            val afterE3 = db.dayDao().items(DAY_ID)
            assertEquals(listOf("i1"), afterE3.map { it.id })
            assertFalse("el último ítem no puede encadenar superserie", afterE3.single().supersetNext)
        }
    }

    @Test
    fun `superset_next no puede quedar en true en el ultimo item del dia`() {
        runBlocking { db.seedPlan() }

        val last = runBlocking { db.dayDao().items(DAY_ID).last() }
        assertEquals("i3", last.id)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { db.dayDao().setSupersetNext("i3", true) }
        }
        assertFalse(
            "el último ítem quedó con superset_next=true",
            runBlocking { db.dayDao().item("i3")!!.supersetNext },
        )

        // El primer ítem sí puede encadenar.
        runBlocking { db.dayDao().setSupersetNext("i1", false) }
        runBlocking { db.dayDao().setSupersetNext("i1", true) }
        assertTrue(runBlocking { db.dayDao().item("i1")!!.supersetNext })

        // Un alta al final del día nace sin superserie y no toca la marca anterior.
        runBlocking { db.dayDao().addItem(dayItem("i4", "e1", sets = 2)) }
        runBlocking {
            val items = db.dayDao().items(DAY_ID)
            assertEquals("i4", items.last().id)
            assertFalse(items.last().supersetNext)
            assertTrue(items.first { it.id == "i1" }.supersetNext)
        }
    }

    @Test
    fun `sets fuera de 1 a 20 se rechaza`() {
        runBlocking { db.seedPlan() }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { db.dayDao().addItem(dayItem("bad-0", "e1", sets = 0)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { db.dayDao().addItem(dayItem("bad-21", "e1", sets = 21)) }
        }

        // Los bordes válidos entran.
        val min = runBlocking { db.dayDao().addItem(dayItem("ok-1", "e1", sets = 1)) }
        val max = runBlocking { db.dayDao().addItem(dayItem("ok-20", "e1", sets = 20)) }
        assertEquals(1, min.sets)
        assertEquals(20, max.sets)

        // La edición valida lo mismo.
        val item = runBlocking { db.dayDao().item("ok-1")!! }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { db.dayDao().updateItem(item.copy(sets = 25)) }
        }
    }

    @Test
    fun `factor de un secundario fuera de 0 a 1 se rechaza`() {
        runBlocking { db.seedPlan() }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                db.exerciseDao().upsertWithSecondaries(
                    exercise("e1"),
                    listOf(ExerciseSecondaryEntity("e1", "m2", 1.5)),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                db.exerciseDao().upsertWithSecondaries(
                    exercise("e1"),
                    listOf(ExerciseSecondaryEntity("e1", "m2", -0.1)),
                )
            }
        }
        // El principal no puede figurar como secundario.
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                db.exerciseDao().upsertWithSecondaries(
                    exercise("e1", primaryMuscleId = "m2"),
                    listOf(ExerciseSecondaryEntity("e1", "m2", 0.5)),
                )
            }
        }

        runBlocking {
            db.exerciseDao().upsertWithSecondaries(
                exercise("e1"),
                listOf(
                    ExerciseSecondaryEntity("e1", "m2", 0.0),
                    ExerciseSecondaryEntity("e1", "m3", 1.0),
                ),
            )
            assertEquals(
                listOf(0.0, 1.0),
                db.exerciseDao().secondariesOf("e1").map { it.factor },
            )
        }
    }

    @Test
    fun `borrar una rutina no borra las sesiones historicas`() {
        runBlocking { db.seedPlan() }

        val sessionId = "s1"
        runBlocking {
            db.sessionDao().startSession(
                SessionEntity(
                    id = sessionId,
                    routineId = ROUTINE_ID,
                    routineDayId = DAY_ID,
                    startedAt = 1_700_000_000_000L,
                    status = SessionStatus.ACTIVE,
                ),
            )
            db.sessionSetDao().upsert(
                SessionSetEntity(
                    id = "set-1",
                    sessionId = sessionId,
                    dayItemId = "i1",
                    exerciseId = "e1",
                    weightKg = 60.0,
                    reps = 8,
                    completedAt = 1_700_000_100_000L,
                ),
            )
        }

        runBlocking { db.routineDao().delete(ROUTINE_ID) }

        runBlocking {
            val session = db.sessionDao().getById(sessionId)
            assertNotNull("la sesión histórica no debe desaparecer", session)
            assertEquals(ROUTINE_ID, session!!.routineId)
            assertEquals("el día borrado deja el id huérfano", DAY_ID, session.routineDayId)
            assertEquals(1, db.sessionSetDao().ofSession(sessionId).size)
        }
    }

    @Test
    fun `solo puede haber una sesion activa a la vez`() {
        runBlocking { db.seedPlan() }

        runBlocking {
            db.sessionDao().startSession(
                SessionEntity(id = "s1", startedAt = 1L, status = SessionStatus.ACTIVE),
            )
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                db.sessionDao().startSession(
                    SessionEntity(id = "s2", startedAt = 2L, status = SessionStatus.ACTIVE),
                )
            }
        }

        // Cerrada la primera, la segunda entra.
        runBlocking {
            db.sessionDao().close(
                id = "s1",
                endedAt = 10L,
                status = SessionStatus.FINISHED,
                notes = null,
            )
            db.sessionDao().startSession(
                SessionEntity(id = "s2", startedAt = 20L, status = SessionStatus.ACTIVE),
            )
            assertEquals("s2", db.sessionDao().active()?.id)
        }
    }

    @Test
    fun `load_type y status se guardan como texto y vuelven como enum`() {
        runBlocking {
            db.seedPlan()
            db.exerciseDao().upsertExercise(exercise("bw").copy(loadType = LoadType.BODYWEIGHT))
            db.sessionDao().upsert(SessionEntity(id = "s9", startedAt = 5L, status = SessionStatus.ABANDONED))

            assertEquals(LoadType.BODYWEIGHT, db.exerciseDao().getById("bw")!!.loadType)
            assertEquals(SessionStatus.ABANDONED, db.sessionDao().getById("s9")!!.status)
        }
    }
}
