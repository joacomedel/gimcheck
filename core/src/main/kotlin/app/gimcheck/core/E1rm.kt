package app.gimcheck.core

/**
 * PR estimado (e1RM) por serie — `docs/especificacion.md` §3.1.
 *
 * Kotlin puro: no redondea. El redondeo es de presentación (`formatNumber`) y el
 * valor que se compara y se guarda es el `Double` completo, así los empates no
 * dependen de cómo se muestre el número.
 */

/** Fórmulas de e1RM soportadas. La elegida se guarda en `Settings.e1rmFormula`. */
enum class E1rmFormula(val label: String) {
    /** `w * (1 + reps/30)`. Default de la app. */
    EPLEY("Epley"),

    /** `w * 36 / (37 - reps)`. */
    BRZYCKI("Brzycki"),
}

/**
 * Reps máximas para estimar: arriba de 12 la fórmula deja de ser fiable y la web
 * (y §3.1) no genera e1RM. La serie igual cuenta para volumen.
 */
const val MAX_E1RM_REPS: Int = 12

/**
 * e1RM de una serie, o `null` si la serie no permite estimarlo.
 *
 * Descarta (`null`, nunca 0 — un 0 se confundiría con "peso cero"):
 *  - `weightKg <= 0` (incluye bodyweight puro y lastre negativo)
 *  - `reps < 1` o `reps > 12`
 *  - `weightKg` NaN o infinito
 *
 * @param formula Epley por defecto; Brzycki cuando el ajuste lo pide.
 */
fun e1rm(
    weightKg: Double,
    reps: Int,
    formula: E1rmFormula = E1rmFormula.EPLEY,
): Double? {
    if (weightKg.isNaN() || weightKg.isInfinite()) return null
    if (weightKg <= 0.0) return null
    if (reps < 1 || reps > MAX_E1RM_REPS) return null
    return when (formula) {
        E1rmFormula.EPLEY -> weightKg * (1.0 + reps / 30.0)
        E1rmFormula.BRZYCKI -> weightKg * 36.0 / (37.0 - reps)
    }
}

/**
 * e1RM de una serie anotada. Las de calentamiento no generan e1RM (§3.1 y §4:
 * "series de calentamiento no cuentan para volumen ni PR").
 */
fun e1rm(set: SessionSet, formula: E1rmFormula): Double? =
    if (set.isWarmup) null else e1rm(set.weightKg, set.reps, formula)

/** Igual que arriba usando la fórmula configurada en los ajustes. */
fun e1rm(set: SessionSet, settings: Settings): Double? =
    e1rm(set, settings.e1rmFormula)
