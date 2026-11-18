package app.gimcheck.data.repository

import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.AppSettingEntity
import app.gimcheck.data.settings.AppSettings
import app.gimcheck.data.settings.AppSettingsCodec
import app.gimcheck.data.settings.SettingKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Ajustes de `docs/especificacion.md` §8, tipados.
 *
 * `app_setting` es key/value de texto (una fila por clave, ADR 0003). Este repositorio
 * es el único que conoce esa representación: afuera todo se habla con [AppSettings].
 *
 * **Emisión inmediata**: [settings] es la `Flow` de la tabla mapeada, así que cualquier
 * cambio hecho con [update] o [set] se ve al instante en la UI. No hace falta reiniciar
 * la app ni volver a leer: Room invalida `app_setting` y la `Flow` vuelve a emitir.
 *
 * **Sin caché**: cada escritura lee el estado actual de la base (una consulta chica y
 * consistente) en lugar de mantener un espejo en memoria que se puede desincronizar
 * con otro proceso o con un restore de backup.
 */
class SettingsRepository internal constructor(
    db: AppDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val dao = db.settingDao()

    /** Todos los ajustes, con los defaults de §8 para lo que todavía no se guardó. */
    val settings: Flow<AppSettings> =
        dao.observeAll().map { rows -> AppSettingsCodec.decode(rows.associate { it.key to it.value }) }

    /**
     * Id del plan activo (GIM-16), o `null` si todavía no se eligió ninguno. Es una fila
     * suelta de `app_setting` ([SettingKeys.ACTIVE_ROUTINE]) que **no** forma parte de
     * [AppSettings]: cambiarla no reescribe los ajustes ni dispara un guardado completo.
     */
    val activeRoutineId: Flow<String?> = dao.observeAll().map { rows ->
        rows.firstOrNull { it.key == SettingKeys.ACTIVE_ROUTINE }?.value?.takeIf { it.isNotBlank() }
    }

    /** Lectura puntual del estado actual (no reactiva). */
    suspend fun current(): AppSettings = withContext(io) { read() }

    /**
     * Aplica [block] sobre el estado actual y guarda el resultado.
     *
     * @return los ajustes ya persistidos, para encadenar o para tests.
     */
    suspend fun update(block: (AppSettings) -> AppSettings): AppSettings = withContext(io) {
        val updated = block(read())
        write(updated)
        updated
    }

    /** Reemplaza todos los ajustes de una vez (restore de backup, "volver a defaults"). */
    suspend fun set(settings: AppSettings): AppSettings = withContext(io) {
        write(settings)
        settings
    }

    /** Vuelve a los defaults de §8. */
    suspend fun reset(): AppSettings = set(AppSettings.DEFAULT).also {
        withContext(io) { dao.delete(SettingKeys.ACTIVE_ROUTINE) }
    }

    /**
     * Marca el plan activo. `null` borra la fila: "sin plan activo" es la ausencia de
     * valor, no la cadena vacía.
     */
    suspend fun setActiveRoutine(id: String?) = withContext(io) {
        if (id == null) {
            dao.delete(SettingKeys.ACTIVE_ROUTINE)
        } else {
            dao.put(AppSettingEntity(key = SettingKeys.ACTIVE_ROUTINE, value = id))
        }
    }

    private suspend fun read(): AppSettings =
        AppSettingsCodec.decode(dao.all().associate { it.key to it.value })

    private suspend fun write(settings: AppSettings) {
        dao.putAll(
            AppSettingsCodec.encode(settings).map { (key, value) -> AppSettingEntity(key = key, value = value) },
        )
    }
}
