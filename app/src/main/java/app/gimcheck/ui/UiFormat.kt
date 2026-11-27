package app.gimcheck.ui

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Fechas y horas en castellano para las pantallas de datos (Historial y Progreso).
 *
 * El dominio (`:core`) no formatea fechas: trabaja con `LocalDate`/`LocalDateTime`.
 * Estas funciones son el único lugar de la UI que decide que un día es `18 nov` y no
 * `11/18`; si el copy de §12 cambia, se cambia acá y no en cada fila.
 */
private val WEEKDAYS = listOf(
    "lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo",
)
private val WEEKDAYS_SHORT = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")
private val MONTHS = listOf(
    "enero", "febrero", "marzo", "abril", "mayo", "junio",
    "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
)
private val MONTHS_SHORT = listOf(
    "ene", "feb", "mar", "abr", "may", "jun",
    "jul", "ago", "sep", "oct", "nov", "dic",
)

/** `18 nov` — filas de listas. */
fun shortDate(date: LocalDate): String =
    "${date.dayOfMonth} ${MONTHS_SHORT[date.monthNumber - 1]}"

/** `mié 18 nov` — fila de sesión: el día de la semana ayuda a ubicarse. */
fun weekdayDate(date: LocalDate): String =
    "${WEEKDAYS_SHORT[date.dayOfWeek.isoDayNumber - 1]} ${shortDate(date)}"

/** `miércoles 18 de noviembre` — encabezado de detalle. */
fun longDate(date: LocalDate): String =
    "${WEEKDAYS[date.dayOfWeek.isoDayNumber - 1]} ${date.dayOfMonth} de ${MONTHS[date.monthNumber - 1]}"

/** `18 nov 2026` — cuando el año importa (rango "Todo"). */
fun shortDateYear(date: LocalDate): String = "${shortDate(date)} ${date.year}"

/** `nov 2026` — eje de tiempo del gráfico de peso. */
fun monthYear(date: LocalDate): String = "${MONTHS_SHORT[date.monthNumber - 1]} ${date.year}"

/** Lunes de la semana ISO de [date] (0 = lunes). */
fun mondayOf(date: LocalDate): LocalDate =
    date.minus(DatePeriod(days = date.dayOfWeek.isoDayNumber - 1))

/** `18–24 nov`: el lunes y el domingo de la semana ISO de [date]. */
fun weekRangeLabel(date: LocalDate): String {
    val monday = mondayOf(date)
    val sunday = monday.plus(DatePeriod(days = 6))
    return if (monday.monthNumber == sunday.monthNumber) {
        "${monday.dayOfMonth}–${sunday.dayOfMonth} ${MONTHS_SHORT[sunday.monthNumber - 1]}"
    } else {
        "${shortDate(monday)} – ${shortDate(sunday)}"
    }
}

/** `18 nov – 24 nov`: rango entre dos fechas de distinto mes o año. */
fun rangeLabel(from: LocalDate, to: LocalDate): String = "${shortDate(from)} – ${shortDate(to)}"

/** `10:05` — hora de la serie corregida. */
fun timeLabel(hour: Int, minute: Int): String =
    "$hour:${minute.toString().padStart(2, '0')}"
