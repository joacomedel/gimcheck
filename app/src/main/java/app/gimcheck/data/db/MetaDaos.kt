package app.gimcheck.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Métricas corporales por fecha (§2 `body_metric`). */
@Dao
interface BodyMetricDao {

    @Query("SELECT * FROM body_metric ORDER BY date DESC")
    fun observeAll(): Flow<List<BodyMetricEntity>>

    @Query("SELECT * FROM body_metric WHERE date = :date")
    suspend fun getByDate(date: String): BodyMetricEntity?

    @Upsert
    suspend fun upsert(metric: BodyMetricEntity)

    @Query("DELETE FROM body_metric WHERE date = :date")
    suspend fun delete(date: String)
}

/**
 * Ajustes globales key/value (§2 `app_setting`).
 *
 * `value` es el JSON del ajuste; el tipado lo pone el repositorio (GIM-10). No
 * hay DataStore: ver `docs/adr/0003-persistencia-room.md`.
 */
@Dao
interface SettingDao {

    @Query("SELECT `value` FROM app_setting WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM app_setting ORDER BY `key`")
    fun observeAll(): Flow<List<AppSettingEntity>>

    @Upsert
    suspend fun put(setting: AppSettingEntity)

    @Query("DELETE FROM app_setting WHERE `key` = :key")
    suspend fun delete(key: String)
}
