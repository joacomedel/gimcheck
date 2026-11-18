package app.gimcheck.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Contrato de los DAO: lectura con `Flow` (reactiva, sin bloquear) y escritura
 * `suspend`. No hay un solo método bloqueante, y eso es lo que permite cumplir
 * "ninguna consulta en el hilo principal" sin `allowMainThreadQueries`.
 */
@RunWith(RobolectricTestRunner::class)
class DaoContractTest {

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
    fun `el Flow de musculos refleja cada escritura`() {
        runBlocking {
            assertEquals(emptyList<MuscleEntity>(), db.muscleDao().observeAll().first())

            db.muscleDao().upsert(muscle("m1", "Pecho"))

            assertEquals(listOf("Pecho"), db.muscleDao().observeAll().first().map { it.name })

            db.muscleDao().upsert(muscle("m2", "Espalda", position = 1))

            assertEquals(
                listOf("Pecho", "Espalda"),
                db.muscleDao().observeAll().first().map { it.name },
            )
        }
    }

    @Test
    fun `el Flow de items del dia respeta el orden por position`() {
        runBlocking {
            db.seedPlan()
            assertEquals(
                listOf("i1", "i2", "i3"),
                db.dayDao().observeItems(DAY_ID).first().map { it.id },
            )

            db.dayDao().moveItem("i3", -2)

            assertEquals(
                listOf("i3", "i1", "i2"),
                db.dayDao().observeItems(DAY_ID).first().map { it.id },
            )
        }
    }

    @Test
    fun `mover el ultimo item arrastra la marca de superserie y deja el nuevo ultimo limpio`() {
        runBlocking {
            db.seedPlan()
            db.dayDao().moveItem("i3", -2) // i3, i1, i2

            val items = db.dayDao().items(DAY_ID)
            assertEquals(listOf("i3", "i1", "i2"), items.map { it.id })
            assertEquals(listOf(0, 1, 2), items.map { it.position })
            // i2 pasó a ser el último: no puede seguir encadenando
            assertEquals(false, items.last().supersetNext)
        }
    }

    @Test
    fun `los ajustes se guardan como clave valor y se leen igual`() {
        runBlocking {
            assertNull(db.settingDao().get("optMin"))

            db.settingDao().put(AppSettingEntity(key = "optMin", value = "10"))
            db.settingDao().put(AppSettingEntity(key = "includeIndirect", value = "false"))

            assertEquals("10", db.settingDao().get("optMin"))
            assertEquals(
                listOf("includeIndirect", "optMin"),
                db.settingDao().observeAll().first().map { it.key },
            )

            db.settingDao().delete("optMin")
            assertNull(db.settingDao().get("optMin"))
        }
    }

    @Test
    fun `las metricas corporales usan la fecha como clave natural`() {
        runBlocking {
            db.bodyMetricDao().upsert(
                BodyMetricEntity(date = "2026-11-18", weightKg = 82.5, updatedAt = 1L),
            )
            db.bodyMetricDao().upsert(
                BodyMetricEntity(date = "2026-11-18", weightKg = 82.1, updatedAt = 2L),
            )

            assertEquals(1, db.bodyMetricDao().observeAll().first().size)
            assertEquals(82.1, db.bodyMetricDao().getByDate("2026-11-18")!!.weightKg, 0.001)
        }
    }
}
