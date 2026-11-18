# Fixtures del backup de la web

Salen de `Rutinas de Gimnasio.html`, no están escritos a mano. Los genera:

```sh
python3 core/src/test/resources/web/gen_fixture_web.py
```

El script lee los literales del propio HTML (`var muscles`, `var E`, los `D(...)` de
`seed()` y de `planBDays()`, `var NEW`, `MULTI`, la tabla de firmas `OLD*` de `ensureV2()`
y `LS_KEY`) y escribe tres cosas:

| Salida | Qué es |
|---|---|
| `web_v21.json` | `localStorage["rutinas-gym-v1"]` con el estado por defecto (15 músculos, 32 ejercicios, 2 planes de 6 días, `v = 21`). Es lo que un navegador guarda apenas se abre la página. |
| `web_sin_v.json` | Backup anterior a la primera migración: sin `plans`, sin `v`, con 25 ejercicios, sin `multi` y con `hu.work = 35`. Ejercita el envoltorio de días, el alta de los 7 ejercicios nuevos, `MULTI` y `hu 35 → 60`. |
| `web_v3.json` | `v = 3`, con las firmas `OLD*` de la Rutina B que `ensureV2()` reescribe, y drop sets (`v ≤ 19` los apaga). |
| `web_v20.json` | `v = 20`: la Rutina B ya es la moderna pero conserva `ha` y un drop set, para las migraciones 19 (`ds = false`) y 20 (`ha → vl`). |
| `WebCatalog.kt` | Generado en `core/src/main/kotlin/...`: los 7 ejercicios, `MULTI`, `planBDays()` y la tabla de parches por versión que usa `importWeb`. |

## Lo único que no viene del HTML

Los ids. La web los crea con `uid()` (aleatorio) en cada arranque, así que un backup real
tiene ids distintos en cada dispositivo y no sirve como fixture reproducible. Acá son
deterministas (`da1`, `db2-3`, `pa`, `pb`, …), con el mismo formato de texto corto.

## Deuda

El dueño todavía no entregó un backup real de su navegador. La importación está probada
contra el estado por defecto del HTML y contra las versiones viejas reconstruidas con las
migraciones del propio archivo; falta el contraste con un backup de uso real (días
renombrados, ejercicios propios, planes duplicados).
