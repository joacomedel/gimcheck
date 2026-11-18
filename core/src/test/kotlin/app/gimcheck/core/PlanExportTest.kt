package app.gimcheck.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `exportPlan`: el plan en el formato de la web (`buildPlanJson()`, HTML 455-466).
 *
 * El punto no es sólo que el JSON tenga las claves: el `resumen` que publica la web
 * (minutos y series por día, series por músculo) tiene que salir igual, porque es lo
 * que el dueño usa para comparar planes pegando el texto.
 */
class PlanExportTest {

    private val snapshot: DomainSnapshot = importWeb(fixture("web/web_v21.json")).valueOrThrow().snapshot
    private val routine: Routine = snapshot.activeRoutine()!!

    private fun plan(name: String = "Rutina A (original)"): JsonObject =
        Json.parseToJsonElement(exportPlan(routine, snapshot.muscles, snapshot.settings))
            .jsonObject

    @Test
    fun `el plan tiene el contrato de la web`() {
        val root = plan()
        assertEquals(
            listOf("app", "tipo", "version", "plan", "ejercicios", "ajustes", "resumen"),
            root.keys.toList(),
        )
        assertEquals("rutinas-gimnasio", root["app"]!!.jsonPrimitive.content)
        assertEquals("plan", root["tipo"]!!.jsonPrimitive.content)
        assertEquals(1, root["version"]!!.jsonPrimitive.int)

        val plan = root["plan"]!!.jsonObject
        assertEquals("Rutina A (original)", plan["nombre"]!!.jsonPrimitive.content)
        val days = plan["dias"]!!.jsonArray
        assertEquals(6, days.size)
        assertEquals(6, days.first().jsonObject["ejercicios"]!!.jsonArray.size)
        // La web referencia los ejercicios por nombre, no por id.
        assertEquals(
            "Press plano",
            days.first().jsonObject["ejercicios"]!!.jsonArray.first().jsonObject["ejercicio"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `el resumen coincide con dayStats y volume`() {
        val resumen = plan()["resumen"]!!.jsonObject
        val days = routine.days.map { dayStats(it.items, snapshot.settings) }

        assertEquals(252, resumen["minutosSemana"]!!.jsonPrimitive.int) // 15100 s
        assertEquals(106, resumen["seriesSemana"]!!.jsonPrimitive.int)

        val porDia = resumen["minutosPorDia"]!!.jsonArray.map { it.jsonObject }
        assertEquals(6, porDia.size)
        assertEquals(listOf(42, 43, 42, 48, 42, 35), porDia.map { it["minutos"]!!.jsonPrimitive.int })
        assertEquals(listOf(18, 18, 18, 18, 21, 13), porDia.map { it["series"]!!.jsonPrimitive.int })

        val porMusculo = resumen["seriesPorMusculo"]!!.jsonArray.map { it.jsonObject }
        assertEquals(15, porMusculo.size)
        val chest = porMusculo.first { it["musculo"]!!.jsonPrimitive.content == "Pecho" }
        // La web redondea a un decimal a la hora de exportar (`r1`): 1,75 → 1,8.
        assertEquals(16.0, chest["directas"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(1.8, chest["indirectas"]!!.jsonPrimitive.double, 1e-9)
        assertTrue(porMusculo.none { it["directas"]!!.jsonPrimitive.double + it["indirectas"]!!.jsonPrimitive.double == 0.0 })
    }

    @Test
    fun `los ejercicios y ajustes del plan salen del catalogo`() {
        val root = plan()
        val exercises = root["ejercicios"]!!.jsonArray.map { it.jsonObject }
        // Sólo los ejercicios que usa la rutina, sin repetir (la web: `used`).
        assertEquals(25, exercises.size)
        assertEquals(exercises.size, exercises.map { it["nombre"]!!.jsonPrimitive.content }.distinct().size)

        val press = exercises.first { it["nombre"]!!.jsonPrimitive.content == "Press plano" }
        assertEquals("Pecho", press["musculoPrincipal"]!!.jsonPrimitive.content)
        assertEquals(45, press["segundosPorSerie"]!!.jsonPrimitive.int)
        assertEquals(120, press["descansoSegundos"]!!.jsonPrimitive.int)
        assertTrue(press["multiarticular"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(2, press["musculosSecundarios"]!!.jsonArray.size)

        val ajustes = root["ajustes"]!!.jsonObject
        assertEquals(listOf("warm", "trans", "drops", "dropSec", "dropVol", "optMin", "optMax"), ajustes.keys.toList())
        assertEquals(0.5, ajustes["dropVol"]!!.jsonPrimitive.double, 1e-9)
    }

    @Test
    fun `el export de plan es determinista`() {
        assertEquals(
            exportPlan(routine, snapshot.muscles, snapshot.settings),
            exportPlan(routine, snapshot.muscles, snapshot.settings),
        )
        // Compacto vs indentado: mismo contenido, distinto string.
        val pretty = exportPlan(routine, snapshot.muscles, snapshot.settings, pretty = true)
        val compact = exportPlan(routine, snapshot.muscles, snapshot.settings, pretty = false)
        assertTrue(pretty.length > compact.length)
        assertEquals(Json.parseToJsonElement(pretty), Json.parseToJsonElement(compact))
    }

    @Test
    fun `el plan de la Rutina B no rompe con superseries`() {
        val planB = snapshot.routines.first { it.name == WebCatalog.PLAN_B_NAME }
        val root = Json.parseToJsonElement(exportPlan(planB, snapshot.muscles, snapshot.settings)).jsonObject
        val firstDay = root["plan"]!!.jsonObject["dias"]!!.jsonArray.first().jsonObject
        val items = firstDay["ejercicios"]!!.jsonArray.map { it.jsonObject }

        assertEquals(8, items.size)
        assertEquals(4, items.count { it["superserieConSiguiente"]!!.jsonPrimitive.content.toBoolean() })
        // La Rutina B usa 8 ejercicios distintos por día; el catálogo exportado es el del plan.
        assertTrue(root["ejercicios"]!!.jsonArray.size >= 8)
        assertEquals(24, root["resumen"]!!.jsonObject["minutosPorDia"]!!.jsonArray.first().jsonObject["series"]!!.jsonPrimitive.int)
    }
}
