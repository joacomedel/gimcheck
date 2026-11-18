package app.gimcheck.data

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import java.time.ZoneId

/**
 * Conversión de instantes en el **borde** de la persistencia (deuda heredada de la
 * auditoría de GIM-9).
 *
 * Por qué existe: `docs/adr/0003-persistencia-room.md` guarda los instantes como
 * `INTEGER` epoch millis UTC (`session.started_at`, `session.ended_at`,
 * `session_set.completed_at`, `body_metric.updated_at`, `session_state.*`), mientras
 * que el dominio de `:core` trabaja con `LocalDateTime` (hora local, sin zona) y con
 * `String` ISO-8601. La conversión vive acá y **sólo acá**: ni el dominio ni los DAO
 * saben de epoch millis, y ninguna pantalla tiene que acordarse de multiplicar por
 * 1000.
 *
 * La zona es un parámetro (default `ZoneId.systemDefault()` en los repositorios) para
 * que los tests usen una zona fija y la conversión sea verificable: ida y vuelta con
 * el mismo `ZoneId` devuelve el mismo `LocalDateTime`, incluso en un día con cambio de
 * horario (el ida y vuelta usa el mismo offset en ambos sentidos).
 */
object Instants {

    /** Hora local → epoch millis UTC, interpretando la hora en [zone]. */
    fun toEpochMillis(value: LocalDateTime, zone: ZoneId): Long =
        value.toJavaLocalDateTime().atZone(zone).toInstant().toEpochMilli()

    /** epoch millis UTC → hora local en [zone]. */
    fun fromEpochMillis(millis: Long, zone: ZoneId): LocalDateTime =
        java.time.Instant.ofEpochMilli(millis)
            .atZone(zone)
            .toLocalDateTime()
            .toKotlinLocalDateTime()

    /**
     * ISO-8601 local (el `String` que usa `:core`, p. ej. `BodyMetric.updatedAt`) →
     * epoch millis. Un texto vacío es "sin fecha" y se guarda como `0` (1970-01-01),
     * que ordena antes que cualquier fecha real en el last-write-wins del backup.
     */
    fun isoToEpochMillis(iso: String, zone: ZoneId): Long =
        if (iso.isBlank()) 0L else toEpochMillis(LocalDateTime.parse(iso), zone)

    /** epoch millis → ISO-8601 local en [zone]. `0` vuelve como `1970-01-01T00:00`. */
    fun epochMillisToIso(millis: Long, zone: ZoneId): String =
        fromEpochMillis(millis, zone).toString()
}
