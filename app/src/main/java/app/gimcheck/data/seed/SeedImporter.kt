package app.gimcheck.data.seed

import app.gimcheck.core.DomainSnapshot
import app.gimcheck.core.importWeb
import app.gimcheck.core.valueOrThrow
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.repository.toEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Carga inicial de datos: el seed de `assets/seed.json` la primera vez que la app
 * arranca **sin datos**.
 *
 * El seed es el JSON de la web (`localStorage["rutinas-gym-v1"]`) recortado a la
 * Rutina A, generado con `scripts/gen-seed.py` desde el mismo estado que validan los
 * tests de `:core` (`docs/paridad-web.md`). Por eso no hay un parser propio: se usa
 * [importWeb], el importador ya probado contra el HTML, y su resultado
 * ([DomainSnapshot]) se escribe con las operaciones validadas de los DAO.
 *
 * **Idempotente**: si ya hay un plan o un músculo cargado no escribe nada. Es la regla
 * que hace seguro llamarlo en cada arranque (y después de un restore, que deja datos).
 *
 * **No escribe ajustes**: los defaults de §8 son los mismos que los del seed de la
 * web (`warm 5`, `trans 30`, …), y [app.gimcheck.data.repository.SettingsRepository]
 * los aplica sin filas. El test del seed verifica esa igualdad, así una divergencia
 * entre la web y §8 no pasa inadvertida.
 */
class SeedImporter(
    private val db: AppDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Importa [seedJson] si la base está vacía.
     *
     * @return `true` si escribió el seed, `false` si ya había datos.
     * @throws IllegalArgumentException si el JSON del seed no es un backup de la web
     *   válido (`importWeb` devuelve un `ImportError`), o si el seed tiene un `sets`
     *   fuera de 1..20.
     */
    suspend fun seedIfEmpty(seedJson: String): Boolean = withContext(io) {
        if (hasData()) return@withContext false
        val snapshot = importWeb(seedJson).valueOrThrow().snapshot
        write(snapshot)
        true
    }

    private suspend fun hasData(): Boolean =
        db.routineDao().count() > 0 || db.muscleDao().countActive() > 0

    private suspend fun write(snapshot: DomainSnapshot) {
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
