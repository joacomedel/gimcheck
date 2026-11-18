package app.gimcheck.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Import del JSON completo de la web — `docs/especificacion.md` §7.
 *
 * **Fuente de verdad del formato**: `Rutinas de Gimnasio.html`.
 *  - clave de `localStorage`: `var LS_KEY = 'rutinas-gym-v1'` (línea 140);
 *  - estado: el objeto `S` que escribe `serialize()` (líneas 296-305), es decir
 *    `{settings, muscles, exercises, includeIndirect, plans, curPlan, v}`
 *    (`days`/`activeDay` de la raíz sólo existen cuando todavía no hay `plans`);
 *  - migraciones: `ensureV2()` (líneas 204-295), que lleva `o.v` desde 1 hasta 21.
 *
 * Este importador **reproduce esa escalera**, incluida la trampa documentada en
 * `docs/paridad-web.md`: `hu.work` 35 → 60 en el primer arranque (`if(!o.v)`).
 * El catálogo y las firmas de las migraciones vienen generados del HTML
 * ([WebCatalog]), no transcritos a mano.
 *
 * Es una función pura: devuelve [WebImportResult] o un [ImportError] tipado y no
 * escribe nada. Los ids se conservan; sólo se generan ids deterministas para lo que
 * la web crea al azar (`uid()`, p. ej. los días de la Rutina B sintetizada).
 */

/** Lo que existe en la web y no existe en la app (o al revés). */
data class WebImportResult(
    val snapshot: DomainSnapshot,
    /** `o.v` de origen: 0 cuando el backup no tenía versión (pre-v1). */
    val sourceVersion: Int,
    /** Migraciones aplicadas, en orden, con texto legible para el usuario. */
    val migrations: List<String>,
    /** Datos que no se pudieron (o no se quisieron) mapear. */
    val warnings: List<String>,
)

private val webJson = Json { ignoreUnknownKeys = true }

private val ROOT_KEYS = setOf(
    "settings", "muscles", "exercises", "plans", "curPlan", "includeIndirect", "v", "days", "activeDay",
)

/** Entrada del importador web. No escribe nada: ver el contrato del archivo. */
fun importWeb(text: String): ImportResult<WebImportResult> = parsing {
    val root = webJson.parseToJsonElement(text).asObject("")

    root.optional("tipo")?.asString("tipo")?.let { tipo ->
        if (tipo == PLAN_TIPO) {
            throw ImportException(
                ImportError.UnsupportedVersion(
                    PLAN_APP,
                    root.optional("version")?.asInt("version"),
                    "es un export de plan; el import espera el backup de datos completo",
                ),
            )
        }
    }

    val version = root.optional("v")?.asInt("v") ?: 0
    if (version < 0 || version > WebCatalog.VERSION) {
        throw ImportException(
            ImportError.UnsupportedVersion(
                PLAN_APP,
                version,
                "soportadas 0..${WebCatalog.VERSION}",
            ),
        )
    }

    val muscles = root.required("muscles", "").asArray("muscles").mapIndexed { index, element ->
        element.asString("muscles[$index]")
    }
    val uniqueMuscles = mutableListOf<String>()
    muscles.forEachIndexed { index, name ->
        if (name.isBlank()) invalid("muscles[$index]", "nombre vacío")
        if (name in uniqueMuscles) invalid("muscles[$index]", "músculo repetido: $name")
        uniqueMuscles += name
    }

    var exercises: List<WebExercise> = parseExercises(root.required("exercises", "").asArray("exercises"))
    val settingsJson = root.required("settings", "").asObject("settings")
    val includeIndirect = root.optional("includeIndirect")?.asBoolean("includeIndirect") ?: false

    val rawPlans = root.optional("plans")?.asArray("plans")?.mapIndexed { index, element ->
        parsePlan(element.asObject("plans[$index]"), index)
    }
    val rawDays = root.optional("days")?.asArray("days")?.mapIndexed { index, element ->
        parseDay(element.asObject("days[$index]"), "days", index, "d")
    }

    if (rawPlans.isNullOrEmpty() && rawDays.isNullOrEmpty()) {
        throw ImportException(
            ImportError.MissingField("plans"),
        )
    }

    val warnings = mutableListOf<String>()
    if (!rawPlans.isNullOrEmpty() && !rawDays.isNullOrEmpty()) {
        warnings += "se ignoró `days` de la raíz: la web sólo lo usa cuando no hay `plans`"
    }
    warnings += "la web no guarda sesiones ni métricas corporales: el estado importado no tiene historial"
    val unknown = root.keys - ROOT_KEYS
    if (unknown.isNotEmpty()) {
        warnings += "campos desconocidos ignorados: ${unknown.sorted().joinToString(", ")}"
    }

    // --- escalera de migraciones de ensureV2() -------------------------------------
    val migrations = mutableListOf<String>()

    var plans: List<WebPlan> = if (rawPlans.isNullOrEmpty()) {
        val days = rawDays ?: emptyList()
        val rootActive = root.optional("activeDay")?.asString("activeDay")
        migrations += "sin `plans`: se envolvieron los días de la raíz como «Rutina A (original)»"
        listOf(
            WebPlan(
                id = "web-plan-1",
                name = "Rutina A (original)",
                days = days,
                activeDay = rootActive ?: days.firstOrNull()?.id,
            ),
        )
    } else {
        rawPlans
    }

    val missingExercises = WebCatalog.legacyExercises.filter { legacy -> exercises.none { it.id == legacy.id } }
    if (missingExercises.isNotEmpty()) {
        exercises += missingExercises.map { legacy -> legacy.toExercise() }
        migrations += "ejercicios agregados: ${missingExercises.joinToString(", ") { it.id }}"
    }
    val multiFilled = exercises.count { it.explicitCompound == null }
    if (multiFilled > 0) {
        exercises = exercises.map { exercise ->
            if (exercise.explicitCompound != null) {
                exercise
            } else {
                exercise.copy(explicitCompound = exercise.id in WebCatalog.multiIds)
            }
        }
        migrations += "multiarticular inferido en $multiFilled ejercicios (`MULTI` de ensureV2)"
    }

    var huMigrated = false
    if (version == 0) {
        exercises = exercises.map { exercise ->
            if (exercise.id == "hu" && exercise.secondsPerSet == 35) {
                huMigrated = true
                exercise.copy(secondsPerSet = 60)
            } else {
                exercise
            }
        }
        if (huMigrated) migrations += "hu.work 35 → 60 (migración `if(!o.v)`, ver docs/paridad-web.md)"
    }

    if (version < 3) {
        if (version == 2) {
            plans = plans.map { plan ->
                if (plan.name == WebCatalog.PLAN_B_NAME) {
                    plan.copy(name = "Rutina B v1 (superseries con músculos compartidos)")
                } else {
                    plan
                }
            }
            migrations += "Rutina B renombrada a «Rutina B v1 (superseries con músculos compartidos)» (v2)"
        }
        plans = plans + listOf(
            WebPlan(
                id = "web-plan-b",
                name = WebCatalog.PLAN_B_NAME,
                days = WebCatalog.planBDays.mapIndexed { index, signature ->
                    dayFromSignature(signature, "web-b-${index + 1}", "Día ${index + 1}")
                },
                activeDay = "web-b-1",
            ),
        )
        migrations += "Rutina B agregada (planBDays, v < 3)"
    }

    val patched = mutableListOf<String>()
    plans = plans.map { plan ->
        if (plan.name != WebCatalog.PLAN_B_NAME) {
            return@map plan
        }
        var days = plan.days
        WebCatalog.dayPatches.forEach { patch ->
            if (version < patch.minVersion || version > patch.maxVersion) return@forEach
            val day = days.getOrNull(patch.dayIndex) ?: return@forEach
            if (signatureOf(day.items) !in patch.signatures) return@forEach
            days = days.toMutableList().also { list ->
                list[patch.dayIndex] = dayFromSignature(
                    WebCatalog.planBDays[patch.dayIndex],
                    day.id,
                    day.name,
                )
            }
            patched += "día ${patch.dayIndex + 1}"
        }
        plan.copy(days = days)
    }
    if (patched.isNotEmpty()) {
        migrations += "Rutina B: días reescritos por firma vieja (${patched.joinToString(", ")})"
    }

    if (version in 3..19) {
        plans = plans.map { plan ->
            if (plan.name != WebCatalog.PLAN_B_NAME) return@map plan
            plan.copy(
                days = plan.days.map { day ->
                    day.copy(items = day.items.map { item -> if (item.dropSet) item.copy(dropSet = false) else item })
                },
            )
        }
        migrations += "Rutina B: drop sets desactivados (v ≤ 19)"
    }

    if (version in 3..20) {
        var replaced = 0
        plans = plans.map { plan ->
            if (plan.name != WebCatalog.PLAN_B_NAME) return@map plan
            plan.copy(
                days = plan.days.map { day ->
                    day.copy(
                        items = day.items.map { item ->
                            if (item.exerciseId == "ha") {
                                replaced++
                                item.copy(exerciseId = "vl")
                            } else {
                                item
                            }
                        },
                    )
                },
            )
        }
        if (replaced > 0) migrations += "Rutina B: ha → vl en $replaced ítems (v ≤ 20)"
    }

    if (version != WebCatalog.VERSION) migrations += "versión final: ${WebCatalog.VERSION}"

    // --- normalización de curPlan / activeDay (final de ensureV2) ------------------
    val requestedCur = root.optional("curPlan")?.asString("curPlan")
    val cur = plans.firstOrNull { it.id == requestedCur } ?: plans.first()
    val normalized = plans.map { plan ->
        val activeDay = plan.activeDay?.takeIf { id -> plan.days.any { it.id == id } } ?: plan.days.firstOrNull()?.id
        plan.copy(activeDay = activeDay)
    }

    // --- materialización al modelo de dominio -------------------------------------
    val exerciseById = exercises.associateBy { it.id }
    val domainExercises = exercises.map { it.toExercise() }
    val domainMuscles = uniqueMuscles.mapIndexed { index, name -> Muscle(name, name, position = index) }

    val routines = normalized.map { plan ->
        Routine(
            id = plan.id,
            name = plan.name,
            days = plan.days.map { day ->
                RoutineDay(
                    id = day.id,
                    name = day.name,
                    items = day.items.map { item ->
                        val exercise = exerciseById[item.exerciseId]
                            ?: invalid("plans[${plan.id}].days[${day.id}].items[${item.id}]",
                                "no existe el ejercicio ${item.exerciseId}")
                        DayItem(
                            id = item.id,
                            exercise = exercise.toExercise(),
                            sets = item.sets,
                            dropSet = item.dropSet,
                            supersetNext = item.supersetNext,
                        )
                    },
                )
            },
            activeDayId = plan.activeDay,
            position = normalized.indexOf(plan),
        )
    }

    WebImportResult(
        snapshot = DomainSnapshot(
            settings = Settings(
                warm = settingsJson.optional("warm")?.asInt("settings.warm") ?: Settings.DEFAULT.warm,
                trans = settingsJson.optional("trans")?.asInt("settings.trans") ?: Settings.DEFAULT.trans,
                drops = settingsJson.optional("drops")?.asInt("settings.drops") ?: Settings.DEFAULT.drops,
                dropSec = settingsJson.optional("dropSec")?.asInt("settings.dropSec") ?: Settings.DEFAULT.dropSec,
                dropVol = settingsJson.optional("dropVol")?.asDouble("settings.dropVol") ?: Settings.DEFAULT.dropVol,
                optMin = settingsJson.optional("optMin")?.asInt("settings.optMin") ?: Settings.DEFAULT.optMin,
                optMax = settingsJson.optional("optMax")?.asInt("settings.optMax") ?: Settings.DEFAULT.optMax,
                includeIndirect = includeIndirect,
            ),
            muscles = domainMuscles,
            exercises = domainExercises,
            routines = routines,
            activeRoutineId = cur.id,
        ),
        sourceVersion = version,
        migrations = migrations,
        warnings = warnings,
    )
}

// --- estado intermedio (mutable donde la migración lo necesita) -------------------

private data class WebPlan(
    val id: String,
    val name: String,
    val days: List<WebDay>,
    val activeDay: String?,
)

private data class WebDay(
    val id: String,
    val name: String,
    val items: List<WebItem>,
)

private data class WebItem(
    val id: String,
    val exerciseId: String,
    val sets: Int,
    val dropSet: Boolean,
    val supersetNext: Boolean,
)

/** Firma de un día en el formato de las migraciones: `ex:sets:ds:ss` unidos por `|`. */
private fun signatureOf(items: List<WebItem>): String = items.joinToString("|") { item ->
    "${item.exerciseId}:${item.sets}:${if (item.dropSet) 1 else 0}:${if (item.supersetNext) 1 else 0}"
}

private fun dayFromSignature(signature: List<String>, dayId: String, dayName: String): WebDay = WebDay(
    id = dayId,
    name = dayName,
    items = signature.mapIndexed { index, part ->
        val parts = part.split(":")
        WebItem(
            id = "$dayId-${index + 1}",
            exerciseId = parts[0],
            sets = parts[1].toInt(),
            dropSet = parts[2] == "1",
            supersetNext = parts[3] == "1",
        )
    },
)

// --- lectura del formato de la web -------------------------------------------------

/** Ejercicio tal como lo guarda la web, con `multi` opcional (puede no estar antes de v1). */
private data class WebExercise(
    val id: String,
    val name: String,
    val primary: String,
    val secondaries: List<Pair<String, Double>>,
    val secondsPerSet: Int,
    val restSeconds: Int,
    val explicitCompound: Boolean?,
) {
    fun toExercise(): Exercise = Exercise(
        id = id,
        name = name,
        primaryMuscleId = primary,
        secondaries = secondaries.map { Secondary(it.first, it.second) },
        secondsPerSet = secondsPerSet,
        restSeconds = restSeconds,
        compound = explicitCompound ?: false,
        position = 0,
    )
}

private fun WebCatalog.LegacyExercise.toExercise(): WebExercise = WebExercise(
    id = id,
    name = name,
    primary = primary,
    secondaries = secondaries,
    secondsPerSet = work,
    restSeconds = rest,
    explicitCompound = compound,
)

private fun parseExercises(array: JsonArray): MutableList<WebExercise> {
    val out = mutableListOf<WebExercise>()
    array.forEachIndexed { index, element ->
        val path = "exercises[$index]"
        val row = element.asObject(path)
        val secondaries = row.optional("secondary")?.asArray("$path.secondary")?.mapIndexed { sIndex, s ->
            val sPath = "$path.secondary[$sIndex]"
            val obj = s.asObject(sPath)
            obj.required("m", sPath).asString("$sPath.m") to obj.required("f", sPath).asDouble("$sPath.f")
        } ?: emptyList()
        out += WebExercise(
            id = row.required("id", path).asString("$path.id"),
            name = row.optional("name")?.asString("$path.name") ?: "",
            primary = row.required("primary", path).asString("$path.primary"),
            secondaries = secondaries,
            secondsPerSet = row.optional("work")?.asInt("$path.work") ?: 0,
            restSeconds = row.optional("rest")?.asInt("$path.rest") ?: 0,
            explicitCompound = row.optional("multi")?.asBoolean("$path.multi"),
        )
    }
    val seen = mutableSetOf<String>()
    out.forEachIndexed { index, exercise ->
        if (exercise.id.isBlank()) invalid("exercises[$index].id", "id vacío")
        if (!seen.add(exercise.id)) invalid("exercises[$index].id", "id repetido: ${exercise.id}")
    }
    return out
}

private fun parsePlan(element: JsonObject, index: Int): WebPlan {
    val path = "plans[$index]"
    val id = element.optional("id")?.asString("$path.id") ?: "web-plan-${index + 1}"
    val name = element.optional("name")?.asString("$path.name") ?: "Plan ${index + 1}"
    val days = element.optional("days")?.asArray("$path.days")?.mapIndexed { dayIndex, day ->
        parseDay(day.asObject("$path.days[$dayIndex]"), "$path.days", dayIndex, "web-d${index + 1}")
    } ?: emptyList()
    return WebPlan(
        id = id,
        name = name,
        days = days,
        activeDay = element.optional("activeDay")?.asString("$path.activeDay"),
    )
}

private fun parseDay(element: JsonObject, parent: String, index: Int, prefix: String): WebDay {
    val path = "$parent[$index]"
    val id = element.optional("id")?.asString("$path.id") ?: "$prefix-${index + 1}"
    val name = element.optional("name")?.asString("$path.name") ?: "Día ${index + 1}"
    val items = element.optional("items")?.asArray("$path.items")?.mapIndexed { itemIndex, item ->
        val itemPath = "$path.items[$itemIndex]"
        val obj = item.asObject(itemPath)
        val ex = obj.required("ex", itemPath).asString("$itemPath.ex")
        WebItem(
            id = obj.optional("id")?.asString("$itemPath.id") ?: "$id-i${itemIndex + 1}",
            exerciseId = ex,
            sets = obj.optional("sets")?.asInt("$itemPath.sets") ?: 3,
            dropSet = obj.optional("ds")?.asBoolean("$itemPath.ds") ?: false,
            supersetNext = obj.optional("ss")?.asBoolean("$itemPath.ss") ?: false,
        )
    } ?: emptyList()
    return WebDay(id = id, name = name, items = items)
}
