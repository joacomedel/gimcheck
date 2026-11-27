package app.gimcheck.ui.chart

import app.gimcheck.core.formatNumber
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.round
import kotlin.math.pow

/**
 * Escalas de los gráficos (§6 y ADR 0005): dominio → fracción 0..1 y ticks "lindos".
 *
 * Vive separado de los composables para poder probarlo en la JVM. Un gráfico que
 * empieza en 0 cuando todos los PR pesan entre 110 y 130 kg desperdicia el 80 % del
 * alto; uno que recorta el eje miente. [niceAxis] busca el punto medio: un mínimo y un
 * máximo redondos que contienen todos los datos con un 10 % de aire.
 */
data class AxisScale(
    val min: Double,
    val max: Double,
    val mid: Double,
) {
    /** Posición de [value] dentro del eje, 0 = abajo, 1 = arriba. */
    fun fraction(value: Double): Float {
        if (max <= min) return 0.5f
        return ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * Eje vertical para [values].
 *
 * @param includeZero fuerza que el 0 esté dentro del eje (barras de volumen).
 * @param padding fracción del rango que se agrega arriba y abajo (5 % por lado).
 * @param empty lo que devuelve sin datos: un eje 0..1 para que el Canvas no divida por 0.
 */
fun niceAxis(
    values: List<Double>,
    includeZero: Boolean = false,
    padding: Double = 0.10,
): AxisScale {
    if (values.isEmpty()) return AxisScale(0.0, 1.0, 0.5)
    var lo = values.min()
    var hi = values.max()
    if (includeZero) lo = minOf(0.0, lo)
    if (hi - lo < 1e-9) {
        // Un solo valor (o todos iguales): abrimos el eje alrededor, no lo dejamos plano.
        val delta = if (abs(hi) < 1e-9) 1.0 else abs(hi) * 0.1
        lo -= delta
        hi += delta
    }
    val pad = (hi - lo) * padding
    lo -= pad
    hi += pad
    if (includeZero) lo = minOf(0.0, lo)
    val step = niceStep((hi - lo) / 2.0)
    // Con `includeZero` el cero es el piso del eje: un mínimo negativo haría que las
    // barras de volumen arrancaran abajo de la línea base y el gráfico mintiera.
    val niceLo = if (includeZero) 0.0 else floor(lo / step) * step
    val niceHi = ceil(hi / step) * step
    return AxisScale(niceLo, niceHi, (niceLo + niceHi) / 2.0)
}

/** Paso "lindo" (1, 2 o 5 × 10^n) para que los ticks no sean 3,7 ni 13,333. */
fun niceStep(raw: Double): Double {
    if (raw <= 0.0 || raw.isNaN() || raw.isInfinite()) return 1.0
    val exponent = floor(log10(raw))
    val base = 10.0.pow(exponent)
    val fraction = raw / base
    val nice = when {
        fraction <= 1.0 -> 1.0
        fraction <= 2.0 -> 2.0
        fraction <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * base
}

/**
 * Tick de un eje: como máximo un decimal, con coma y sin ceros de relleno (`82,5`).
 *
 * El contrato prohíbe más de un decimal en los ejes; `formatNumber` recorta a dos y
 * acá se redondea antes a uno.
 */
fun formatTick(value: Double): String = formatNumber(round(value * 10.0) / 10.0)

/** Valor compacto para volúmenes: `12,4 k` en vez de `12400`. */
fun formatCompact(value: Double): String {
    if (abs(value) < 1000.0) return formatTick(value)
    val thousands = value / 1000.0
    return formatTick(round(thousands * 10.0) / 10.0) + " k"
}

/** Distancia horizontal entre puntos de un gráfico de línea con [count] puntos. */
fun xFraction(index: Int, count: Int): Float = when {
    count <= 1 -> 0.5f
    else -> index.toFloat() / (count - 1).toFloat()
}
