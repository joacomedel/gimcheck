package app.gimcheck.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.gimcheck.core.LoadType

/*
 * Tablas del catálogo: `muscle`, `exercise`, `exercise_secondary`
 * (`docs/especificacion.md` §2). Los campos son los del dominio de `:core`
 * (Muscle/Exercise/Secondary en `app.gimcheck.core.Models`); `notes` es el único
 * que viene de §2 y no del dominio, porque no entra en ningún cálculo.
 *
 * Reglas de integridad de §2 que viven en esta capa:
 *  - `factor ∈ [0,1]` lo valida `ExerciseDao.upsertWithSecondaries` (SQLite no
 *    tiene CHECK desde las anotaciones de Room; el borde está en el DAO).
 *  - borrar un `exercise` arrastra `exercise_secondary` (CASCADE) y lo saca de
 *    `day_item` en la misma transacción (`ExerciseDao.deleteExerciseCascade`).
 *  - borrar un `muscle` con ejercicios está prohibido (RESTRICT): primero se
 *    reasignan, no se pierden ejercicios por accidente.
 */

/** Músculo del catálogo editable (§2 `muscle`). */
@Entity(tableName = "muscle")
data class MuscleEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "position") val position: Int = 0,
    @ColumnInfo(name = "archived") val archived: Boolean = false,
)

/**
 * Ejercicio del catálogo (§2 `exercise`).
 *
 * @param id UUID v4 en texto, como el resto de las tablas.
 * @param notes texto libre de §2 (el dominio no lo usa todavía).
 * @param loadType se guarda como TEXT con el nombre del enum ([Converters]).
 */
@Entity(
    tableName = "exercise",
    foreignKeys = [
        ForeignKey(
            entity = MuscleEntity::class,
            parentColumns = ["id"],
            childColumns = ["primary_muscle_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("primary_muscle_id")],
)
data class ExerciseEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "primary_muscle_id") val primaryMuscleId: String,
    @ColumnInfo(name = "seconds_per_set") val secondsPerSet: Int,
    @ColumnInfo(name = "rest_seconds") val restSeconds: Int,
    @ColumnInfo(name = "compound") val compound: Boolean = false,
    @ColumnInfo(name = "load_type") val loadType: LoadType = LoadType.EXTERNAL,
    @ColumnInfo(name = "notes") val notes: String? = null,
    @ColumnInfo(name = "position") val position: Int = 0,
    @ColumnInfo(name = "archived") val archived: Boolean = false,
)

/**
 * Músculo secundario de un ejercicio con su factor de aporte (§2
 * `exercise_secondary`). PK compuesta `(exercise_id, muscle_id)`, que ya cubre
 * el índice de la FK `exercise_id`; la FK `muscle_id` lleva su propio índice.
 */
@Entity(
    tableName = "exercise_secondary",
    primaryKeys = ["exercise_id", "muscle_id"],
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MuscleEntity::class,
            parentColumns = ["id"],
            childColumns = ["muscle_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("muscle_id")],
)
data class ExerciseSecondaryEntity(
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "muscle_id") val muscleId: String,
    @ColumnInfo(name = "factor") val factor: Double,
)
