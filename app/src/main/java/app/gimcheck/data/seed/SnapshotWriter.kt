package app.gimcheck.data.seed

import app.gimcheck.core.DomainSnapshot
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.repository.toEntity

/**
 * Escritura de un [DomainSnapshot] completo (músculos, ejercicios, planes, días e
 * ítems) con las operaciones validadas de los DAO.
 *
 * Existe porque hay **dos** caminos que cargan un snapshot: el seed del primer
 * arranque ([SeedImporter]) y el import de un backup de la web desde Ajustes
 * ([app.gimcheck.data.transfer.WebBackupImporter], GIM-45). Duplicar el orden de
 * escritura era la forma más fácil de que uno de los dos se rompiera en silencio.
 *
 * El llamador decide el borrado previo y la transacción: acá sólo se escribe.
 */
internal class SnapshotWriter(private val db: AppDatabase) {

    suspend fun write(snapshot: DomainSnapshot) {
        // Orden por las claves foráneas: músculos → ejercicios → planes → días → ítems.
        snapshot.muscles.forEach { muscle -> db.muscleDao().upsert(muscle.toEntity()) }
        snapshot.exercises.forEachIndexed { index, exercise ->
            // `position` conserva el orden del catálogo de la web; el import lo deja
            // en 0 porque el dominio no lo usa para calcular.
            db.exerciseDao().upsertWithSecondaries(
                exercise = exercise.copy(position = index).toEntity(),
                secondaries = exercise.secondaries.map { it.toEntity(exercise.id) },
            )
        }
        snapshot.routines.forEach { routine ->
            db.routineDao().upsertWithDays(
                routine = routine.toEntity(),
                days = routine.days.mapIndexed { index, day -> day.toEntity(routine.id, index) },
            )
            routine.days.forEach { day ->
                db.dayDao().replaceItems(
                    dayId = day.id,
                    items = day.items.mapIndexed { index, item -> item.toEntity(day.id, index) },
                )
            }
        }
    }
}
