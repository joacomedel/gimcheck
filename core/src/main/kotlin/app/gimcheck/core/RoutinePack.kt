package app.gimcheck.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Export e import de **rutinas** (no del historial) — `docs/especificacion.md` §7.
 *
 * Tres formatos de entrada, un solo resultado ([RoutinePackage]):
 *
 *  - **pack de GimCheck** (`kind: "routines"`): el que produce [exportRoutinePack].
 *    Conserva ids, días, ítems, descansos por ítem y reps objetivo; es re-importable
 *    tal cual y sirve para pasar rutinas entre teléfonos.
 *  - **plan de la web** (`tipo: "plan"`): el JSON que ya exporta la app con
 *    [exportPlan]. La web referencia ejercicios por nombre y no guarda ids, así que
 *    el import resuelve nombres contra el catálogo y crea lo que falte.
 *  - **backup completo** (propio o de la web): se toman sus rutinas y el catálogo que
 *    necesitan. El historial del archivo se ignora a propósito: para eso está el
 *    import de Ajustes, que reemplaza todo.
 *
 * Igual que el resto de los imports, son funciones puras: devuelven [ImportResult] y
 * no escriben nada. La resolución contra el catálogo local y la escritura viven en la
 * capa de datos (`RoutineTransfer`).
 */

/** `kind` que distingue un pack de rutinas de un backup completo (mismo `schema`). */
const val ROUTINE_PACK_KIND: String = "routines"

/** Versión del pack. Subir sólo con una migración explícita en [decodeRoutinePack]. */
const val ROUTINE_PACK_VERSION: Int = 1

/**
 * Contenido de un pack, ya resuelto a dominio.
 *
 * Los ids de [muscles] y [exercises] son los del archivo; los de [routines] también.
 * La capa de datos decide si los reutiliza o genera ids nuevos para no pisar nada.
 */
data class RoutinePackage(
    val muscles: List<Muscle> = emptyList(),
    val exercises: List<Exercise> = emptyList(),
    val routines: List<Routine> = emptyList(),
)

@Serializable
data class RoutinePackFile(
    val schema: String = BACKUP_SCHEMA,
    val kind: String = ROUTINE_PACK_KIND,
    val version: Int = ROUTINE_PACK_VERSION,
    val exportedAt: String,
    val muscles: List<BackupMuscle> = emptyList(),
    val exercises: List<BackupExercise> = emptyList(),
    val routines: List<BackupRoutine> = emptyList(),
)

private val routinePackJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private val prettyRoutinePackJson = Json(routinePackJson) { prettyPrint = true }

private val routineImportJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private const val DEFAULT_MUSCLE: String = "General"
private const val DEFAULT_SECONDS_PER_SET: Int = 45
private const val DEFAULT_REST_SECONDS: Int = 90

// --- export -------------------------------------------------------------------

/**
 * Serializa un conjunto de rutinas con **sólo** los músculos y ejercicios que usan.
 * Determinista: mismas entradas → mismo string.
 *
 * @param routineIds ids a incluir; `null` = todas las rutinas del snapshot.
 * @param pretty indentado para que el archivo se pueda leer a ojo.
 */
fun exportRoutinePack(
    snapshot: DomainSnapshot,
    exportedAt: String,
    routineIds: Set<String>? = null,
    stamp: String = exportedAt,
    pretty: Boolean = true,
): String {
    val selected = if (routineIds == null) {
        snapshot.routines
    } else {
        snapshot.routines.filter { it.id in routineIds }
    }
    val usedExercises = selected
        .flatMap { routine -> routine.days.flatMap { day -> day.items } }
        .map { it.exercise }
        .distinctBy { it.id }
    val usedExerciseIds = usedExercises.map { it.id }.toSet()
    val usedMuscleIds = buildSet {
        usedExercises.forEach { exercise ->
            add(exercise.primaryMuscleId)
            exercise.secondaries.forEach { add(it.muscleId) }
        }
    }
    val filtered = DomainSnapshot(
        settings = snapshot.settings,
        muscles = snapshot.muscles.filter { it.id in usedMuscleIds },
        exercises = usedExercises,
        routines = selected,
        activeRoutineId = snapshot.activeRoutineId,
    )
    val backup = filtered.toBackupFile(exportedAt = exportedAt, stamp = stamp)
    val file = RoutinePackFile(
        exportedAt = exportedAt,
        muscles = backup.muscles,
        exercises = backup.exercises,
        routines = backup.routines,
    )
    val json = if (pretty) prettyRoutinePackJson else routinePackJson
    return json.encodeToString(RoutinePackFile.serializer(), file)
}

// --- import -------------------------------------------------------------------

/**
 * Detecta el formato y devuelve un [RoutinePackage]. Acepta pack de GimCheck, plan de
 * la web y backup completo (propio o web). No escribe nada.
 */
fun importRoutineFile(text: String): ImportResult<RoutinePackage> = parsing {
    val root = routineImportJson.parseToJsonElement(text).asObject("")
    when {
        root.optional("kind")?.asString("kind") == ROUTINE_PACK_KIND ->
            decodeRoutinePack(text)

        root.optional("tipo")?.asString("tipo") == PLAN_TIPO ->
            decodePlan(text)

        root.optional("schema")?.asString("schema") == BACKUP_SCHEMA ->
            decodeBackup(text)

        root.containsKey("plans") || root.containsKey("days") ->
            decodeWebBackup(text)

        else -> invalid(
            "",
            "no es un archivo de rutinas: se esperaba un pack de GimCheck, " +
                "un plan exportado de la web o un backup",
        )
    }
}

/** Igual que [importRoutineFile] pero exige que el archivo sea un pack de GimCheck. */
fun importRoutinePack(text: String): ImportResult<RoutinePackage> = parsing {
    decodeRoutinePack(text)
}

/** Importa el JSON de plan de la web ([exportPlan] y `buildPlanJson()` del HTML). */
fun importPlan(text: String): ImportResult<RoutinePackage> = parsing {
    decodePlan(text)
}

private fun decodeRoutinePack(text: String): RoutinePackage {
    val file = routinePackJson.decodeFromString(RoutinePackFile.serializer(), text)
    if (file.schema != BACKUP_SCHEMA) {
        invalid("schema", "se esperaba $BACKUP_SCHEMA y vino ${file.schema}")
    }
    if (file.kind != ROUTINE_PACK_KIND) {
        invalid("kind", "se esperaba $ROUTINE_PACK_KIND y vino ${file.kind}")
    }
    if (file.version !in 1..ROUTINE_PACK_VERSION) {
        throw ImportException(
            ImportError.UnsupportedVersion(
                "$BACKUP_SCHEMA/$ROUTINE_PACK_KIND",
                file.version,
                "soportadas: 1..$ROUTINE_PACK_VERSION",
            ),
        )
    }

    // Reusa la validación del backup (ids únicos, referencias y activeDay) con listas
    // vacías de sesiones y métricas. Después se agregan los rangos propios del plan.
    val asBackup = BackupFile(
        schema = BACKUP_SCHEMA,
        version = BACKUP_VERSION,
        exportedAt = file.exportedAt,
        settings = BackupSettings(updatedAt = file.exportedAt),
        muscles = file.muscles,
        exercises = file.exercises,
        routines = file.routines,
        sessions = emptyList(),
        bodyMetrics = emptyList(),
    )
    validateBackup(asBackup)
    validatePackRanges(file)

    val domain = when (val result = asBackup.toDomain()) {
        is ImportResult.Success -> result.value
        is ImportResult.Failure -> throw ImportException(result.error)
    }
    if (domain.routines.isEmpty()) invalid("routines", "el archivo no contiene rutinas")
    return RoutinePackage(
        muscles = domain.muscles,
        exercises = domain.exercises,
        routines = domain.routines,
    )
}

private fun validatePackRanges(file: RoutinePackFile) {
    val itemIds = HashSet<String>()
    file.muscles.forEach { muscle ->
        if (muscle.name.isBlank()) invalid("muscles[${muscle.id}].name", "nombre vacío")
    }
    file.exercises.forEach { exercise ->
        if (exercise.name.isBlank()) invalid("exercises[${exercise.id}].name", "nombre vacío")
        if (exercise.secondsPerSet < 0) {
            invalid("exercises[${exercise.id}].secondsPerSet", "no puede ser negativo")
        }
        if (exercise.restSeconds < 0) {
            invalid("exercises[${exercise.id}].restSeconds", "no puede ser negativo")
        }
        exercise.secondaries.forEach { secondary ->
            if (secondary.factor !in 0.0..1.0) {
                invalid(
                    "exercises[${exercise.id}].secondaries[${secondary.muscleId}]",
                    "el factor debe estar entre 0 y 1: ${secondary.factor}",
                )
            }
        }
    }
    file.routines.forEach { routine ->
        if (routine.name.isBlank()) invalid("routines[${routine.id}].name", "nombre vacío")
        routine.days.forEach { day ->
            if (day.name.isBlank()) invalid("routines[${routine.id}].days[${day.id}].name", "nombre vacío")
            day.items.forEach { item ->
                if (!itemIds.add(item.id)) {
                    invalid("routines[${routine.id}].days[${day.id}].items[${item.id}]", "id repetido")
                }
                if (item.sets !in 1..20) {
                    invalid(
                        "routines[${routine.id}].days[${day.id}].items[${item.id}].sets",
                        "las series deben estar entre 1 y 20: ${item.sets}",
                    )
                }
                item.restOverrideSeconds?.let { seconds ->
                    if (seconds < 0) {
                        invalid(
                            "routines[${routine.id}].days[${day.id}].items[${item.id}].restOverrideSeconds",
                            "no puede ser negativo",
                        )
                    }
                }
            }
        }
    }
}

// --- backup completo ----------------------------------------------------------

private fun decodeBackup(text: String): RoutinePackage {
    val snapshot = when (val result = importBackup(text)) {
        is ImportResult.Success -> result.value
        is ImportResult.Failure -> throw ImportException(result.error)
    }
    if (snapshot.routines.isEmpty()) invalid("routines", "el backup no contiene rutinas")
    return RoutinePackage(
        muscles = snapshot.muscles,
        exercises = snapshot.exercises,
        routines = snapshot.routines,
    )
}

private fun decodeWebBackup(text: String): RoutinePackage {
    val result = when (val imported = importWeb(text)) {
        is ImportResult.Success -> imported.value
        is ImportResult.Failure -> throw ImportException(imported.error)
    }
    val snapshot = result.snapshot
    if (snapshot.routines.isEmpty()) invalid("plans", "el backup no contiene rutinas")
    return RoutinePackage(
        muscles = snapshot.muscles,
        exercises = snapshot.exercises,
        routines = snapshot.routines,
    )
}

// --- plan de la web -----------------------------------------------------------

/**
 * DTO laxo del plan de la web: sólo lo que hace falta para reconstruir la rutina.
 * `ajustes` y `resumen` se ignoran a propósito (son globales o derivados).
 */
@Serializable
private data class PlanImportFile(
    val app: String = PLAN_APP,
    val tipo: String = "",
    val version: Int = PLAN_VERSION,
    val plan: PlanImportBody? = null,
    val ejercicios: List<PlanImportExercise> = emptyList(),
)

@Serializable
private data class PlanImportBody(
    val nombre: String = "",
    val dias: List<PlanImportDay> = emptyList(),
)

@Serializable
private data class PlanImportDay(
    val nombre: String = "",
    val ejercicios: List<PlanImportItem> = emptyList(),
)

@Serializable
private data class PlanImportItem(
    val ejercicio: String = "",
    val series: Int = 3,
    val dropSet: Boolean = false,
    val superserieConSiguiente: Boolean = false,
)

@Serializable
private data class PlanImportExercise(
    val nombre: String = "",
    val musculoPrincipal: String = "",
    val musculosSecundarios: List<PlanImportSecondary> = emptyList(),
    val segundosPorSerie: Int = DEFAULT_SECONDS_PER_SET,
    val descansoSegundos: Int = DEFAULT_REST_SECONDS,
    val multiarticular: Boolean = false,
)

@Serializable
private data class PlanImportSecondary(
    val musculo: String = "",
    val factor: Double = 0.5,
)

private fun decodePlan(text: String): RoutinePackage {
    val file = routineImportJson.decodeFromString(PlanImportFile.serializer(), text)
    if (file.app != PLAN_APP) {
        invalid("app", "se esperaba $PLAN_APP y vino ${file.app}")
    }
    if (file.tipo.isNotEmpty() && file.tipo != PLAN_TIPO) {
        invalid("tipo", "se esperaba $PLAN_TIPO y vino ${file.tipo}")
    }
    if (file.version != PLAN_VERSION) {
        throw ImportException(
            ImportError.UnsupportedVersion(PLAN_APP, file.version, "soportadas: $PLAN_VERSION"),
        )
    }
    val body = file.plan ?: missing("plan")
    val routineName = body.nombre.trim()
    if (routineName.isEmpty()) invalid("plan.nombre", "vacío")

    val metadata = LinkedHashMap<String, PlanImportExercise>()
    file.ejercicios.forEach { exercise ->
        val name = exercise.nombre.trim()
        if (name.isNotEmpty()) metadata[normalizeName(name)] = exercise
    }

    // Músculos por clave (id o nombre en el formato de la web): se resuelven contra el
    // catálogo local en la capa de datos; acá sólo se arma la lista del archivo.
    val muscleNames = LinkedHashMap<String, String>()
    fun addMuscle(key: String) {
        val display = key.trim().ifBlank { DEFAULT_MUSCLE }.replace('_', ' ')
        muscleNames.putIfAbsent(normalizeName(display), display)
    }
    file.ejercicios.forEach { exercise ->
        addMuscle(exercise.musculoPrincipal)
        exercise.musculosSecundarios.forEach { addMuscle(it.musculo) }
    }
    body.dias.forEach { day ->
        day.ejercicios.forEach { item ->
            val name = item.ejercicio.trim()
            if (name.isEmpty()) invalid("plan.dias", "hay un ítem sin nombre de ejercicio")
            if (normalizeName(name) !in metadata) addMuscle(DEFAULT_MUSCLE)
        }
    }
    if (muscleNames.isEmpty()) addMuscle(DEFAULT_MUSCLE)

    val muscleIdByName = uniquePlanIds(
        prefix = "plan-muscle",
        keys = muscleNames.keys,
    )

    val exerciseDisplayNames = LinkedHashMap<String, String>()
    metadata.values.forEach { meta ->
        val name = meta.nombre.trim()
        exerciseDisplayNames.putIfAbsent(normalizeName(name), name)
    }
    body.dias.forEach { day ->
        day.ejercicios.forEach { item ->
            val name = item.ejercicio.trim()
            if (name.isNotEmpty()) exerciseDisplayNames.putIfAbsent(normalizeName(name), name)
        }
    }

    val exerciseIds = uniquePlanIds(
        prefix = "plan-exercise",
        keys = exerciseDisplayNames.keys,
    )
    val exercises = exerciseDisplayNames.entries.map { (key, name) ->
        val meta = metadata[key]
        val primaryName = meta?.musculoPrincipal?.trim()?.takeIf { it.isNotEmpty() }
            ?: muscleNames.values.first()
        val primaryId = muscleIdByName[normalizeName(primaryName)]
            ?: muscleIdByName.values.first()
        val secondaries = meta?.musculosSecundarios.orEmpty().mapNotNull { secondary ->
            val muscleId = muscleIdByName[normalizeName(secondary.musculo)] ?: return@mapNotNull null
            if (muscleId == primaryId) return@mapNotNull null
            Secondary(muscleId, secondary.factor.coerceIn(0.0, 1.0))
        }.distinctBy { it.muscleId }
        Exercise(
            id = exerciseIds.getValue(key),
            name = name,
            primaryMuscleId = primaryId,
            secondaries = secondaries,
            secondsPerSet = (meta?.segundosPorSerie ?: DEFAULT_SECONDS_PER_SET).coerceAtLeast(1),
            restSeconds = (meta?.descansoSegundos ?: DEFAULT_REST_SECONDS).coerceAtLeast(0),
            compound = meta?.multiarticular ?: false,
        )
    }
    val exerciseByName = exercises.associateBy { normalizeName(it.name) }

    val days = body.dias.mapIndexed { dayIndex, day ->
        val items = day.ejercicios.mapIndexedNotNull { itemIndex, item ->
            val name = item.ejercicio.trim()
            if (name.isEmpty()) invalid("plan.dias[$dayIndex].ejercicios[$itemIndex].ejercicio", "vacío")
            if (item.series !in 1..20) {
                invalid(
                    "plan.dias[$dayIndex].ejercicios[$itemIndex].series",
                    "las series deben estar entre 1 y 20: ${item.series}",
                )
            }
            val exercise = exerciseByName[normalizeName(name)]
                ?: invalid("plan.dias[$dayIndex].ejercicios[$itemIndex].ejercicio", "no se pudo crear $name")
            DayItem(
                id = "plan-item-${dayIndex + 1}-${itemIndex + 1}",
                exercise = exercise,
                sets = item.series,
                dropSet = item.dropSet,
                supersetNext = item.superserieConSiguiente && itemIndex < day.ejercicios.lastIndex,
            )
        }
        RoutineDay(
            id = "plan-day-${dayIndex + 1}",
            name = day.nombre.trim().ifBlank { "Día ${dayIndex + 1}" },
            items = items,
        )
    }

    val muscles = muscleNames.entries.mapIndexed { index, (normalized, name) ->
        Muscle(id = muscleIdByName.getValue(normalized), name = name, position = index)
    }
    return RoutinePackage(
        muscles = muscles,
        exercises = exercises,
        routines = listOf(
            Routine(
                id = "plan-routine-1",
                name = routineName,
                days = days,
            ),
        ),
    )
}

private fun normalizeName(value: String): String =
    value.trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

/**
 * Ids estables derivados del nombre, no del índice: importar dos planes distintos no
 * puede hacer que el ejercicio 1 de uno se confunda con el ejercicio 1 del otro. Si
 * dos nombres comparten slug, se agrega un sufijo.
 */
private fun uniquePlanIds(prefix: String, keys: Collection<String>): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    val used = HashSet<String>()
    keys.forEach { key ->
        val base = "$prefix-${slug(key)}"
        var candidate = base
        var copy = 2
        while (!used.add(candidate)) {
            candidate = "$base-$copy"
            copy += 1
        }
        result[key] = candidate
    }
    return result
}

private fun slug(value: String): String =
    value.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "x" }
