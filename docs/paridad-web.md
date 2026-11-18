# Paridad con la web — GIM-4

Comparación entre los números que publica `Rutinas de Gimnasio.html` (fuente de verdad) y los
que devuelve `:core` en Kotlin. Cualquier diferencia distinta de cero es un bug del port.

## Cómo se obtuvieron los valores de la web

El equipo no tiene motor de JavaScript (no hay `node`, `bun`, `deno` ni `qjs`), así que no se
pudo ejecutar la página. En su lugar:

1. Se transcribió `seed()` + `ensureV2()` + `groupsOf/dayStats/effSets/volume/status/fmt/num`
   (HTML líneas 146-183 y 323-413) a un script de cálculo desechable, con los literales
   **extraídos por regex del propio HTML**, no copiados a mano.
2. Se verificaron a mano dos días completos contra las reglas (Día 1: 735 s de trabajo,
   1320 s de descanso, 450 s de otros, 2505 s; Día 4: 710 / 1665 / 510 / 2885). Coinciden.
3. El fixture de test (`core/src/test/kotlin/app/gimcheck/core/HtmlSeed.kt`) se **generó**
   leyendo los literales del HTML con un script, para que un typo de transcripción no pueda
   coincidir consigo mismo.

Convenciones: duraciones en segundos; volumen en series efectivas; `Δ` = Kotlin − web.

Datos: Rutina A del seed (6 días, 39 ítems, 25 ejercicios distintos; el catálogo completo tiene
32 contando los 7 que agrega `planBDays()` y que la Rutina A no usa). Ajustes por defecto:
`warm 5`, `trans 30`, `drops 2`, `dropSec 25`, `dropVol 0,5`, `optMin 10`, `optMax 20`,
volumen indirecto **desactivado**. La Rutina A no tiene superseries ni drop sets.

## Duración por día (`dayStats`)

| Día | Trabajo web | Trabajo Kotlin | Δ | Descanso web | Descanso Kotlin | Δ | Otros web | Otros Kotlin | Δ | Total web | Total Kotlin | Δ | Serie web | Serie Kotlin | Δ |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Día 1 | 735 | 735 | 0 | 1320 | 1320 | 0 | 450 | 450 | 0 | **2505** (42 min) | **2505** (42 min) | 0 | 18 | 18 | 0 |
| Día 2 | 660 | 660 | 0 | 1455 | 1455 | 0 | 450 | 450 | 0 | **2565** (43 min) | **2565** (43 min) | 0 | 18 | 18 | 0 |
| Día 3 | 750 | 750 | 0 | 1290 | 1290 | 0 | 450 | 450 | 0 | **2490** (42 min) | **2490** (42 min) | 0 | 18 | 18 | 0 |
| Día 4 | 710 | 710 | 0 | 1665 | 1665 | 0 | 510 | 510 | 0 | **2885** (48 min) | **2885** (48 min) | 0 | 18 | 18 | 0 |
| Día 5 | 825 | 825 | 0 | 1230 | 1230 | 0 | 480 | 480 | 0 | **2535** (42 min) | **2535** (42 min) | 0 | 21 | 21 | 0 |
| Día 6 | 515 | 515 | 0 | 1155 | 1155 | 0 | 450 | 450 | 0 | **2120** (35 min) | **2120** (35 min) | 0 | 13 | 13 | 0 |
| **Semana** | | | | | | | | | | **15100** (4 h 12 min) | **15100** (4 h 12 min) | 0 | **106** | **106** | 0 |

Promedio por día: web `42 min` · Kotlin `42 min` · Δ 0.

## Series semanales por músculo (`volume` + `estado`)

| Músculo | Directas web | Directas Kotlin | Δ | Indirectas web | Indirectas Kotlin | Δ | Total (con indirectas) web | Total Kotlin | Δ | Días web | Días Kotlin | Δ | Estado sólo directas | Estado directas + indirectas |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Pecho | 16 | 16 | 0 | 1,75 | 1,75 | 0 | 17,75 | 17,75 | 0 | 3 | 3 | 0 | ✓ En rango | ✓ En rango |
| Espalda | 14 | 14 | 0 | 1,25 | 1,25 | 0 | 15,25 | 15,25 | 0 | 3 | 3 | 0 | ✓ En rango | ✓ En rango |
| Hombro anterior | 6 | 6 | 0 | 6,75 | 6,75 | 0 | 12,75 | 12,75 | 0 | 4 | 4 | 0 | ▼ Bajo | ✓ En rango |
| Hombro lateral | 9 | 9 | 0 | 1,5 | 1,5 | 0 | 10,5 | 10,5 | 0 | 4 | 4 | 0 | ▼ Bajo | ✓ En rango |
| Hombro posterior | 5 | 5 | 0 | 0 | 0 | 0 | 5 | 5 | 0 | 2 | 2 | 0 | ▼ Bajo | ▼ Bajo |
| Bíceps | 10 | 10 | 0 | 3,5 | 3,5 | 0 | 13,5 | 13,5 | 0 | 3 | 3 | 0 | ✓ En rango | ✓ En rango |
| Tríceps | 10 | 10 | 0 | 8,75 | 8,75 | 0 | 18,75 | 18,75 | 0 | 3 | 3 | 0 | ✓ En rango | ✓ En rango |
| Antebrazo | 3 | 3 | 0 | 3,75 | 3,75 | 0 | 6,75 | 6,75 | 0 | 3 | 3 | 0 | ▼ Muy bajo | ▼ Bajo |
| Cuádriceps | 3 | 3 | 0 | 0 | 0 | 0 | 3 | 3 | 0 | 1 | 1 | 0 | ▼ Muy bajo | ▼ Muy bajo |
| Isquiotibiales | 3 | 3 | 0 | 0,75 | 0,75 | 0 | 3,75 | 3,75 | 0 | 1 | 1 | 0 | ▼ Muy bajo | ▼ Muy bajo |
| Glúteo | 3 | 3 | 0 | 0 | 0 | 0 | 3 | 3 | 0 | 1 | 1 | 0 | ▼ Muy bajo | ▼ Muy bajo |
| Gemelos | 6 | 6 | 0 | 0 | 0 | 0 | 6 | 6 | 0 | 2 | 2 | 0 | ▼ Bajo | ▼ Bajo |
| Abductores | 3 | 3 | 0 | 0 | 0 | 0 | 3 | 3 | 0 | 1 | 1 | 0 | ▼ Muy bajo | ▼ Muy bajo |
| Aductores | 3 | 3 | 0 | 0 | 0 | 0 | 3 | 3 | 0 | 1 | 1 | 0 | ▼ Muy bajo | ▼ Muy bajo |
| Abdomen | 12 | 12 | 0 | 0 | 0 | 0 | 12 | 12 | 0 | 2 | 2 | 0 | ✓ En rango | ✓ En rango |
| **Suma** | **106** | **106** | 0 | **28** | **28** | 0 | **134** | **134** | 0 | | | | | |

«Días» es la cantidad de días con volumen: directo siempre cuenta; indirecto sólo si aporta
**1 serie efectiva o más** (la web: `if(e*s.f>=1) q.da[di]=1`).

## Casos borde (valores calculados a mano desde las reglas)

| Caso | Web (regla) | Kotlin | Δ |
|---|---|---|---|
| Grupo de 1 ítem (3×45 s, descanso 120 s) | 135 + 240 + 300 = **675 s**, 3 series | 675, 3 | 0 |
| Superserie de 3 (3 series cada uno) | 360 + 240 + 300 = **900 s**, 9 series | 900, 9 | 0 |
| Drop set (3 series, drops 2 × 25 s) | trabajo 185 s, total **725 s**, 3 series cargadas, **4,0** efectivas | 725, 3, 4,0 | 0 |
| Ítem con menos series que la ronda (3 y 2 en el mismo bloque) | 205 + 240 + 300 = **745 s**, 5 series | 745, 5 | 0 |
| Día vacío | 0 + 0 + **300 s** de calentamiento | 300 | 0 |
| Músculo sin entrenar | 0 directas, 0 indirectas, 0 días, **▼ Muy bajo** | igual | 0 |
| Descanso con override (3 series, 60 s en vez de 120 s) | 135 + 120 + 300 = **555 s** | 555 | 0 |
| Superserie sin cerrar (último ítem con `supersetNext`) | un solo grupo: **780 s**, 6 series | 780, 6 | 0 |
| El mismo par sin superserie | **1080 s** (dos bloques, +30 s de traslado) | 1080 | 0 |

## Lo que no coincidió (y por qué)

Ningún número publicado por la web difiere del de Kotlin. Sí hay dos cosas que **no son
obvias** y quedan documentadas porque explican los valores:

1. **`hu` (Hombro unilateral) trabaja 60 s, no 35 s.** El literal de `seed()` dice 35, pero
   `seed()` devuelve `ensureV2(...)`, y esa migración (`if(!o.v) … if(hu.work===35) hu.work=60`)
   lo sube a 60 antes de calcular. Los días 1, 3 y 5 lo usan 3 veces cada uno: usar 35 daría
   225 s menos de trabajo en la semana. El fixture usa 60 (valor efectivo).
2. **El último descanso se descuenta una sola vez por día**, no una por grupo: el HTML hace
   `rest -= lastRest` al final de `dayStats`, con `lastRest` de la última ronda del último
   grupo. Un día de 6 bloques sueltos cuenta 3 descansos por bloque y resta sólo el último.
   `docs/especificacion.md` §3 lo dice igual (la resta está fuera del `por grupo`), pero es la
   trampa más fácil de portar mal: descontar por grupo bajaría el descanso del Día 1 de
   1320 s a 930 s (6 bloques sueltos), y su total de 2505 s a 2115 s.

También se respeta que un **día vacío suma el calentamiento** (`warm × 60`), que es lo que
hace la web: `otro = (0 - 1 → 0) × trans + warm × 60`.

Formato de salida: `formatDuration(15100)` → `4 h 12 min` y `formatNumber(1.75)` → `1,75`
(coma decimal, sin ceros de cola, redondeo `floor(x + 0,5)` como `Math.round` de JS).

## Verificación

`./scripts/check-core.sh` (taskset 0-1, guardas de RAM/temperatura) corre
`StatsParityTest` (los números de estas tablas) y `StatsEdgeCasesTest` (los casos borde y los
formatos). El fixture `HtmlSeed.kt` se regenera leyendo el HTML si los datos de ejemplo cambian.
