package app.gimcheck.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Planes: `routine`, `routine_day`, `day_item` (`docs/especificacion.md` §2).
 *
 * `position` es el orden de ejecución y **no se renumera** al borrar: los huecos
 * son inofensivos (`ORDER BY position`) y así dos escrituras concurrentes no se
 * pisan posiciones. El "último ítem de un día" es siempre el de `MAX(position)`.
 */

/**
 * Plan/rutina (§2 `routine`). [createdAt] es TEXT ISO-8601 y no epoch, porque el
 * dominio (`app.gimcheck.core.Routine.createdAt`) lo guarda como String y el
 * import de la web puede no traerlo (cadena vacía). Los instantes de sesión sí
 * son epoch millis: ver [SessionEntity].
 */
@Entity(tableName = "routine")
data class RoutineEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "notes") val notes: String? = null,
    @ColumnInfo(name = "position") val position: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "archived") val archived: Boolean = false,
)

/** Día de un plan (§2 `routine_day`). */
@Entity(
    tableName = "routine_day",
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["id"],
            childColumns = ["routine_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("routine_id")],
)
data class RoutineDayEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "routine_id") val routineId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "position") val position: Int = 0,
)

/**
 * Ítem de un día (§2 `day_item`): ejercicio + series + marcas de superserie y
 * drop set.
 *
 * `sets` debe quedar en 1..20: lo valida `DayDao` antes de escribir
 * ([DayDao.MIN_SETS], [DayDao.MAX_SETS]). `superset_next` nunca puede ser true
 * en el último ítem del día: `DayDao` lo fuerza en cada alta/edición y
 * `ExerciseDao.deleteExerciseCascade` lo recalcula cuando el borrado deja otro
 * ítem al final.
 */
@Entity(
    tableName = "day_item",
    foreignKeys = [
        ForeignKey(
            entity = RoutineDayEntity::class,
            parentColumns = ["id"],
            childColumns = ["day_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("day_id"), Index("exercise_id")],
)
data class DayItemEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "day_id") val dayId: String,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "position") val position: Int = 0,
    @ColumnInfo(name = "sets") val sets: Int,
    @ColumnInfo(name = "drop_set") val dropSet: Boolean = false,
    @ColumnInfo(name = "superset_next") val supersetNext: Boolean = false,
    @ColumnInfo(name = "rest_override_seconds") val restOverrideSeconds: Int? = null,
    @ColumnInfo(name = "target_reps_min") val targetRepsMin: Int? = null,
    @ColumnInfo(name = "target_reps_max") val targetRepsMax: Int? = null,
)
