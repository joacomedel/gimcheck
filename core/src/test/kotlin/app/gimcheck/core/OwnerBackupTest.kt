package app.gimcheck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El backup real del dueño, tal como lo exportó su navegador el 2026-11-18.
 *
 * `core/src/test/resources/web/owner_backup_v21.json` se commiteó **sin reformatear**: es
 * evidencia de lo que la web guardó en `localStorage["rutinas-gym-v1"]`, no un fixture
 * generado. Los ids de `uid()` son los de su dispositivo (distintos de [HtmlSeed] y de
 * `web_v21.json`), así que la comparación con el estado por defecto del HTML se hace sobre
 * el **modelo importado**, nunca sobre el texto.
 */
class OwnerBackupTest {

    private fun import(name: String): WebImportResult =
        importWeb(fixture("web/$name")).valueOrThrow()

    @Test
    fun `el backup real del dueño conserva el estado de la Rutina A`() {
        val result = import("owner_backup_v21.json")
        val snapshot = result.snapshot

        assertEquals(21, result.sourceVersion)
        assertEquals(15, snapshot.muscles.size)
        assertEquals(32, snapshot.exercises.size)
        assertEquals(2, snapshot.routines.size)

        val planA = snapshot.routines.first { it.name == "Rutina A (original)" }
        val planB = snapshot.routines.first { it.name == WebCatalog.PLAN_B_NAME }
        assertEquals(6, planA.days.size)
        assertEquals(6, planB.days.size)
        assertEquals(87, snapshot.routines.sumOf { routine -> routine.days.sumOf { it.items.size } })
        assertEquals(39, planA.days.sumOf { it.items.size })
        assertEquals(48, planB.days.sumOf { it.items.size })

        // Ajustes del dueño: los del seed, con el volumen indirecto apagado.
        assertEquals(Settings.DEFAULT, snapshot.settings)
        assertEquals(60, snapshot.exercise("hu")!!.secondsPerSet)

        // Los números de `docs/paridad-web.md` salen del backup real, no sólo del generado.
        val stats = planA.days.map { dayStats(it.items, snapshot.settings) }
        assertEquals(
            listOf(2505, 2565, 2490, 2885, 2535, 2120),
            stats.map { it.totalSeconds },
        )
        assertEquals(15100, stats.sumOf { it.totalSeconds })
        assertEquals("4 h 12 min", formatDuration(stats.sumOf { it.totalSeconds }))
        assertEquals(106, stats.sumOf { it.sets })

        val rows = volume(planA.days, snapshot.settings, snapshot.muscles)
        assertEquals(15, rows.size)
        assertEquals(106.0, rows.values.sumOf { it.direct }, 1e-9)
        assertEquals(28.0, rows.values.sumOf { it.indirect }, 1e-9)
        assertEquals(VolumeBand.IN_RANGE, estado(rows.getValue("Pecho"), snapshot.settings))
        assertEquals(VolumeBand.LOW, estado(rows.getValue("Hombro anterior"), snapshot.settings))

        println(
            "owner_backup_v21 → ${snapshot.exercises.size} ejercicios · ${snapshot.routines.size} planes · " +
                "${planA.days.size}+${planB.days.size} días · ${planA.days.sumOf { it.items.size }}+" +
                "${planB.days.sumOf { it.items.size }} ítems · semana ${stats.sumOf { it.totalSeconds }} s",
        )
    }

    @Test
    fun `el backup del dueño y el del HTML producen el mismo modelo`() {
        val owner = import("owner_backup_v21.json").snapshot
        val html = import("web_v21.json").snapshot

        // Los ids de `uid()` no se comparan: son distintos por diseño en cada dispositivo.
        assertEquals(canonical(html), canonical(owner))
    }

    /**
     * El modelo importado sin ids: nombres, orden, series, `ds`, `ss` y ajustes.
     *
     * Es una lista de líneas para que el fallo muestre la primera diferencia con contexto.
     */
    private fun canonical(snapshot: DomainSnapshot): List<String> = buildList {
        add("settings|${snapshot.settings}")
        add("activa|${snapshot.activeRoutine()?.name}")
        snapshot.muscles.forEach { add("musculo|${it.name}") }
        snapshot.exercises.forEach { exercise ->
            add(
                "ejercicio|${exercise.name}|${exercise.primaryMuscleId}|" +
                    "${exercise.secondaries.joinToString(",") { "${it.muscleId}:${it.factor}" }}|" +
                    "${exercise.secondsPerSet}|${exercise.restSeconds}|${exercise.compound}",
            )
        }
        snapshot.routines.forEach { routine ->
            add("rutina|${routine.name}")
            routine.days.forEach { day ->
                add("dia|${day.name}|${day.items.size}")
                day.items.forEach { item ->
                    add(
                        "item|${item.exercise.name}|${item.sets}|${item.dropSet}|${item.supersetNext}|" +
                            "${item.restOverrideSeconds}|${item.targetRepsMin}|${item.targetRepsMax}",
                    )
                }
            }
        }
    }

    @Test
    fun `el backup del dueño se importa sin advertencias de campos raros`() {
        val result = import("owner_backup_v21.json")

        // La escalera no tiene nada que migrar: ya viene en v21.
        assertTrue("migraciones: ${result.migrations}", result.migrations.isEmpty())
        assertTrue(
            "advertencias: ${result.warnings}",
            result.warnings.none { it.contains("desconocidos") },
        )
    }
}
