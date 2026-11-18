package app.gimcheck.data.repository

import app.gimcheck.core.DayItem
import app.gimcheck.core.Exercise
import app.gimcheck.core.Muscle
import app.gimcheck.core.Routine
import app.gimcheck.core.RoutineDay
import app.gimcheck.core.Secondary
import app.gimcheck.data.db.DayItemEntity
import app.gimcheck.data.db.ExerciseEntity
import app.gimcheck.data.db.ExerciseSecondaryEntity
import app.gimcheck.data.db.MuscleEntity
import app.gimcheck.data.db.RoutineDayEntity
import app.gimcheck.data.db.RoutineEntity

/*
 * Mapeo entidad ↔ dominio (`docs/especificacion.md` §2 ↔ `app.gimcheck.core`).
 *
 * Sólo los repositorios y el seed usan estas funciones: la UI nunca ve una entidad
 * de Room ni un id suelto. Las columnas nullable se normalizan acá (`notes`/`createdAt`
 * vacíos se guardan como NULL y vuelven como `""`), así el dominio tiene un único
 * valor para "sin dato".
 */

// --- músculos ---------------------------------------------------------------------

internal fun MuscleEntity.toDomain(): Muscle = Muscle(
    id = id,
    name = name,
    position = position,
    archived = archived,
)

internal fun Muscle.toEntity(): MuscleEntity = MuscleEntity(
    id = id,
    name = name,
    position = position,
    archived = archived,
)

// --- ejercicios -------------------------------------------------------------------

internal fun ExerciseSecondaryEntity.toDomain(): Secondary = Secondary(muscleId = muscleId, factor = factor)

internal fun Secondary.toEntity(exerciseId: String): ExerciseSecondaryEntity =
    ExerciseSecondaryEntity(exerciseId = exerciseId, muscleId = muscleId, factor = factor)

internal fun ExerciseEntity.toDomain(secondaries: List<ExerciseSecondaryEntity>): Exercise = Exercise(
    id = id,
    name = name,
    primaryMuscleId = primaryMuscleId,
    secondaries = secondaries.map { it.toDomain() },
    secondsPerSet = secondsPerSet,
    restSeconds = restSeconds,
    compound = compound,
    loadType = loadType,
    position = position,
    archived = archived,
)

internal fun Exercise.toEntity(): ExerciseEntity = ExerciseEntity(
    id = id,
    name = name,
    primaryMuscleId = primaryMuscleId,
    secondsPerSet = secondsPerSet,
    restSeconds = restSeconds,
    compound = compound,
    loadType = loadType,
    position = position,
    archived = archived,
)

// --- planes -----------------------------------------------------------------------

internal fun RoutineEntity.toDomain(days: List<RoutineDay>): Routine = Routine(
    id = id,
    name = name,
    days = days,
    // §2 no persiste `activeDay`: la rutina/día activo lo decide la UI (GIM-16).
    activeDayId = null,
    notes = notes.orEmpty(),
    position = position,
    createdAt = createdAt.orEmpty(),
    archived = archived,
)

internal fun Routine.toEntity(): RoutineEntity = RoutineEntity(
    id = id,
    name = name,
    notes = notes.ifBlank { null },
    position = position,
    createdAt = createdAt.ifBlank { null },
    archived = archived,
)

internal fun RoutineDayEntity.toDomain(items: List<DayItem>): RoutineDay =
    RoutineDay(id = id, name = name, items = items)

internal fun RoutineDay.toEntity(routineId: String, position: Int): RoutineDayEntity = RoutineDayEntity(
    id = id,
    routineId = routineId,
    name = name,
    position = position,
)

internal fun DayItemEntity.toDomain(exercise: Exercise): DayItem = DayItem(
    id = id,
    exercise = exercise,
    sets = sets,
    dropSet = dropSet,
    supersetNext = supersetNext,
    restOverrideSeconds = restOverrideSeconds,
    targetRepsMin = targetRepsMin,
    targetRepsMax = targetRepsMax,
)

internal fun DayItem.toEntity(dayId: String, position: Int): DayItemEntity = DayItemEntity(
    id = id,
    dayId = dayId,
    exerciseId = exercise.id,
    position = position,
    sets = sets,
    dropSet = dropSet,
    supersetNext = supersetNext,
    restOverrideSeconds = restOverrideSeconds,
    targetRepsMin = targetRepsMin,
    targetRepsMax = targetRepsMax,
)
