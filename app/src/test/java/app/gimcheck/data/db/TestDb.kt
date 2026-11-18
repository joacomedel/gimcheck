package app.gimcheck.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider

/*
 * Utilidades compartidas por los tests de Room. Todo corre en la JVM con
 * Robolectric: no hay emulador ni `connectedAndroidTest` en este proyecto
 * (`docs/build.md`).
 */

internal const val ROUTINE_ID = "routine-1"
internal const val DAY_ID = "day-1"

/** Base en memoria: sin archivo, se descarta al cerrar. */
internal fun inMemoryDatabase(): AppDatabase =
    Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        AppDatabase::class.java,
    ).build()

internal fun muscle(id: String, name: String = id): MuscleEntity =
    MuscleEntity(id = id, name = name)

internal fun exercise(
    id: String,
    primaryMuscleId: String = "m1",
    secondsPerSet: Int = 40,
    restSeconds: Int = 90,
): ExerciseEntity = ExerciseEntity(
    id = id,
    name = id,
    primaryMuscleId = primaryMuscleId,
    secondsPerSet = secondsPerSet,
    restSeconds = restSeconds,
)

internal fun dayItem(
    id: String,
    exerciseId: String,
    sets: Int = 3,
    dayId: String = DAY_ID,
): DayItemEntity = DayItemEntity(id = id, dayId = dayId, exerciseId = exerciseId, sets = sets)

/**
 * Plan de prueba: 3 músculos, 3 ejercicios y 1 rutina con 1 día de 3 ítems
 * `i1 → i2` en superserie y `i3` suelto (el último del día).
 */
internal suspend fun AppDatabase.seedPlan() {
    muscleDao().upsert(muscle("m1", "Pecho"))
    muscleDao().upsert(muscle("m2", "Espalda"))
    muscleDao().upsert(muscle("m3", "Tríceps"))
    exerciseDao().upsertExercise(exercise("e1"))
    exerciseDao().upsertExercise(exercise("e2"))
    exerciseDao().upsertExercise(exercise("e3"))
    routineDao().upsertWithDays(
        RoutineEntity(id = ROUTINE_ID, name = "Rutina A"),
        listOf(RoutineDayEntity(id = DAY_ID, routineId = ROUTINE_ID, name = "Día 1")),
    )
    dayDao().addItem(dayItem("i1", "e1"))
    dayDao().addItem(dayItem("i2", "e2"))
    dayDao().addItem(dayItem("i3", "e3"))
    dayDao().setSupersetNext("i1", true)
    dayDao().setSupersetNext("i2", true)
}
