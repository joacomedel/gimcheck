package app.gimcheck.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup propio (`schema: gimcheck`, versión 1) — `docs/especificacion.md` §7.
 *
 * Cubre: forma del JSON, determinismo, ida y vuelta, validación con error tipado y
 * la fusión por id con last-write-wins sobre `updatedAt`.
 */
class BackupJsonTest {

    private val snapshot: DomainSnapshot = importWeb(fixture("web/web_v21.json")).valueOrThrow().snapshot

    private val exportedAt = "2026-11-18T09:30:00"

    @Test
    fun `el backup tiene la forma de la especificacion y es determinista`() {
        val first = exportBackup(snapshot, exportedAt)
        val second = exportBackup(snapshot, exportedAt)
        assertEquals(first, second)

        val root = Json.parseToJsonElement(first).jsonObject
        assertEquals(
            listOf("schema", "version", "exportedAt", "settings", "muscles", "exercises", "routines", "sessions", "bodyMetrics"),
            root.keys.toList(),
        )
        assertEquals("gimcheck", root["schema"]!!.jsonPrimitive.content)
        assertEquals(1, root["version"]!!.jsonPrimitive.int)
        assertEquals(exportedAt, root["exportedAt"]!!.jsonPrimitive.content)

        // Otro instante de exportación cambia el string (y sólo por eso).
        assertNotEquals(first, exportBackup(snapshot, "2026-11-19T00:00:00"))
    }

    @Test
    fun `el backup conserva ids y vuelve identico`() {
        val json = exportBackup(snapshot, exportedAt)
        val restored = importBackup(json).valueOrThrow()

        assertEquals(snapshot.muscles.map { it.id }, restored.muscles.map { it.id })
        assertEquals(snapshot.exercises.map { it.id }, restored.exercises.map { it.id })
        assertEquals(32, restored.exercises.size)
        assertEquals(snapshot.settings, restored.settings)
        assertEquals(2, restored.routines.size)

        // Los ítems mantienen id de ítem, ejercicio, series, drop set y superserie.
        val original = snapshot.activeRoutine()!!.days.flatMap { it.items }
        val copy = restored.activeRoutine()!!.days.flatMap { it.items }
        assertEquals(original.map { it.id }, copy.map { it.id })
        assertEquals(original.map { it.exerciseId }, copy.map { it.exerciseId })
        assertEquals(original.map { it.sets }, copy.map { it.sets })
        assertEquals(original.map { it.supersetNext }, copy.map { it.supersetNext })

        assertEquals(json, exportBackup(restored, exportedAt))
    }

    @Test
    fun `sesiones y metricas corporales sobreviven la ida y vuelta`() {
        val withHistory = snapshot.copy(
            sessions = listOf(
                Session(
                    id = "s1",
                    startedAt = LocalDateTime(2026, 11, 17, 19, 0),
                    endedAt = LocalDateTime(2026, 11, 17, 20, 5),
                    bodyWeightKg = 78.4,
                    routineId = "web-plan-1",
                    routineDayId = "da1",
                    sets = listOf(
                        SessionSet(
                            id = "ss1",
                            exerciseId = "pp",
                            weightKg = 70.0,
                            reps = 8,
                            completedAt = LocalDateTime(2026, 11, 17, 19, 10),
                            isWarmup = false,
                        ),
                    ),
                ),
            ),
            bodyMetrics = listOf(BodyMetric(LocalDate(2026, 11, 17), 78.4, updatedAt = exportedAt)),
        )

        val restored = importBackup(exportBackup(withHistory, exportedAt)).valueOrThrow()
        assertEquals(1, restored.sessions.size)
        assertEquals(LocalDateTime(2026, 11, 17, 20, 5), restored.sessions.first().endedAt)
        assertEquals("ss1", restored.sessions.first().sets.first().id)
        assertEquals(70.0, restored.sessions.first().sets.first().weightKg, 1e-9)
        assertEquals(78.4, restored.sessions.first().bodyWeightKg!!, 1e-9)
        assertEquals(1, restored.bodyMetrics.size)
        assertEquals(LocalDate(2026, 11, 17), restored.bodyMetrics.first().date)
    }

    @Test
    fun `un backup ajeno o roto falla con error tipado`() {
        assertTrue(importBackup("no es json").errorOrNull() is ImportError.NotJson)
        assertTrue(importBackup("[]").errorOrNull() is ImportError.NotJson)

        val wrongSchema = exportBackup(snapshot, exportedAt).replace("\"schema\":\"gimcheck\"", "\"schema\":\"rutinas-gym\"")
        val schemaError = importBackup(wrongSchema).errorOrNull()
        assertTrue("era $schemaError", schemaError is ImportError.UnsupportedVersion)
        assertEquals("rutinas-gym", (schemaError as ImportError.UnsupportedVersion).schema)

        val future = exportBackup(snapshot, exportedAt).replace("\"version\":1", "\"version\":7")
        val futureError = importBackup(future).errorOrNull()
        assertTrue("era $futureError", futureError is ImportError.UnsupportedVersion)
        assertEquals(7, (futureError as ImportError.UnsupportedVersion).version)

        // id repetido en el catálogo: se rechaza en vez de dejar la base a medias.
        val duplicated = exportBackup(snapshot, exportedAt)
            .replace("\"id\":\"hu\"", "\"id\":\"pp\"")
        val duplicateError = importBackup(duplicated).errorOrNull()
        assertTrue("era $duplicateError", duplicateError is ImportError.InvalidValue)
        assertTrue(duplicateError!!.message.contains("repetido"))
    }

    @Test
    fun `una referencia rota no se importa`() {
        val json = exportBackup(snapshot, exportedAt).replace("\"exerciseId\":\"pp\"", "\"exerciseId\":\"fantasma\"")
        val error = importBackup(json).errorOrNull()
        assertTrue("era $error", error is ImportError.InvalidValue)
        assertTrue(error!!.message.contains("fantasma"))
    }

    @Test
    fun `la fusion gana la fila con updatedAt mas nuevo`() {
        val older = parseBackup(exportBackup(snapshot, "2026-11-01T00:00:00", stamp = "2026-11-01T00:00:00")).valueOrThrow()
        val newer = parseBackup(
            exportBackup(
                snapshot.copy(exercises = snapshot.exercises.map {
                    if (it.id == "pp") it.copy(name = "Press plano (nuevo)") else it
                }),
                "2026-11-18T00:00:00",
                stamp = "2026-11-18T00:00:00",
            ),
        ).valueOrThrow()

        // Last-write-wins no depende del orden de los argumentos: gana la fecha mayor.
        assertEquals("Press plano (nuevo)", mergeBackup(older, newer).exercises.first { it.id == "pp" }.name)
        assertEquals("Press plano (nuevo)", mergeBackup(newer, older).exercises.first { it.id == "pp" }.name)
        assertEquals(32, mergeBackup(older, newer).exercises.size)

        // Y el que pierde no arrastra su contenido: el nombre viejo no queda en ningún lado.
        assertTrue(mergeBackup(newer, older).exercises.none { it.name == "Press plano" })
    }

    @Test
    fun `la fusion conserva lo que solo existe de un lado`() {
        val base = parseBackup(exportBackup(snapshot, "2026-11-01T00:00:00", stamp = "2026-11-01T00:00:00")).valueOrThrow()
        val extra = base.copy(
            exercises = base.exercises + BackupExercise(
                id = "nuevo",
                name = "Ejercicio nuevo",
                primaryMuscleId = "Pecho",
                secondsPerSet = 40,
                restSeconds = 90,
                updatedAt = "2026-11-10T00:00:00",
            ),
            muscles = base.muscles + BackupMuscle("Nuevo músculo", "Nuevo músculo", 15),
        )

        val merged = mergeBackup(base, extra)
        assertEquals(33, merged.exercises.size)
        assertEquals(16, merged.muscles.size)
        // El orden del resultado: primero lo existente, después lo nuevo.
        assertEquals("pp", merged.exercises.first().id)
        assertEquals("nuevo", merged.exercises.last().id)

        // Y el resultado fusionado sigue siendo un backup válido e importable.
        assertEquals(33, importBackup(Json.encodeToString(BackupFile.serializer(), merged)).valueOrThrow().exercises.size)
    }

    @Test
    fun `un updatedAt vacio no pisa un valor con fecha`() {
        val base = parseBackup(exportBackup(snapshot, exportedAt, stamp = exportedAt)).valueOrThrow()
        val older = base.copy(
            exercises = base.exercises.map {
                if (it.id == "pp") it.copy(name = "sin fecha", updatedAt = "") else it
            },
        )
        val merged = mergeBackup(base, older)
        assertEquals("Press plano", merged.exercises.first { it.id == "pp" }.name)
    }
}
