package app.gimcheck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * El camino crudo de escritura de los DAO no es alcanzable desde afuera (deuda
 * heredada de la auditoría de GIM-9).
 *
 * Kotlin no permite `internal` en un miembro de interfaz, así que la restricción se
 * aplica en dos capas y este test verifica las dos:
 *
 *  1. **Fuera del módulo**: cada `@Dao` de `data/db` está declarado `internal`. Un
 *     módulo de UI (o `:core`) no puede ni nombrar el DAO, mucho menos escribir.
 *  2. **Dentro del módulo**: los mutadores crudos —los que saltean una invariante de
 *     §2, como `SessionDao.upsert`, que permitiría dos sesiones `ACTIVE`— sólo los
 *     usan los propios DAO. Ningún archivo de `src/main` fuera de `data/db` los
 *     nombra: el único camino de escritura de la app son los repositorios.
 *
 * Se mira el código fuente y no el bytecode a propósito: `internal` es `public` en el
 * JVM, así que la reflexión no puede distinguirlo.
 */
class DaoWritePathTest {

    /** Mutadores crudos: los llama el DAO, nunca un repositorio ni una pantalla. */
    private val rawMutators = listOf(
        // DayDao: sets fuera de 1..20 y superserie en el último ítem
        "upsertItem(",
        "setItemPosition(",
        "setItemSupersetNext(",
        "deleteItemRow(",
        "deleteItemsOf(",
        "clearLastItemSupersetNext(",
        // RoutineDao
        "upsertRoutine(",
        "upsertDay(",
        // ExerciseDao: factor fuera de [0,1] y borrado sin cascada a los días
        "upsertExercise(",
        "upsertSecondaries(",
        "deleteSecondariesOf(",
        "deleteDayItemsOfExercise(",
        "deleteExerciseRow(",
        // SessionDao: dos sesiones ACTIVE
        "sessionDao().upsert(",
    )

    @Test
    fun `todos los DAO son internal y no se pueden usar desde otro modulo`() {
        val daos = mainSources()
            .filter { it.parentFile.name == "db" }
            .filter { it.readText().contains("@Dao") }

        assertTrue("no se encontró ningún DAO en ${moduleDir()}", daos.isNotEmpty())
        daos.forEach { file ->
            val text = file.readText()
            assertTrue(
                "${file.name} declara un @Dao que no es `internal`",
                Regex("""@Dao\s+internal interface \w+""").containsMatchIn(text),
            )
            assertTrue(
                "${file.name}: @Database expone un DAO público",
                !Regex("""^\s*abstract fun \w+Dao\(\)""", RegexOption.MULTILINE).containsMatchIn(text),
            )
        }

        // La base en sí es `internal`: fuera del módulo no existe el tipo con el que
        // se piden los DAO, así que la restricción no se apoya sólo en la convención.
        val database = mainSources().single { it.name == "AppDatabase.kt" }.readText()
        assertTrue(
            "AppDatabase tiene que ser internal",
            Regex("""internal abstract class AppDatabase\b""").containsMatchIn(database),
        )
        assertEquals(
            "los nueve DAO de §2 tienen que estar expuestos por la base",
            9,
            Regex("""abstract fun \w+Dao\(\)""").findAll(database).count(),
        )
    }

    @Test
    fun `fuera de data db nadie escribe por el camino crudo`() {
        val offenders = mainSources()
            .filterNot { it.parentFile.name == "db" }
            .flatMap { file ->
                val text = file.readText()
                rawMutators.filter { text.contains(it) }.map { "${file.name}: $it" }
            }

        assertTrue("escrituras crudas fuera de los repositorios: $offenders", offenders.isEmpty())
    }

    @Test
    fun `los cinco repositorios existen y son el camino de escritura`() {
        val repositories = mainSources().filter { it.parentFile.name == "repository" }.map { it.name }.toSet()

        listOf(
            "RoutineRepository.kt",
            "ExerciseRepository.kt",
            "SessionRepository.kt",
            "MetricsRepository.kt",
            "SettingsRepository.kt",
        ).forEach { expected ->
            assertTrue("falta $expected", expected in repositories)
        }
    }

    /** `app/src/main/java`: se sube desde el working dir del test (`app/`) hasta encontrarlo. */
    private fun mainSources(): List<File> {
        val dir = File(moduleDir(), "src/main/java")
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun moduleDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("no se encontró src/main/java desde ${System.getProperty("user.dir")}")
    }
}
