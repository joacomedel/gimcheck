package app.gimcheck.data.db

import androidx.room.TypeConverter
import app.gimcheck.core.LoadType

/**
 * Convertidores de enums a TEXT ([LoadType] de `:core` y [SessionStatus] local).
 *
 * Se guarda el **nombre** (`"EXTERNAL"`, `"ACTIVE"`) y no el ordinal: renombrar o
 * reordenar un enum no puede cambiar el significado de las filas ya escritas.
 */
class Converters {

    @TypeConverter
    fun loadTypeToString(value: LoadType): String = value.name

    @TypeConverter
    fun stringToLoadType(value: String): LoadType = LoadType.valueOf(value)

    @TypeConverter
    fun sessionStatusToString(value: SessionStatus): String = value.name

    @TypeConverter
    fun stringToSessionStatus(value: String): SessionStatus = SessionStatus.valueOf(value)
}
