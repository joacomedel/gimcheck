package app.gimcheck.core

/**
 * Identidad del módulo de dominio. Es lo mínimo que `:core` expone en el esqueleto:
 * sirve para que el pipeline tenga código real que compilar y testear.
 * El modelo, las fórmulas y la máquina de estados llegan en GIM-4..GIM-8.
 */
object CoreInfo {
    const val MODULE: String = "gimcheck-core"
    const val VERSION: String = "0.1.0"

    fun describe(): String = "GimCheck core $VERSION"
}
