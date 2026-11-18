package app.gimcheck.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Import del backup de la web (`docs/especificacion.md` §7).
 *
 * Los fixtures de `core/src/test/resources/web/` los genera
 * `gen_fixture_web.py` leyendo `Rutinas de Gimnasio.html`: no están escritos a mano.
 * Los números esperados son los de `docs/paridad-web.md` (los mismos que valida
 * `StatsParityTest` con `HtmlSeed`), así que este test comprueba que el importador
 * **reconstruye** ese estado y no sólo que parsea JSON.
 */
class WebImportTest {

    private fun import(name: String): WebImportResult =
        importWeb(fixture("web/$name")).valueOrThrow()

    /** Día web: trabajo / descanso / otros / total / series (tabla de `paridad-web.md`). */
    private data class ExpectedDay(
        val name: String,
        val work: Int,
        val rest: Int,
        val other: Int,
        val total: Int,
        val sets: Int,
    )

    private val expectedDays = listOf(
        ExpectedDay("Día 1", 735, 1320, 450, 2505, 18),
        ExpectedDay("Día 2", 660, 1455, 450, 2565, 18),
        ExpectedDay("Día 3", 750, 1290, 450, 2490, 18),
        ExpectedDay("Día 4", 710, 1665, 510, 2885, 18),
        ExpectedDay("Día 5", 825, 1230, 480, 2535, 21),
        ExpectedDay("Día 6", 515, 1155, 450, 2120, 13),
    )

    @Test
    fun `el estado por defecto de la web conserva 6 dias y 32 ejercicios`() {
        val result = import("web_v21.json")
        val snapshot = result.snapshot

        assertEquals(21, result.sourceVersion)
        assertEquals(15, snapshot.muscles.size)
        assertEquals(32, snapshot.exercises.size)
        assertEquals(2, snapshot.routines.size)

        val active = snapshot.activeRoutine()
        assertNotNull(active)
        assertEquals("Rutina A (original)", active!!.name)
        assertEquals(6, active.days.size)
        assertEquals(39, active.days.sumOf { it.items.size })

        // Los 25 del catálogo original, más los 7 que sólo existen porque `ensureV2()` los agrega.
        val ids = snapshot.exercises.map { it.id }.toSet()
        assertTrue(ids.containsAll(HtmlSeed.exercises.keys))
        assertEquals(setOf("fon", "rem", "sen", "pdr", "pre", "vl", "rg"), ids - HtmlSeed.exercises.keys)

        println("web_v21 → ${snapshot.muscles.size} músculos · ${snapshot.exercises.size} ejercicios · " +
            "${snapshot.routines.size} rutinas · ${active.days.size} días · ${active.days.sumOf { it.items.size }} ítems")
    }

    @Test
    fun `los numeros de dayStats son los de la web`() {
        val snapshot = import("web_v21.json").snapshot
        val routine = snapshot.activeRoutine()!!

        assertEquals(expectedDays.size, routine.days.size)
        routine.days.forEachIndexed { index, day ->
            val web = expectedDays[index]
            val stats = dayStats(day.items, snapshot.settings)
            assertEquals("${web.name} work", web.work, stats.workSeconds)
            assertEquals("${web.name} rest", web.rest, stats.restSeconds)
            assertEquals("${web.name} other", web.other, stats.otherSeconds)
            assertEquals("${web.name} total", web.total, stats.totalSeconds)
            assertEquals("${web.name} sets", web.sets, stats.sets)
        }
        val total = routine.days.sumOf { dayStats(it.items, snapshot.settings).totalSeconds }
        assertEquals(15100, total)
        assertEquals("4 h 12 min", formatDuration(total))
    }

    @Test
    fun `el volumen semanal es el de la web`() {
        val snapshot = import("web_v21.json").snapshot
        val rows = volume(snapshot.activeRoutine()!!.days, snapshot.settings, snapshot.muscles)

        assertEquals(15, rows.size)
        assertEquals(106.0, rows.values.sumOf { it.direct }, 1e-9)
        assertEquals(28.0, rows.values.sumOf { it.indirect }, 1e-9)

        // Tres filas de la tabla de paridad, directas/indirectas/días.
        val chest = rows.getValue("Pecho")
        assertEquals(16.0, chest.direct, 1e-9)
        assertEquals(1.75, chest.indirect, 1e-9)
        assertEquals(3, chest.days)

        val frontDelt = rows.getValue("Hombro anterior")
        assertEquals(6.0, frontDelt.direct, 1e-9)
        assertEquals(6.75, frontDelt.indirect, 1e-9)
        assertEquals(4, frontDelt.days)
        assertEquals(VolumeBand.LOW, estado(frontDelt, snapshot.settings))
        assertEquals(VolumeBand.IN_RANGE, estado(frontDelt, snapshot.settings.copy(includeIndirect = true)))

        val triceps = rows.getValue("Tríceps")
        assertEquals(10.0, triceps.direct, 1e-9)
        assertEquals(8.75, triceps.indirect, 1e-9)
        assertEquals(VolumeBand.IN_RANGE, estado(triceps, snapshot.settings))
    }

    @Test
    fun `la Rutina B llega con sus superseries y su catalogo completo`() {
        val snapshot = import("web_v21.json").snapshot
        val planB = snapshot.routines.first { it.name == WebCatalog.PLAN_B_NAME }

        assertEquals(6, planB.days.size)
        assertTrue(planB.days.all { it.items.size == 8 })
        // La Rutina B sí encadena: 4 bloques de 2 por día en la versión 21.
        assertEquals(4, planB.days.first().items.count { it.supersetNext })
        // Nada de lo que la web trae en la Rutina B usa ejercicios que no existan.
        planB.days.forEach { day ->
            day.items.forEach { item -> assertNotNull(snapshot.exercise(item.exerciseId)) }
        }
    }

    @Test
    fun `un backup sin version se migra como en el HTML`() {
        val result = import("web_sin_v.json")
        val snapshot = result.snapshot

        assertEquals(0, result.sourceVersion)
        // 25 del catálogo viejo + los 7 de ensureV2.
        assertEquals(32, snapshot.exercises.size)
        // `ensureV2` agrega la Rutina B cuando el backup no tiene versión (`!o.v || o.v<3`).
        assertEquals(2, snapshot.routines.size)
        assertEquals("Rutina A (original)", snapshot.routines.first().name)
        assertEquals(WebCatalog.PLAN_B_NAME, snapshot.routines[1].name)
        assertEquals(6, snapshot.routines.first().days.size)
        assertEquals(6, snapshot.routines[1].days.size)
        assertEquals("Rutina A (original)", snapshot.activeRoutine()!!.name)

        // `hu.work` estaba en 35 y tiene que quedar en 60 (sino la semana cambia 225 s).
        assertEquals(60, snapshot.exercise("hu")!!.secondsPerSet)
        assertTrue(result.migrations.any { it.contains("hu.work") })
        // Los ejercicios viejos no traían `multi`: se infiere con `MULTI`.
        assertTrue(result.migrations.any { it.contains("multiarticular") })
        assertTrue(snapshot.exercise("pp")!!.compound)
        assertTrue(!snapshot.exercise("hu")!!.compound)

        // Migrado, el estado tiene que dar los mismos números que la web.
        val routine = snapshot.activeRoutine()!!
        routine.days.forEachIndexed { index, day ->
            val web = expectedDays[index]
            assertEquals("${web.name} total", web.total, dayStats(day.items, snapshot.settings).totalSeconds)
        }
        val rows = volume(routine.days, snapshot.settings, snapshot.muscles)
        assertEquals(106.0, rows.values.sumOf { it.direct }, 1e-9)
    }

    @Test
    fun `v3 reescribe los dias de la Rutina B por firma`() {
        val result = import("web_v3.json")
        val planB = result.snapshot.routines.first { it.name == WebCatalog.PLAN_B_NAME }

        // Las firmas OLD* del día 1 y del día 6 se reemplazan por planBDays().
        assertEquals(
            WebCatalog.planBDays[0].map { it.substringBefore(":") },
            planB.days[0].items.map { it.exerciseId },
        )
        assertEquals(
            WebCatalog.planBDays[5].map { it.substringBefore(":") },
            planB.days[5].items.map { it.exerciseId },
        )
        assertTrue(result.migrations.any { it.contains("firmas") || it.contains("firma") })

        // v ≤ 19: los drop sets de la Rutina B quedan apagados, y `ha` ya no existe (v ≤ 20).
        assertTrue(planB.days.flatMap { it.items }.none { it.dropSet })
        assertTrue(planB.days.flatMap { it.items }.none { it.exerciseId == "ha" })
        // La Rutina A no se toca: sigue con los 6 días y los mismos números.
        val planA = result.snapshot.routines.first { it.name == "Rutina A (original)" }
        assertEquals(2505, dayStats(planA.days[0].items, result.snapshot.settings).totalSeconds)
    }

    @Test
    fun `v20 conserva los drop sets pero cambia ha por vl`() {
        val result = import("web_v20.json")
        val planB = result.snapshot.routines.first { it.name == WebCatalog.PLAN_B_NAME }
        val items = planB.days.flatMap { it.items }

        assertTrue(items.none { it.exerciseId == "ha" })
        // v = 20 no entra en la ventana 3..19: el drop set del fixture sobrevive.
        assertTrue(items.any { it.dropSet })
        assertEquals(1, items.count { it.dropSet })
    }

    @Test
    fun `un JSON roto o fuera de contrato devuelve error tipado`() {
        val broken = importWeb("{\"settings\":")
        assertTrue(broken.errorOrNull() is ImportError.NotJson)

        val rootArray = importWeb("[]")
        assertTrue(rootArray.errorOrNull() is ImportError.WrongType)

        val empty = importWeb("{}")
        assertEquals(ImportError.MissingField("muscles"), empty.errorOrNull())

        val noDays = importWeb("""{"muscles":[],"exercises":[],"settings":{}}""")
        assertEquals(ImportError.MissingField("plans"), noDays.errorOrNull())

        val future = importWeb(
            """{"muscles":["Pecho"],"exercises":[],"settings":{},"plans":[],"v":99}""",
        )
        val error = future.errorOrNull()
        assertTrue("era $error", error is ImportError.UnsupportedVersion)
        assertEquals(99, (error as ImportError.UnsupportedVersion).version)

        // El export de un plan no es un backup de datos: se rechaza con un mensaje claro.
        val plan = importWeb("""{"app":"rutinas-gimnasio","tipo":"plan","version":1}""")
        assertTrue(plan.errorOrNull() is ImportError.UnsupportedVersion)
    }

    @Test
    fun `una referencia a un ejercicio inexistente falla sin dejar estado`() {
        val json = """
            {"settings":{},"muscles":["Pecho"],"exercises":[],"v":21,}
        """.trimIndent()
        val failure = importWeb(json)
        assertTrue(failure.errorOrNull() is ImportError.NotJson)

        val dangling = """
            {"settings":{},"muscles":["Pecho"],
             "exercises":[{"id":"pp","name":"Press plano","primary":"Pecho","work":45,"rest":120}],
             "plans":[{"id":"p","name":"P","days":[{"id":"d","name":"Día 1",
               "items":[{"id":"i","ex":"noexiste","sets":3}]}]}],"v":21}
        """.trimIndent()
        val error = importWeb(dangling).errorOrNull()
        assertTrue("era $error", error is ImportError.InvalidValue)
        assertTrue(error!!.message.contains("noexiste"))

        // Un import que falla no comparte nada con el siguiente: son funciones puras.
        assertEquals(6, import("web_v21.json").snapshot.activeRoutine()!!.days.size)
    }

    @Test
    fun `un JSON de 2 MB se parsea sin explotar`() {
        val builder = StringBuilder(2_100_000)
        builder.append("{\"settings\":{\"warm\":5,\"trans\":30,\"drops\":2,\"dropSec\":25,")
        builder.append("\"dropVol\":0.5,\"optMin\":10,\"optMax\":20},")
        builder.append("\"muscles\":[\"Pecho\",\"Espalda\",\"Tríceps\"],\"exercises\":[")
        val exerciseCount = 9000
        for (index in 0 until exerciseCount) {
            if (index > 0) builder.append(',')
            builder.append(
                """{"id":"e$index","name":"Ejercicio $index","primary":"Pecho",""" +
                    """"secondary":[{"m":"Espalda","f":0.5}],"work":45,"rest":120,"multi":false}""",
            )
        }
        builder.append("],\"includeIndirect\":false,\"plans\":[{\"id\":\"p1\",\"name\":\"Grande\",\"days\":[")
        val dayCount = 3
        val itemsPerDay = 7500
        for (day in 0 until dayCount) {
            if (day > 0) builder.append(',')
            builder.append("""{"id":"d$day","name":"Día ${day + 1}","items":[""")
            for (item in 0 until itemsPerDay) {
                if (item > 0) builder.append(',')
                builder.append("""{"id":"i$day-$item","ex":"e${(day * itemsPerDay + item) % exerciseCount}","sets":3}""")
            }
            builder.append("]}")
        }
        // cierra `days`, el plan y `plans`
        builder.append("]}]" + ",\"curPlan\":\"p1\",\"v\":21}")
        val text = builder.toString()
        assertTrue("el JSON mide ${text.length} bytes", text.length > 2_000_000)

        val started = System.nanoTime()
        val result = importWeb(text)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val snapshot = result.valueOrThrow().snapshot
        val day = snapshot.activeRoutine()!!.days.first()
        val stats = dayStats(day.items, snapshot.settings)

        // + los 7 que agrega la migración de `ensureV2` (el JSON de prueba no los trae).
        assertEquals(exerciseCount + WebCatalog.legacyExercises.size, snapshot.exercises.size)
        assertEquals(dayCount, snapshot.activeRoutine()!!.days.size)
        assertEquals(itemsPerDay * 3, stats.sets)
        println("2 MB (${text.length} bytes) → ${snapshot.exercises.size} ejercicios, " +
            "${snapshot.activeRoutine()!!.days.size} días × $itemsPerDay ítems en ${elapsedMs} ms")
    }

    @Test
    fun `el import del estado de la web se puede exportar y volver a importar`() {
        val snapshot = import("web_v21.json").snapshot
        val json = exportBackup(snapshot, exportedAt = "2026-11-18T00:00:00")
        val again = importBackup(json).valueOrThrow()

        assertEquals(snapshot.muscles, again.muscles)
        assertEquals(snapshot.exercises, again.exercises)
        assertEquals(snapshot.settings, again.settings)
        assertEquals(snapshot.routines.map { it.days }, again.routines.map { it.days })
        assertEquals(json, exportBackup(again, exportedAt = "2026-11-18T00:00:00"))
    }

    @Test
    fun `los fixtures declaran de donde salen`() {
        // El JSON tiene la forma exacta de `serialize()` de la web: sin `days` en la raíz.
        val root = Json.parseToJsonElement(fixture("web/web_v21.json")).jsonObject
        assertEquals(
            listOf("settings", "muscles", "exercises", "includeIndirect", "plans", "curPlan", "v"),
            root.keys.toList(),
        )
        assertEquals(WebCatalog.LS_KEY, "rutinas-gym-v1")
        assertEquals(21, root["v"]!!.jsonPrimitive.int)
        val plans = root["plans"]!!.jsonArray
        assertEquals(2, plans.size)
        val days = plans[0].jsonObject["days"]!!.jsonArray
        assertEquals(6, days.size)
        assertEquals(6, days.count { (it as JsonObject)["items"] != null })
    }
}
