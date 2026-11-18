package app.gimcheck.data.repository

import app.gimcheck.core.BodyMetric
import app.gimcheck.data.Instants
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.BodyMetricEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import java.time.ZoneId

/**
 * Peso corporal y grasa por fecha (`body_metric`, §2). La fecha es la clave natural
 * (`YYYY-MM-DD` en la zona del usuario), igual que en la web.
 *
 * **Fecha de actualización (deuda de GIM-9)**: `body_metric.updated_at` está en
 * epoch millis y `:core` lo maneja como `String` ISO-8601
 * ([BodyMetric.updatedAt]). La conversión vive en [Instants] y en este borde: un
 * texto vacío se guarda como `0` ("sin fecha") y vuelve como `1970-01-01T00:00`, que
 * ordena antes que cualquier fecha real en el last-write-wins del backup.
 */
class MetricsRepository internal constructor(
    db: AppDatabase,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val dao = db.bodyMetricDao()

    /** Todas las métricas, la más reciente primero. */
    fun observeAll(): Flow<List<BodyMetric>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** La última métrica cargada, o `null` si todavía no hay ninguna. */
    fun observeLatest(): Flow<BodyMetric?> =
        dao.observeAll().map { rows -> rows.firstOrNull()?.toDomain() }

    suspend fun get(date: LocalDate): BodyMetric? = withContext(io) {
        dao.getByDate(date.toString())?.toDomain()
    }

    suspend fun save(metric: BodyMetric) = withContext(io) {
        dao.upsert(metric.toEntity())
    }

    suspend fun delete(date: LocalDate) = withContext(io) {
        dao.delete(date.toString())
    }

    private fun BodyMetricEntity.toDomain(): BodyMetric = BodyMetric(
        date = LocalDate.parse(date),
        weightKg = weightKg,
        bodyFatPct = bodyFatPct,
        note = note.orEmpty(),
        updatedAt = Instants.epochMillisToIso(updatedAt, zone),
    )

    private fun BodyMetric.toEntity(): BodyMetricEntity = BodyMetricEntity(
        date = date.toString(),
        weightKg = weightKg,
        bodyFatPct = bodyFatPct,
        note = note.ifBlank { null },
        updatedAt = Instants.isoToEpochMillis(updatedAt, zone),
    )
}
