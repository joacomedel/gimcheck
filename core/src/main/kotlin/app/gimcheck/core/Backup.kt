package app.gimcheck.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Backup completo de la app — `docs/especificacion.md` §7.
 *
 * Contrato: `{schema: "gimcheck", version: 1, exportedAt, settings, muscles,
 * exercises, routines, sessions, bodyMetrics}`. El JSON es determinista: las claves
 * salen en el orden de declaración de los DTO y todas las colecciones son listas
 * (nunca mapas), así que las mismas entradas producen el mismo string.
 *
 * Los ids se conservan tal cual: el export/import sirve para mover datos entre
 * dispositivos sin colisiones (UUID) y para el backup manual.
 */
const val BACKUP_SCHEMA: String = "gimcheck"

/** Versión del esquema de backup. Subir sólo con una migración en [migrateBackup]. */
const val BACKUP_VERSION: Int = 1

@Serializable
data class BackupFile(
    val schema: String = BACKUP_SCHEMA,
    val version: Int = BACKUP_VERSION,
    val exportedAt: String,
    val settings: BackupSettings,
    val muscles: List<BackupMuscle> = emptyList(),
    val exercises: List<BackupExercise> = emptyList(),
    val routines: List<BackupRoutine> = emptyList(),
    val sessions: List<BackupSession> = emptyList(),
    val bodyMetrics: List<BackupBodyMetric> = emptyList(),
)

/**
 * Ajustes globales en el backup. `updatedAt` no existe en [Settings] (es una fila de
 * `app_setting` por clave en §2); el export lo completa con el instante del backup
 * para que la fusión tenga con qué comparar.
 */
@Serializable
data class BackupSettings(
    val updatedAt: String,
    val warm: Int = 5,
    val trans: Int = 30,
    val drops: Int = 2,
    val dropSec: Int = 25,
    val dropVol: Double = 0.5,
    val optMin: Int = 10,
    val optMax: Int = 20,
    val includeIndirect: Boolean = false,
    val e1rmFormula: E1rmFormula = E1rmFormula.EPLEY,
    val bodyweightVolume: Boolean = false,
)

@Serializable
data class BackupMuscle(
    val id: String,
    val name: String,
    val position: Int = 0,
    val archived: Boolean = false,
    val updatedAt: String = "",
)

@Serializable
data class BackupSecondary(
    val muscleId: String,
    val factor: Double,
)

@Serializable
data class BackupExercise(
    val id: String,
    val name: String,
    val primaryMuscleId: String,
    val secondaries: List<BackupSecondary> = emptyList(),
    val secondsPerSet: Int,
    val restSeconds: Int,
    val compound: Boolean = false,
    val loadType: LoadType = LoadType.EXTERNAL,
    val notes: String = "",
    val position: Int = 0,
    val archived: Boolean = false,
    val updatedAt: String = "",
)

@Serializable
data class BackupDayItem(
    val id: String,
    val exerciseId: String,
    val sets: Int,
    val dropSet: Boolean = false,
    val supersetNext: Boolean = false,
    val restOverrideSeconds: Int? = null,
    val targetRepsMin: Int? = null,
    val targetRepsMax: Int? = null,
)

@Serializable
data class BackupDay(
    val id: String,
    val name: String,
    val position: Int = 0,
    val items: List<BackupDayItem> = emptyList(),
)

@Serializable
data class BackupRoutine(
    val id: String,
    val name: String,
    val notes: String = "",
    val position: Int = 0,
    val createdAt: String = "",
    val archived: Boolean = false,
    val activeDayId: String? = null,
    val updatedAt: String = "",
    val days: List<BackupDay> = emptyList(),
)

@Serializable
data class BackupSessionSet(
    val id: String,
    val exerciseId: String,
    val weightKg: Double,
    val reps: Int,
    val completedAt: String,
    val rpe: Double? = null,
    val isWarmup: Boolean = false,
    val isDropSet: Boolean = false,
)

@Serializable
data class BackupSession(
    val id: String,
    val startedAt: String,
    val endedAt: String? = null,
    val status: String = "FINISHED",
    val bodyWeightKg: Double? = null,
    val routineId: String? = null,
    val routineDayId: String? = null,
    val notes: String = "",
    val updatedAt: String = "",
    val sets: List<BackupSessionSet> = emptyList(),
)

@Serializable
data class BackupBodyMetric(
    val date: String,
    val weightKg: Double,
    val bodyFatPct: Double? = null,
    val note: String = "",
    val updatedAt: String = "",
)

/** JSON de backup: compacto por defecto, sin claves nulas implícitas. */
private val backupJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private val prettyBackupJson = Json(backupJson) { prettyPrint = true }

// --- export -------------------------------------------------------------------

/**
 * Serializa el estado completo. Función pura y determinista: mismas entradas →
 * mismo string (no depende del orden de un `Map` ni del reloj).
 *
 * @param exportedAt instante que se escribe en `exportedAt` (ISO-8601, lo pasa el
 *   llamador: `:core` no lee el reloj).
 * @param stamp `updatedAt` de las filas de plan, que todavía no lo persisten; por
 *   defecto el mismo instante del backup.
 * @param pretty indentado de 2 espacios (para el archivo que baja el usuario).
 */
fun exportBackup(
    snapshot: DomainSnapshot,
    exportedAt: String,
    stamp: String = exportedAt,
    pretty: Boolean = false,
): String {
    val settings = snapshot.settings
    val file = BackupFile(
        exportedAt = exportedAt,
        settings = BackupSettings(
            updatedAt = stamp,
            warm = settings.warm,
            trans = settings.trans,
            drops = settings.drops,
            dropSec = settings.dropSec,
            dropVol = settings.dropVol,
            optMin = settings.optMin,
            optMax = settings.optMax,
            includeIndirect = settings.includeIndirect,
            e1rmFormula = settings.e1rmFormula,
            bodyweightVolume = settings.bodyweightVolume,
        ),
        muscles = snapshot.muscles.map {
            BackupMuscle(
                id = it.id,
                name = it.name,
                position = it.position,
                archived = it.archived,
                updatedAt = stamp,
            )
        },
        exercises = snapshot.exercises.map {
            BackupExercise(
                id = it.id,
                name = it.name,
                primaryMuscleId = it.primaryMuscleId,
                secondaries = it.secondaries.map { s -> BackupSecondary(s.muscleId, s.factor) },
                secondsPerSet = it.secondsPerSet,
                restSeconds = it.restSeconds,
                compound = it.compound,
                loadType = it.loadType,
                position = it.position,
                archived = it.archived,
                updatedAt = stamp,
            )
        },
        routines = snapshot.routines.map { routine ->
            BackupRoutine(
                id = routine.id,
                name = routine.name,
                notes = routine.notes,
                position = routine.position,
                createdAt = routine.createdAt,
                archived = routine.archived,
                activeDayId = routine.activeDayId,
                updatedAt = stamp,
                days = routine.days.mapIndexed { index, day ->
                    BackupDay(
                        id = day.id,
                        name = day.name,
                        position = index,
                        items = day.items.map { item ->
                            BackupDayItem(
                                id = item.id,
                                exerciseId = item.exerciseId,
                                sets = item.sets,
                                dropSet = item.dropSet,
                                supersetNext = item.supersetNext,
                                restOverrideSeconds = item.restOverrideSeconds,
                                targetRepsMin = item.targetRepsMin,
                                targetRepsMax = item.targetRepsMax,
                            )
                        },
                    )
                },
            )
        },
        sessions = snapshot.sessions.map { session ->
            BackupSession(
                id = session.id,
                startedAt = session.startedAt.toString(),
                endedAt = session.endedAt?.toString(),
                status = if (session.endedAt == null) "ACTIVE" else "FINISHED",
                bodyWeightKg = session.bodyWeightKg,
                routineId = session.routineId,
                routineDayId = session.routineDayId,
                updatedAt = session.endedAt?.toString() ?: session.startedAt.toString(),
                sets = session.sets.map { set ->
                    BackupSessionSet(
                        id = set.id,
                        exerciseId = set.exerciseId,
                        weightKg = set.weightKg,
                        reps = set.reps,
                        completedAt = set.completedAt.toString(),
                        rpe = set.rpe,
                        isWarmup = set.isWarmup,
                        isDropSet = set.isDropSet,
                    )
                },
            )
        },
        bodyMetrics = snapshot.bodyMetrics.map {
            BackupBodyMetric(
                date = it.date.toString(),
                weightKg = it.weightKg,
                bodyFatPct = it.bodyFatPct,
                note = it.note,
                updatedAt = it.updatedAt,
            )
        },
    )
    val json = if (pretty) prettyBackupJson else backupJson
    return json.encodeToString(BackupFile.serializer(), file)
}

// --- import -------------------------------------------------------------------

/**
 * Decodifica, valida y migra un backup. **No escribe nada.**
 *
 * Falla con [ImportError] tipado si:
 *  - el texto no es JSON o no tiene la forma del backup ([ImportError.NotJson]);
 *  - `schema` no es `gimcheck` o `version` no existe ([ImportError.UnsupportedVersion]);
 *  - hay ids repetidos, referencias a músculos/ejercicios inexistentes o fechas rotas.
 */
fun importBackup(text: String): ImportResult<DomainSnapshot> =
    parseBackup(text).flatMap { it.toDomain() }

/** Igual que [importBackup] pero sin materializar el dominio (para fusionar). */
fun parseBackup(text: String): ImportResult<BackupFile> = parsing {
    val file = backupJson.decodeFromString(BackupFile.serializer(), text)
    validateBackup(file)
}

private fun migrateBackup(file: BackupFile): BackupFile = when (file.version) {
    // No hay versiones anteriores a 1 todavía. Cuando exista la 2, acá se transforma
    // la 1 en 2 paso a paso; nunca se lee un archivo viejo sin pasar por acá.
    BACKUP_VERSION -> file
    else -> throw ImportException(
        ImportError.UnsupportedVersion(BACKUP_SCHEMA, file.version, "soportadas: 1"),
    )
}

private fun validateBackup(file: BackupFile): BackupFile {
    if (file.schema != BACKUP_SCHEMA) {
        throw ImportException(
            ImportError.UnsupportedVersion(file.schema, file.version, "se esperaba schema $BACKUP_SCHEMA"),
        )
    }
    if (file.version > BACKUP_VERSION) {
        throw ImportException(
            ImportError.UnsupportedVersion(BACKUP_SCHEMA, file.version, "soportadas: 1..$BACKUP_VERSION"),
        )
    }
    val migrated = migrateBackup(file)

    fun uniqueIds(path: String, ids: List<String>) {
        val seen = HashSet<String>()
        for (id in ids) {
            if (id.isBlank()) invalid(path, "hay un id vacío")
            if (!seen.add(id)) invalid(path, "id repetido: $id")
        }
    }
    uniqueIds("muscles", migrated.muscles.map { it.id })
    uniqueIds("exercises", migrated.exercises.map { it.id })
    uniqueIds("routines", migrated.routines.map { it.id })
    uniqueIds("routines.days", migrated.routines.flatMap { r -> r.days.map { it.id } })
    uniqueIds("sessions", migrated.sessions.map { it.id })
    uniqueIds("bodyMetrics", migrated.bodyMetrics.map { it.date })

    val muscles = migrated.muscles.map { it.id }.toSet()
    val exercises = migrated.exercises.map { it.id }.toSet()
    migrated.exercises.forEach { exercise ->
        if (exercise.primaryMuscleId !in muscles) {
            invalid("exercises[${exercise.id}].primaryMuscleId", "no existe el músculo ${exercise.primaryMuscleId}")
        }
        exercise.secondaries.forEach { secondary ->
            if (secondary.muscleId !in muscles) {
                invalid("exercises[${exercise.id}].secondaries", "no existe el músculo ${secondary.muscleId}")
            }
        }
    }
    migrated.routines.forEach { routine ->
        routine.days.forEach { day ->
            day.items.forEach { item ->
                if (item.exerciseId !in exercises) {
                    invalid("routines[${routine.id}].days[${day.id}].items[${item.id}]", "no existe el ejercicio ${item.exerciseId}")
                }
            }
        }
        if (routine.activeDayId != null && routine.days.none { it.id == routine.activeDayId }) {
            invalid("routines[${routine.id}].activeDayId", "no es un día de la rutina")
        }
    }
    migrated.sessions.forEach { session ->
        parseDateTime("sessions[${session.id}].startedAt", session.startedAt)
        session.endedAt?.let { parseDateTime("sessions[${session.id}].endedAt", it) }
        session.sets.forEach { parseDateTime("sessions[${session.id}].sets[${it.id}].completedAt", it.completedAt) }
    }
    migrated.bodyMetrics.forEach { parseDate("bodyMetrics[${it.date}].date", it.date) }
    return migrated
}

private fun parseDateTime(path: String, text: String): LocalDateTime = runCatching {
    LocalDateTime.parse(text)
}.getOrElse { invalid(path, "no es una fecha ISO-8601 válida: $text") }

private fun parseDate(path: String, text: String): LocalDate = runCatching {
    LocalDate.parse(text)
}.getOrElse { invalid(path, "no es una fecha ISO-8601 válida: $text") }

/** Materializa el dominio a partir de un backup ya validado. */
fun BackupFile.toDomain(): ImportResult<DomainSnapshot> = parsing {
    val exercises = exercises.map { row ->
        Exercise(
            id = row.id,
            name = row.name,
            primaryMuscleId = row.primaryMuscleId,
            secondaries = row.secondaries.map { Secondary(it.muscleId, it.factor) },
            secondsPerSet = row.secondsPerSet,
            restSeconds = row.restSeconds,
            compound = row.compound,
            loadType = row.loadType,
            position = row.position,
            archived = row.archived,
        )
    }
    val byId = exercises.associateBy { it.id }
    val routines = routines.map { routine ->
        Routine(
            id = routine.id,
            name = routine.name,
            notes = routine.notes,
            position = routine.position,
            createdAt = routine.createdAt,
            archived = routine.archived,
            activeDayId = routine.activeDayId,
            days = routine.days.map { day ->
                RoutineDay(
                    id = day.id,
                    name = day.name,
                    items = day.items.map { item ->
                        val exercise = byId[item.exerciseId]
                            ?: invalid(
                                "routines[${routine.id}].days[${day.id}].items[${item.id}]",
                                "no existe el ejercicio ${item.exerciseId}",
                            )
                        DayItem(
                            id = item.id,
                            exercise = exercise,
                            sets = item.sets,
                            dropSet = item.dropSet,
                            supersetNext = item.supersetNext,
                            restOverrideSeconds = item.restOverrideSeconds,
                            targetRepsMin = item.targetRepsMin,
                            targetRepsMax = item.targetRepsMax,
                        )
                    },
                )
            },
        )
    }
    DomainSnapshot(
        settings = Settings(
            warm = settings.warm,
            trans = settings.trans,
            drops = settings.drops,
            dropSec = settings.dropSec,
            dropVol = settings.dropVol,
            optMin = settings.optMin,
            optMax = settings.optMax,
            includeIndirect = settings.includeIndirect,
            e1rmFormula = settings.e1rmFormula,
            bodyweightVolume = settings.bodyweightVolume,
        ),
        muscles = muscles.map { Muscle(it.id, it.name, it.position, it.archived) },
        exercises = exercises,
        routines = routines,
        sessions = sessions.map { session ->
            Session(
                id = session.id,
                startedAt = parseDateTime("sessions[${session.id}].startedAt", session.startedAt),
                endedAt = session.endedAt?.let { parseDateTime("sessions[${session.id}].endedAt", it) },
                bodyWeightKg = session.bodyWeightKg,
                routineId = session.routineId,
                routineDayId = session.routineDayId,
                sets = session.sets.map { set ->
                    SessionSet(
                        id = set.id,
                        exerciseId = set.exerciseId,
                        weightKg = set.weightKg,
                        reps = set.reps,
                        completedAt = parseDateTime("sessions[${session.id}].sets[${set.id}].completedAt", set.completedAt),
                        rpe = set.rpe,
                        isWarmup = set.isWarmup,
                        isDropSet = set.isDropSet,
                    )
                },
            )
        },
        bodyMetrics = bodyMetrics.map {
            BodyMetric(
                date = parseDate("bodyMetrics[${it.date}].date", it.date),
                weightKg = it.weightKg,
                bodyFatPct = it.bodyFatPct,
                note = it.note,
                updatedAt = it.updatedAt,
            )
        },
        activeRoutineId = routines.firstOrNull()?.id,
    )
}

// --- fusión -------------------------------------------------------------------

/**
 * Fusiona dos backups **por id con last-write-wins sobre `updatedAt`** (§7).
 *
 * Función pura: devuelve el backup resultante; escribir es del repositorio. Reglas:
 *  - gana la fila con `updatedAt` mayor (comparación de strings ISO-8601, que ordena
 *    igual que el tiempo); un `updatedAt` vacío pierde contra uno presente y, si
 *    empatan vacíos, gana el existente (no se pisa lo local con lo desconocido).
 *  - el orden del resultado es el de `existing` y después lo nuevo de `incoming`.
 *  - los ajustes se fusionan como una sola fila (mismo criterio).
 *  - métricas corporales: la clave es la fecha, no el id.
 */
fun mergeBackup(existing: BackupFile, incoming: BackupFile): BackupFile {
    fun wins(candidate: String, current: String): Boolean = when {
        candidate.isBlank() && current.isBlank() -> false
        candidate.isBlank() -> false
        current.isBlank() -> true
        else -> candidate > current
    }

    val settings = if (wins(incoming.settings.updatedAt, existing.settings.updatedAt)) {
        incoming.settings
    } else {
        existing.settings
    }

    return existing.copy(
        settings = settings,
        muscles = mergeRows(existing.muscles, incoming.muscles, { it.id }) { a, b ->
            if (wins(b.updatedAt, a.updatedAt)) b else a
        },
        exercises = mergeRows(existing.exercises, incoming.exercises, { it.id }) { a, b ->
            if (wins(b.updatedAt, a.updatedAt)) b else a
        },
        routines = mergeRows(existing.routines, incoming.routines, { it.id }) { a, b ->
            if (wins(b.updatedAt, a.updatedAt)) b else a
        },
        sessions = mergeRows(existing.sessions, incoming.sessions, { it.id }) { a, b ->
            if (wins(b.updatedAt, a.updatedAt)) b else a
        },
        bodyMetrics = mergeRows(existing.bodyMetrics, incoming.bodyMetrics, { it.date }) { a, b ->
            if (wins(b.updatedAt, a.updatedAt)) b else a
        },
    )
}

private fun <T> mergeRows(
    existing: List<T>,
    incoming: List<T>,
    id: (T) -> String,
    pick: (current: T, candidate: T) -> T,
): List<T> {
    val byId = LinkedHashMap<String, T>()
    existing.forEach { byId[id(it)] = it }
    incoming.forEach { candidate ->
        val current = byId[id(candidate)]
        byId[id(candidate)] = if (current == null) candidate else pick(current, candidate)
    }
    return byId.values.toList()
}
