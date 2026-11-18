package app.gimcheck.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Sesiones: `session`, `session_set`, `session_state` (`docs/especificacion.md` §2)
 * más `body_metric` y `app_setting`.
 *
 * Decisión de tipos de tiempo (ADR 0003): los **instantes** van como INTEGER
 * epoch millis UTC (`started_at`, `ended_at`, `completed_at`, `rest_ends_at`,
 * `updated_at`); `:core` trabaja con `LocalDateTime` y el repositorio (GIM-10)
 * convierte. La única excepción es `routine.created_at` (TEXT ISO), que es un
 * dato de procedencia del import y puede venir vacío.
 */

/** Estado de una sesión (§2). Se guarda como TEXT con el nombre del enum. */
enum class SessionStatus { ACTIVE, FINISHED, ABANDONED }

/**
 * Sesión de entrenamiento (§2 `session`).
 *
 * `routine_id` / `routine_day_id` **no** son FK a propósito: §2 pide que borrar
 * una rutina no borre el histórico, y la UI muestra "rutina eliminada". Los ids
 * quedan huérfanos y se conservan; un FK con `SET NULL` perdería el dato. Llevan
 * índice porque el histórico se consulta por rutina/día.
 *
 * `status = ACTIVE` es único por dispositivo: lo garantiza
 * `SessionDao.startSession` (una sesión nueva no arranca si ya hay otra abierta).
 */
@Entity(
    tableName = "session",
    indices = [
        Index("routine_id"),
        Index("routine_day_id"),
        Index("status"),
        Index("started_at"),
    ],
)
data class SessionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "routine_id") val routineId: String? = null,
    @ColumnInfo(name = "routine_day_id") val routineDayId: String? = null,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    @ColumnInfo(name = "status") val status: SessionStatus = SessionStatus.ACTIVE,
    @ColumnInfo(name = "notes") val notes: String? = null,
    @ColumnInfo(name = "body_weight_kg") val bodyWeightKg: Double? = null,
)

/**
 * Serie anotada (§2 `session_set`).
 *
 * `session_id` sí es FK con CASCADE: la serie no existe sin su sesión.
 * `day_item_id` y `exercise_id` **no** son FK: el histórico tiene que sobrevivir
 * al borrado del ítem o del ejercicio (si no, borrar un ejercicio se llevaría
 * las series ya entrenadas). Ambos son nullable porque un ítem eliminado deja el
 * id sin fila.
 *
 * `round_index` / `set_index` son la posición dentro del bloque de superserie y
 * dentro de las series del ítem (§4); `completed_at` es el instante real.
 */
@Entity(
    tableName = "session_set",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("session_id"),
        Index("day_item_id"),
        Index("exercise_id"),
        Index("completed_at"),
    ],
)
data class SessionSetEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "day_item_id") val dayItemId: String? = null,
    @ColumnInfo(name = "exercise_id") val exerciseId: String? = null,
    @ColumnInfo(name = "round_index") val roundIndex: Int = 0,
    @ColumnInfo(name = "set_index") val setIndex: Int = 0,
    @ColumnInfo(name = "weight_kg") val weightKg: Double = 0.0,
    @ColumnInfo(name = "reps") val reps: Int = 0,
    @ColumnInfo(name = "rpe") val rpe: Double? = null,
    @ColumnInfo(name = "is_drop_set") val isDropSet: Boolean = false,
    @ColumnInfo(name = "is_warmup") val isWarmup: Boolean = false,
    @ColumnInfo(name = "completed_at") val completedAt: Long,
    @ColumnInfo(name = "note") val note: String? = null,
)

/**
 * Cursor de la sesión guiada (§2 `session_state`, §4): permite retomar donde
 * quedó aunque el proceso haya muerto, incluido el descanso pendiente
 * (`rest_ends_at`).
 */
@Entity(
    tableName = "session_state",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionStateEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "block_index") val blockIndex: Int = 0,
    @ColumnInfo(name = "round_index") val roundIndex: Int = 0,
    @ColumnInfo(name = "item_index") val itemIndex: Int = 0,
    @ColumnInfo(name = "rest_ends_at") val restEndsAt: Long? = null,
    @ColumnInfo(name = "rest_seconds") val restSeconds: Int? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Peso corporal del día (§2 `body_metric`). Clave natural: la fecha
 * `YYYY-MM-DD` en la zona del usuario, que es como la web y `:core`
 * (`BodyMetric.date`) agrupan las métricas.
 */
@Entity(tableName = "body_metric")
data class BodyMetricEntity(
    @PrimaryKey @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "weight_kg") val weightKg: Double,
    @ColumnInfo(name = "body_fat_pct") val bodyFatPct: Double? = null,
    @ColumnInfo(name = "note") val note: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Ajuste global (§2 `app_setting`): clave/valor con el JSON laxo del valor.
 *
 * Todo el estado de la app vive en Room (ADR 0003): no hay DataStore. Un ajuste
 * nuevo es una fila, no una migración; el tipado fuerte lo pone el repositorio
 * (GIM-10) con `kotlinx.serialization` sobre `value`.
 */
@Entity(tableName = "app_setting")
data class AppSettingEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)
