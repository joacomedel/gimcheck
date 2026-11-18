package app.gimcheck

import android.content.Context
import android.util.Log
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.repository.ExerciseRepository
import app.gimcheck.data.repository.MetricsRepository
import app.gimcheck.data.repository.RoutineRepository
import app.gimcheck.data.repository.SessionRepository
import app.gimcheck.data.repository.SettingsRepository
import app.gimcheck.data.seed.SeedImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Inyección de dependencias **manual**: no hay Hilt ni Koin (ADR 0003).
 *
 * Un solo contenedor, creado en [GimCheckApp], con la base y los cinco repositorios.
 * Es explícito y barato: el grafo son seis objetos y no cambia en runtime. Los tests
 * no usan el contenedor: construyen sus repositorios sobre una base en memoria, que es
 * lo mismo que hace acá con la base del dispositivo.
 *
 * El alcance [background] es de la app (no de una pantalla): el seed tiene que terminar
 * aunque la activity se recree. Es `SupervisorJob` para que un fallo de fondo no cancele
 * el resto.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    private val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** La base del dispositivo (`gimcheck.db`, esquema v1). `internal`: nadie la pide. */
    internal val database: AppDatabase = AppDatabase.build(appContext)

    val exercises: ExerciseRepository = ExerciseRepository(database)
    val routines: RoutineRepository = RoutineRepository(database)
    val sessions: SessionRepository = SessionRepository(database)
    val metrics: MetricsRepository = MetricsRepository(database)
    val settings: SettingsRepository = SettingsRepository(database)

    private val seedImporter: SeedImporter = SeedImporter(database)

    /** Dispara el seed en segundo plano; no bloquea el arranque ni la UI. */
    fun seedIfEmptyAsync() {
        background.launch { seedIfEmpty() }
    }

    /**
     * Carga el seed si la base está vacía.
     *
     * @return `true` si escribió datos.
     */
    suspend fun seedIfEmpty(): Boolean = try {
        seedImporter.seedIfEmpty(readSeed())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        // Un seed roto no puede impedir usar la app: se registra y se sigue con la base
        // vacía (el usuario puede cargar sus datos o importar un backup).
        Log.e(TAG, "no se pudo cargar el seed de $SEED_ASSET", error)
        false
    }

    private fun readSeed(): String =
        appContext.assets.open(SEED_ASSET).bufferedReader().use { it.readText() }

    companion object {
        /** Asset con el seed generado por `scripts/gen-seed.py`. */
        const val SEED_ASSET: String = "seed.json"

        private const val TAG: String = "GimCheck"
    }
}
