package app.gimcheck.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/** Versión del esquema. Cambiarla obliga a una `Migration` y su test. */
internal const val DB_VERSION: Int = 1

/**
 * Base local de GimCheck: las once tablas de `docs/especificacion.md` §2.
 *
 * Decisiones (detalle en `docs/adr/0003-persistencia-room.md`):
 *
 *  - **Todo va a Room**, incluidos los ajustes (`app_setting` key/value). No hay
 *    DataStore: los ajustes se exportan con el backup, entran en la misma
 *    transacción que el resto del modelo y no agregan un segundo motor de
 *    persistencia con su propio ciclo de vida.
 *  - `exportSchema = true` y los JSON versionados en `app/schemas/`: sin eso no
 *    hay `MigrationTestHelper` ni auditoría del esquema.
 *  - **v1 es la creación**: Room no ejecuta una `Migration(0, 1)`; una instalación
 *    nueva llama `onCreate` con el esquema exportado. El "0→1" se prueba con
 *    `MigrationTestHelper.createDatabase(1)`, que es exactamente ese camino
 *    (`app/src/test/java/app/gimcheck/data/db/SchemaCreationTest.kt`). Desde la
 *    v2, cada bump lleva `Migration(n, n+1)` + `createDatabase(n)` /
 *    `runMigrationsAndValidate(n+1)`.
 *  - Sin `allowMainThreadQueries`: ninguna consulta puede correr en el hilo
 *    principal. Los DAO son `suspend`/`Flow`; el repositorio (GIM-10) envuelve
 *    lo que no sea DAO en `Dispatchers.IO`.
 */
@Database(
    entities = [
        MuscleEntity::class,
        ExerciseEntity::class,
        ExerciseSecondaryEntity::class,
        RoutineEntity::class,
        RoutineDayEntity::class,
        DayItemEntity::class,
        SessionEntity::class,
        SessionSetEntity::class,
        SessionStateEntity::class,
        BodyMetricEntity::class,
        AppSettingEntity::class,
    ],
    version = DB_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun muscleDao(): MuscleDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun routineDao(): RoutineDao
    abstract fun dayDao(): DayDao
    abstract fun sessionDao(): SessionDao
    abstract fun sessionSetDao(): SessionSetDao
    abstract fun sessionStateDao(): SessionStateDao
    abstract fun bodyMetricDao(): BodyMetricDao
    abstract fun settingDao(): SettingDao

    companion object {
        /** Versión del esquema, para tests y para el chequeo de migraciones. */
        const val VERSION: Int = DB_VERSION

        /** Nombre del archivo SQLite (lo usa el test de creación con `MigrationTestHelper`). */
        const val FILE_NAME: String = "gimcheck.db"

        /**
         * Construye la base del dispositivo. Sin singleton a propósito: la
         * inyección del repositorio es de GIM-10; los tests construyen las suyas.
         */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, FILE_NAME)
                .build()
    }
}
