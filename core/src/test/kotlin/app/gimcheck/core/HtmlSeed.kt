package app.gimcheck.core

/**
 * Datos de ejemplo de `Rutinas de Gimnasio.html` (`seed()`, líneas 146-183 del HTML),
 * generados leyendo los literales del archivo: 15 músculos, 25 ejercicios (los 32 del
 * catálogo completo incluyen los 7 que agrega `planBDays()` y que la Rutina A no usa)
 * y los 6 días del plan activo.
 *
 * Ojo: `seed()` pasa por `ensureV2()`, que sube `hu.work` de 35 a 60 (migración `!o.v`).
 * El fixture usa el valor ya migrado, que es el que la web usa para calcular.
 */
object HtmlSeed {

    val muscles: List<Muscle> = listOf(
        Muscle("Pecho", "Pecho", position = 0),
        Muscle("Espalda", "Espalda", position = 1),
        Muscle("Hombro anterior", "Hombro anterior", position = 2),
        Muscle("Hombro lateral", "Hombro lateral", position = 3),
        Muscle("Hombro posterior", "Hombro posterior", position = 4),
        Muscle("Bíceps", "Bíceps", position = 5),
        Muscle("Tríceps", "Tríceps", position = 6),
        Muscle("Antebrazo", "Antebrazo", position = 7),
        Muscle("Cuádriceps", "Cuádriceps", position = 8),
        Muscle("Isquiotibiales", "Isquiotibiales", position = 9),
        Muscle("Glúteo", "Glúteo", position = 10),
        Muscle("Gemelos", "Gemelos", position = 11),
        Muscle("Abductores", "Abductores", position = 12),
        Muscle("Aductores", "Aductores", position = 13),
        Muscle("Abdomen", "Abdomen", position = 14),
    )

    private fun ex(
        id: String,
        name: String,
        primary: String,
        work: Int,
        rest: Int,
        vararg secondary: Pair<String, Double>,
    ) = Exercise(
        id = id,
        name = name,
        primaryMuscleId = primary,
        secondaries = secondary.map { Secondary(it.first, it.second) },
        secondsPerSet = work,
        restSeconds = rest,
        compound = id in COMPOUND_IDS,
    )

    private val COMPOUND_IDS = setOf("pp", "dl", "pm", "dom", "dor", "pin", "hip")

    val exercises: Map<String, Exercise> = listOf(
        ex("pp", "Press plano", "Pecho", 45, 120, "Hombro anterior" to 0.5, "Tríceps" to 0.5),
        ex("hu", "Hombro unilateral", "Hombro lateral", 60, 60),
        ex("ap", "Aperturas de pecho", "Pecho", 35, 75, "Hombro anterior" to 0.25),
        ex("tpa", "Tríceps en polea hacia arriba", "Tríceps", 35, 75),
        ex("ant", "Antebrazo", "Antebrazo", 35, 60),
        ex("tpb", "Tríceps en polea hacia abajo", "Tríceps", 35, 75),
        ex("dl", "Dominadas lastradas", "Espalda", 45, 120, "Bíceps" to 0.5, "Antebrazo" to 0.25),
        ex("po", "Pull over", "Espalda", 35, 90, "Pecho" to 0.25, "Tríceps" to 0.25),
        ex("apo", "Apertura posterior", "Hombro posterior", 35, 75, "Espalda" to 0.25),
        ex("bm", "Bíceps martillo", "Bíceps", 35, 75, "Antebrazo" to 0.5),
        ex("ha", "Hombro anterior", "Hombro anterior", 35, 75),
        ex("bmq", "Bíceps en máquina", "Bíceps", 35, 75),
        ex("gem", "Gemelos", "Gemelos", 35, 60),
        ex("cua", "Cuádriceps en máquina", "Cuádriceps", 40, 90),
        ex("hip", "Hip thrust", "Glúteo", 45, 120, "Isquiotibiales" to 0.25),
        ex("absf", "Abs en fondo", "Abdomen", 35, 60),
        ex("crun", "Crunch en máquina", "Abdomen", 35, 60),
        ex("pm", "Press militar", "Hombro anterior", 45, 120, "Hombro lateral" to 0.5, "Tríceps" to 0.5),
        ex("dom", "Dominadas", "Espalda", 45, 120, "Bíceps" to 0.5, "Antebrazo" to 0.25),
        ex("abd", "Abductor", "Abductores", 35, 60),
        ex("adu", "Aductor", "Aductores", 35, 60),
        ex("isq", "Isquiotibiales", "Isquiotibiales", 40, 75),
        ex("dor", "Dorsalera", "Espalda", 40, 90, "Bíceps" to 0.5),
        ex("pin", "Press inclinado con mancuernas", "Pecho", 45, 105, "Hombro anterior" to 0.5, "Tríceps" to 0.5),
        ex("tsc", "Tríceps sobre la cabeza", "Tríceps", 35, 75),
    ).associateBy { it.id }

    private fun day(id: String, name: String, items: List<Triple<String, Int, Boolean>>) = RoutineDay(
        id = id,
        name = name,
        items = items.mapIndexed { index, (exerciseId, sets, dropSet) ->
            DayItem(
                id = "$id-${index + 1}",
                exercise = exercises.getValue(exerciseId),
                sets = sets,
                dropSet = dropSet,
            )
        },
    )

    /** Los 6 días del plan activo del seed, en orden. */
    val days: List<RoutineDay> = listOf(
        day("d1", "Día 1", listOf(Triple("pp", 3, false), Triple("hu", 3, false), Triple("ap", 3, false), Triple("tpa", 3, false), Triple("ant", 3, false), Triple("tpb", 3, false))),
        day("d2", "Día 2", listOf(Triple("dl", 3, false), Triple("po", 3, false), Triple("apo", 3, false), Triple("bm", 3, false), Triple("ha", 3, false), Triple("bmq", 3, false))),
        day("d3", "Día 3", listOf(Triple("gem", 3, false), Triple("hu", 3, false), Triple("cua", 3, false), Triple("hip", 3, false), Triple("absf", 3, false), Triple("crun", 3, false))),
        day("d4", "Día 4", listOf(Triple("pp", 3, false), Triple("pm", 3, false), Triple("dom", 2, false), Triple("ap", 2, false), Triple("po", 2, false), Triple("apo", 2, false), Triple("bm", 2, false), Triple("tpa", 2, false))),
        day("d5", "Día 5", listOf(Triple("gem", 3, false), Triple("hu", 3, false), Triple("abd", 3, false), Triple("absf", 3, false), Triple("adu", 3, false), Triple("crun", 3, false), Triple("isq", 3, false))),
        day("d6", "Día 6", listOf(Triple("pp", 3, false), Triple("dor", 2, false), Triple("pin", 2, false), Triple("po", 2, false), Triple("bmq", 2, false), Triple("tsc", 2, false))),
    )
}
