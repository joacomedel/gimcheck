package app.gimcheck.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Import / export — errores y utilidades de JSON (`docs/especificacion.md` §7).
 *
 * Todas las entradas de import son **funciones puras**: devuelven
 * [ImportResult] y no escriben nada. Si algo falla, el resultado es
 * [ImportResult.Failure] con un [ImportError] tipado y el llamador no tiene nada
 * que deshacer. La escritura a base es de otro ticket (repositorio, GIM-10).
 *
 * Los errores nombran la ruta del campo (`plans[1].days[0].items[3].sets`) para que
 * el mensaje que ve el usuario sirva para arreglar el archivo, no para adivinar.
 */
sealed class ImportError {
    abstract val message: String

    /** El texto no es JSON válido. */
    data class NotJson(override val message: String) : ImportError()

    /** El JSON es válido pero la raíz no es un objeto (o la forma no es la esperada). */
    data class NotAnObject(override val message: String) : ImportError()

    /** Falta un campo obligatorio. */
    data class MissingField(val path: String) : ImportError() {
        override val message: String get() = "falta el campo obligatorio $path"
    }

    /** El campo existe con otro tipo. */
    data class WrongType(val path: String, val expected: String) : ImportError() {
        override val message: String get() = "$path: se esperaba $expected"
    }

    /** Versión de esquema desconocida: no se inventa una migración al aire. */
    data class UnsupportedVersion(
        val schema: String,
        val version: Int?,
        val detail: String = "",
    ) : ImportError() {
        override val message: String
            get() = buildString {
                append("versión no soportada de $schema")
                if (version != null) append(": $version")
                if (detail.isNotEmpty()) append(" ($detail)")
            }
    }

    /** Dato con forma correcta pero contenido inválido (id repetido, referencia rota, fecha). */
    data class InvalidValue(val path: String, val detail: String) : ImportError() {
        override val message: String get() = "$path: $detail"
    }
}

/** Resultado de un import: o el valor, o un error tipado. Nunca excepción. */
sealed interface ImportResult<out T> {
    data class Success<T>(val value: T) : ImportResult<T>
    data class Failure(val error: ImportError) : ImportResult<Nothing>
}

fun <T> ImportResult<T>.valueOrNull(): T? = (this as? ImportResult.Success)?.value

fun <T> ImportResult<T>.errorOrNull(): ImportError? = (this as? ImportResult.Failure)?.error

fun <T> ImportResult<T>.valueOrThrow(): T = when (this) {
    is ImportResult.Success -> value
    is ImportResult.Failure -> throw IllegalArgumentException(error.message)
}

inline fun <T, R> ImportResult<T>.map(transform: (T) -> R): ImportResult<R> = when (this) {
    is ImportResult.Success -> ImportResult.Success(transform(value))
    is ImportResult.Failure -> this
}

inline fun <T, R> ImportResult<T>.flatMap(transform: (T) -> ImportResult<R>): ImportResult<R> =
    when (this) {
        is ImportResult.Success -> transform(value)
        is ImportResult.Failure -> this
    }

// --- parseo estricto con ruta -------------------------------------------------

/** Excepción interna: se traduce a [ImportError] en [parsing]. No sale de esta capa. */
internal class ImportException(val error: ImportError) : RuntimeException(error.message)

internal inline fun <T> parsing(block: () -> T): ImportResult<T> = try {
    ImportResult.Success(block())
} catch (e: ImportException) {
    ImportResult.Failure(e.error)
} catch (e: SerializationException) {
    ImportResult.Failure(ImportError.NotJson(e.message ?: "JSON inválido"))
} catch (e: IllegalArgumentException) {
    // p. ej. `LocalDate.parse` con una fecha rota
    ImportResult.Failure(ImportError.InvalidValue("", e.message ?: "valor inválido"))
}

internal fun fail(path: String, expected: String): Nothing =
    throw ImportException(ImportError.WrongType(path, expected))

internal fun missing(path: String): Nothing = throw ImportException(ImportError.MissingField(path))

internal fun invalid(path: String, detail: String): Nothing =
    throw ImportException(ImportError.InvalidValue(path, detail))

internal fun JsonElement.asObject(path: String): JsonObject = when (this) {
    is JsonObject -> this
    else -> fail(path, "un objeto")
}

internal fun JsonElement.asArray(path: String): JsonArray = when (this) {
    is JsonArray -> this
    else -> fail(path, "una lista")
}

internal fun JsonObject.required(key: String, path: String): JsonElement =
    this[key] ?: missing(if (path.isEmpty()) key else "$path.$key")

internal fun JsonObject.optional(key: String): JsonElement? =
    this[key]?.takeIf { it !is JsonNull }

internal fun JsonElement.asString(path: String): String {
    val primitive = this as? JsonPrimitive ?: fail(path, "un texto")
    if (primitive is JsonNull) fail(path, "un texto")
    return primitive.content
}

/** Número con tolerancia: acepta `3` y `"3"` (algunos backups viejos guardaron texto). */
internal fun JsonElement.asDouble(path: String): Double {
    val primitive = this as? JsonPrimitive ?: fail(path, "un número")
    if (primitive is JsonNull) fail(path, "un número")
    return primitive.content.trim().toDoubleOrNull() ?: fail(path, "un número")
}

internal fun JsonElement.asInt(path: String): Int {
    val value = asDouble(path)
    if (value % 1.0 != 0.0) invalid(path, "se esperaba un entero y vino $value")
    if (value > Int.MAX_VALUE || value < Int.MIN_VALUE) invalid(path, "fuera de rango: $value")
    return value.toInt()
}

internal fun JsonElement.asBoolean(path: String): Boolean {
    val primitive = this as? JsonPrimitive ?: fail(path, "un booleano")
    if (primitive is JsonNull) fail(path, "un booleano")
    return primitive.booleanOrNull
        ?: primitive.content.toBooleanStrictOrNull()
        ?: fail(path, "un booleano")
}
