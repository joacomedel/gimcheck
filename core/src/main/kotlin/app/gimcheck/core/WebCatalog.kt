package app.gimcheck.core

/**
 * Catálogo y tabla de migraciones del formato de `Rutinas de Gimnasio.html`.
 *
 * **Generado**, no editar a mano: lo escribe
 * `core/src/test/resources/web/gen_fixture_web.py` leyendo los literales del HTML
 * (`seed()`, `planBDays()`, `ensureV2()`). Regenerar con:
 *
 *     python3 core/src/test/resources/web/gen_fixture_web.py
 *
 * Existe en `main` y no en el test porque el importador de verdad (`importWeb`)
 * necesita agregar los 7 ejercicios y la Rutina B a los backups viejos, igual que
 * `ensureV2()`. Tenerlo generado evita que la tabla se desincronice del HTML.
 */
internal object WebCatalog {
    /** `var LS_KEY` de la web: la clave de `localStorage` donde vive el estado. */
    const val LS_KEY: String = "rutinas-gym-v1"

    /** `o.v` de la última versión del HTML: el techo de las migraciones. */
    const val VERSION: Int = 21

    /** `MULTI` de `ensureV2()`: los multiarticulares del catálogo de ejemplo. */
    val multiIds: Set<String> = linkedSetOf("pp", "dl", "pm", "dom", "dor", "pin", "hip")

    data class LegacyExercise(
        val id: String,
        val name: String,
        val primary: String,
        val secondaries: List<Pair<String, Double>>,
        val work: Int,
        val rest: Int,
        val compound: Boolean,
    )

    /** `NEW` de `ensureV2()`: los ejercicios que se agregaron después de la v1. */
    val legacyExercises: List<LegacyExercise> = listOf(
        LegacyExercise("fon", "Fondos lastrados", "Pecho", listOf("Tríceps" to 0.5, "Hombro anterior" to 0.5), 45, 120, true),
        LegacyExercise("rem", "Remo con barra", "Espalda", listOf("Bíceps" to 0.5, "Hombro posterior" to 0.5), 45, 105, true),
        LegacyExercise("sen", "Sentadilla", "Cuádriceps", listOf("Glúteo" to 0.5, "Isquiotibiales" to 0.25), 50, 150, true),
        LegacyExercise("pdr", "Peso muerto rumano", "Isquiotibiales", listOf("Glúteo" to 0.5, "Espalda" to 0.25), 50, 120, true),
        LegacyExercise("pre", "Prensa de piernas", "Cuádriceps", listOf("Glúteo" to 0.5), 45, 105, true),
        LegacyExercise("vl", "Vuelos laterales con mancuerna", "Hombro lateral", listOf(), 30, 45, false),
        LegacyExercise("rg", "Remo Gironda", "Espalda", listOf("Bíceps" to 0.5, "Hombro posterior" to 0.25), 40, 90, true),
    )

    /** Nombre con el que `ensureV2()` crea y después reconoce la Rutina B. */
    const val PLAN_B_NAME: String = "Rutina B (más multiarticulares)"

    /** `planBDays()`: 6 días de 8 ítems, en formato de firma `ex:sets:ds:ss`. */
    val planBDays: List<List<String>> = listOf(
        listOf("pp:4:0:1", "ant:3:0:0", "pin:3:0:1", "bmq:3:0:0", "fon:3:0:1", "absf:3:0:0", "tpb:2:0:1", "vl:3:0:0"),
        listOf("dl:4:0:1", "absf:3:0:0", "rg:3:0:1", "vl:3:0:0", "po:3:0:1", "bm:3:0:0", "vl:3:0:1", "ant:3:0:0"),
        listOf("hip:3:0:1", "vl:3:0:0", "isq:3:0:1", "cua:3:0:0", "abd:3:0:1", "adu:3:0:0", "gem:3:0:1", "crun:3:0:0"),
        listOf("dor:3:0:1", "gem:3:0:0", "pp:3:0:1", "ant:3:0:0", "pm:4:0:1", "apo:4:0:0", "tpa:3:0:1", "bmq:3:0:0"),
        listOf("hip:3:0:1", "apo:3:0:0", "isq:4:0:1", "cua:3:0:0", "abd:3:0:1", "adu:3:0:0", "gem:3:0:1", "crun:3:0:0"),
        listOf("dor:3:0:1", "gem:3:0:0", "pp:3:0:1", "ant:3:0:0", "pin:3:0:1", "bmq:3:0:0", "tsc:2:0:1", "vl:3:0:0"),
    )

    /**
     * Reescritura de un día de la Rutina B cuando su firma coincide con una vieja
     * (`OLD*` de `ensureV2()`). `o.v` en [minVersion, maxVersion].
     */
    data class DayPatch(
        val dayIndex: Int,
        val minVersion: Int,
        val maxVersion: Int,
        val signatures: List<String>,
    )

    /** Tabla de parches, en el orden en que los aplica `ensureV2()`. */
    val dayPatches: List<DayPatch> = listOf(
        DayPatch(0, 3, 3, listOf("pp:4:1:0|pin:3:0:1|vl:3:1:0|fon:3:0:1|crun:3:0:0|tpb:2:1:0")),
        DayPatch(1, 3, 6, listOf("dl:4:0:0|rem:3:0:1|absf:3:0:0|apo:3:0:1|bm:2:1:0|po:3:0:1|gem:3:1:0", "dl:4:0:1|pm:4:0:0|rem:3:0:1|vl:3:1:0|po:3:0:1|bm:3:1:0", "dl:4:0:1|absf:3:0:0|rg:3:0:1|vl:3:1:0|ha:3:0:1|gem:3:1:0|po:3:0:1|bm:3:1:0")),
        DayPatch(2, 3, 8, listOf("sen:4:0:0|pdr:3:0:1|cua:3:1:0|hip:3:0:1|absf:3:0:0|gem:3:0:1|vl:3:1:0", "hip:3:0:1|apo:3:0:0|isq:3:0:1|cua:3:1:0|abd:3:0:1|adu:3:0:0|gem:3:1:1|crun:3:0:0")),
        DayPatch(3, 3, 14, listOf("pp:2:0:0|pm:4:1:0|dom:3:0:1|tpa:2:1:0|ap:2:0:1|apo:2:0:0|po:2:0:1|vl:3:1:0", "pp:3:0:1|dom:3:0:0|pm:4:0:1|apo:4:1:0|po:3:0:1|gem:3:1:0|tpa:2:0:1|bm:2:1:0", "pp:3:0:1|ant:3:0:0|dom:3:0:1|tpa:3:1:0|pm:4:0:1|apo:4:1:0|po:3:0:1|gem:3:1:0", "dom:3:0:1|tpa:3:1:0|pp:3:0:1|ant:3:0:0|pm:4:0:1|apo:4:1:0|po:3:0:1|gem:3:1:0", "dom:3:0:1|gem:3:1:0|pp:3:0:1|ant:3:0:0|pm:4:0:1|apo:4:1:0|tpa:3:1:1|ha:3:0:0", "dor:3:0:1|gem:3:1:0|pp:3:0:1|ant:3:0:0|pm:4:0:1|apo:4:1:0|tpa:3:1:1|ha:3:0:0")),
        DayPatch(4, 3, 15, listOf("pre:4:0:0|isq:3:0:1|abd:3:0:0|hip:3:0:1|adu:3:0:0|gem:3:1:1|crun:3:0:0")),
        DayPatch(5, 3, 17, listOf("pp:3:0:0|dor:3:0:1|pin:3:0:0|rem:3:0:1|vl:3:1:0|bmq:2:0:1|tsc:2:1:0", "pp:3:0:1|abd:3:1:0|pin:3:0:1|vl:3:1:0|dor:3:0:1|ha:3:1:0|bmq:2:0:1|tsc:2:1:0")),
        DayPatch(0, 4, 18, listOf("pp:4:0:1|ant:3:0:0|pin:3:0:1|vl:3:1:0|fon:3:0:1|absf:3:0:0|tpb:2:1:1|ant:2:0:0")),
    )
}
