package app.gimcheck.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Creación de la base desde cero (el "0 → 1" de `docs/especificacion.md` §2) con
 * `MigrationTestHelper`, bajo Robolectric.
 *
 * Por qué no hay un `Migration(0, 1)`: Room nunca ejecuta una migración desde la
 * versión 0. Una instalación nueva abre la base vacía y llama `onCreate`, que crea
 * las tablas a partir del esquema compilado; recién desde la v2 existe una
 * `Migration(n, n+1)` que correr. `createDatabase(db, 1)` es ese camino de
 * creación, y acá se verifica contra el schema JSON exportado y commiteado en
 * `app/schemas/`.
 *
 * `MigrationTestHelper` lee `<clase de la base>/<version>.json` de los assets: por
 * eso `app/build.gradle.kts` copia `app/schemas` a los assets de `debug`.
 */
@RunWith(RobolectricTestRunner::class)
class SchemaCreationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun `la base se crea desde cero en la version 1 con las once tablas de la especificacion`() {
        val db = helper.createDatabase(TEST_DB, AppDatabase.VERSION)
        try {
            val tables = tableNames(db)
            assertTrue("faltan tablas: ${EXPECTED_TABLES - tables.toSet()}", tables.containsAll(EXPECTED_TABLES))
            assertTrue(
                "tablas inesperadas: ${tables - EXPECTED_TABLES.toSet()}",
                (tables - EXPECTED_TABLES.toSet()).all { it == "android_metadata" },
            )
            EXPECTED_COLUMNS.forEach { (table, columns) ->
                assertEquals("columnas de $table", columns, columnNames(db, table))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `day_item tiene clave foranea a routine_day y a exercise con borrado en cascada`() {
        val db = helper.createDatabase(TEST_DB, AppDatabase.VERSION)
        try {
            val fks = foreignKeys(db, "day_item")
            assertEquals(2, fks.size)
            assertEquals(setOf("routine_day", "exercise"), fks.map { it.table }.toSet())
            assertTrue(
                "todas las FK de day_item deben borrar en cascada: $fks",
                fks.all { it.onDelete == "CASCADE" },
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `las claves foraneas de day_item tienen indice`() {
        val db = helper.createDatabase(TEST_DB, AppDatabase.VERSION)
        try {
            val indexes = indexNames(db, "day_item")
            assertTrue("faltó el índice de day_id: $indexes", indexes.any { it.contains("day_id") })
            assertTrue(
                "faltó el índice de exercise_id: $indexes",
                indexes.any { it.contains("exercise_id") },
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `el schema exportado esta en los assets de debug y declara version 1`() {
        val json = ApplicationProvider.getApplicationContext<Context>()
            .assets.open("${AppDatabase::class.java.canonicalName}/1.json")
            .bufferedReader()
            .use { it.readText() }
        assertTrue("el JSON no declara la version 1", json.contains("\"version\": 1"))
        // room_master_table la crea Room en tiempo de ejecución, no está en el schema.
        EXPECTED_COLUMNS.keys.forEach { table ->
            assertTrue("el schema no declara la tabla $table", json.contains("\"tableName\": \"$table\""))
        }
    }

    private companion object {
        const val TEST_DB = "schema-creation-test.db"

        /** Las once tablas de §2 (más la de control de Room). */
        val EXPECTED_TABLES = listOf(
            "app_setting",
            "body_metric",
            "day_item",
            "exercise",
            "exercise_secondary",
            "muscle",
            "room_master_table",
            "routine",
            "routine_day",
            "session",
            "session_set",
            "session_state",
        )

        /** Columnas por tabla, en el orden declarado por la entidad (= `PRAGMA table_info`). */
        val EXPECTED_COLUMNS = linkedMapOf(
            "muscle" to listOf("id", "name", "position", "archived"),
            "exercise" to listOf(
                "id", "name", "primary_muscle_id", "seconds_per_set", "rest_seconds",
                "compound", "load_type", "notes", "position", "archived",
            ),
            "exercise_secondary" to listOf("exercise_id", "muscle_id", "factor"),
            "routine" to listOf("id", "name", "notes", "position", "created_at", "archived"),
            "routine_day" to listOf("id", "routine_id", "name", "position"),
            "day_item" to listOf(
                "id", "day_id", "exercise_id", "position", "sets", "drop_set",
                "superset_next", "rest_override_seconds", "target_reps_min", "target_reps_max",
            ),
            "session" to listOf(
                "id", "routine_id", "routine_day_id", "started_at", "ended_at", "status",
                "notes", "body_weight_kg",
            ),
            "session_set" to listOf(
                "id", "session_id", "day_item_id", "exercise_id", "round_index", "set_index",
                "weight_kg", "reps", "rpe", "is_drop_set", "is_warmup", "completed_at", "note",
            ),
            "session_state" to listOf(
                "session_id", "block_index", "round_index", "item_index", "rest_ends_at",
                "rest_seconds", "updated_at",
            ),
            "body_metric" to listOf("date", "weight_kg", "body_fat_pct", "note", "updated_at"),
            "app_setting" to listOf("key", "value"),
        )
    }
}

/** Nombre de una FK tal como lo devuelve `PRAGMA foreign_key_list`. */
internal data class ForeignKeyRow(
    val table: String,
    val from: String,
    val to: String,
    val onDelete: String,
)

internal fun tableNames(db: SupportSQLiteDatabase): List<String> =
    db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

internal fun columnNames(db: SupportSQLiteDatabase, table: String): List<String> =
    db.query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }

internal fun foreignKeys(db: SupportSQLiteDatabase, table: String): List<ForeignKeyRow> =
    db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
        val tableIndex = cursor.getColumnIndexOrThrow("table")
        val fromIndex = cursor.getColumnIndexOrThrow("from")
        val toIndex = cursor.getColumnIndexOrThrow("to")
        val onDeleteIndex = cursor.getColumnIndexOrThrow("on_delete")
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ForeignKeyRow(
                        table = cursor.getString(tableIndex),
                        from = cursor.getString(fromIndex),
                        to = cursor.getString(toIndex),
                        onDelete = cursor.getString(onDeleteIndex),
                    ),
                )
            }
        }
    }

internal fun indexNames(db: SupportSQLiteDatabase, table: String): List<String> =
    db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?", arrayOf(table))
        .use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
