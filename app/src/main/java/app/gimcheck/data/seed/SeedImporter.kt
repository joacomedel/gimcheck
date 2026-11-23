package app.gimcheck.data.seed

import app.gimcheck.core.importWeb
import app.gimcheck.core.valueOrThrow
import app.gimcheck.data.db.AppDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Carga inicial de datos: el seed de `assets/seed.json` la primera vez que la app
 * arranca **sin datos**.
 *
 * El seed es el backup real del dueño (`owner_backup_v21.json`, formato de la web
 * `localStorage["rutinas-gym-v1"]`): **sus dos rutinas** (A y B), 15 músculos y 32
 * ejercicios. Lo genera `scripts/gen-seed.py` copiando ese archivo, que es el mismo
 * que validan los tests de `:core` (`docs/paridad-web.md`). Por eso no hay un parser
 * propio: se usa [importWeb], el importador ya probado contra el HTML, y su resultado
 * (`DomainSnapshot`) se escribe con las operaciones validadas de los DAO.
 *
 * **Idempotente**: si ya hay un plan o un músculo cargado no escribe nada. Es la regla
 * que hace seguro llamarlo en cada arranque (y después de un restore, que deja datos).
 *
 * **No escribe ajustes**: los defaults de §8 son los mismos que los del seed de la
 * web (`warm 5`, `trans 30`, …), y [app.gimcheck.data.repository.SettingsRepository]
 * los aplica sin filas. El test del seed verifica esa igualdad, así una divergencia
 * entre la web y §8 no pasa inadvertida.
 */
class SeedImporter internal constructor(
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
        SnapshotWriter(db).write(snapshot)
        true
    }

    private suspend fun hasData(): Boolean =
        db.routineDao().count() > 0 || db.muscleDao().countActive() > 0
}
