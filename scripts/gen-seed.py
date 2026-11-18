#!/usr/bin/env python3
"""Genera `app/src/main/assets/seed.json` desde el estado por defecto del HTML.

Por qué existe: el seed de la app **no se escribe a mano**. Sale del mismo estado de
`Rutinas de Gimnasio.html` que ya validan los tests de `:core` (`web_v21.json`, que
produce `core/src/test/resources/web/gen_fixture_web.py` leyendo los literales
`seed()`, `ensureV2()` y `planBDays()` del HTML). Así el dato que la app carga al
arrancar es exactamente el que se comparó contra la web, no una copia nueva que se
puede desincronizar.

Qué recorta respecto del estado completo:

  - **sólo la Rutina A** (6 días, `Día 1..6`), que es la que miden los números de
    `docs/paridad-web.md` (15.100 s y 106 series por semana). La Rutina B existe en el
    HTML pero no entra en el seed de v1: son dos planes de ejemplo y el producto
    arranca con el original.
  - el catálogo completo queda como está: **15 músculos y 32 ejercicios** (los 25 de
    `seed()` más los 7 que agrega `ensureV2()`), porque la Rutina A no usa todos.

El formato de salida es el JSON de `localStorage["rutinas-gym-v1"]` (v = 21), el mismo
que importa `importWeb` de `:core`. La app no tiene un formato de seed propio: usa el
importador que ya está probado contra la web.

Uso:

    python3 scripts/gen-seed.py                 # regenera el fixture y el seed
    python3 scripts/gen-seed.py --no-regenerate # sólo rearma el seed desde web_v21.json
"""

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_GENERATOR = ROOT / "core/src/test/resources/web/gen_fixture_web.py"
WEB_STATE = ROOT / "core/src/test/resources/web/web_v21.json"
OUT = ROOT / "app/src/main/assets/seed.json"

PLAN_A = "Rutina A (original)"

# Lo que verifican los tests de la app y `docs/paridad-web.md`.
EXPECTED_DAYS = 6
EXPECTED_EXERCISES = 32
EXPECTED_MUSCLES = 15


def main():
    if "--no-regenerate" not in sys.argv:
        print("regenerando", FIXTURE_GENERATOR.relative_to(ROOT), "desde el HTML…")
        subprocess.run([sys.executable, str(FIXTURE_GENERATOR)], check=True, cwd=ROOT)

    state = json.loads(WEB_STATE.read_text(encoding="utf-8"))

    plan_a = [plan for plan in state["plans"] if plan["name"] == PLAN_A]
    if len(plan_a) != 1:
        raise SystemExit(
            "se esperaba exactamente un plan %r en %s, hay %d"
            % (PLAN_A, WEB_STATE.relative_to(ROOT), len(plan_a))
        )
    plan_a = plan_a[0]

    seed = {
        "v": state["v"],
        "settings": state["settings"],
        "muscles": state["muscles"],
        "exercises": state["exercises"],
        "includeIndirect": state["includeIndirect"],
        "plans": [plan_a],
        "curPlan": plan_a["id"],
    }

    days = plan_a["days"]
    if len(days) != EXPECTED_DAYS:
        raise SystemExit("la Rutina A tiene %d días, se esperaban %d" % (len(days), EXPECTED_DAYS))
    if len(seed["exercises"]) != EXPECTED_EXERCISES:
        raise SystemExit(
            "el catálogo tiene %d ejercicios, se esperaban %d"
            % (len(seed["exercises"]), EXPECTED_EXERCISES)
        )
    if len(seed["muscles"]) != EXPECTED_MUSCLES:
        raise SystemExit(
            "el catálogo tiene %d músculos, se esperaban %d"
            % (len(seed["muscles"]), EXPECTED_MUSCLES)
        )
    used = {item["ex"] for day in days for item in day["items"]}
    unknown = used - {exercise["id"] for exercise in seed["exercises"]}
    if unknown:
        raise SystemExit("la Rutina A usa ejercicios que no están en el catálogo: %s" % sorted(unknown))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(seed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    sets = sum(item["sets"] for day in days for item in day["items"])
    print(
        "%-40s %d días · %d ejercicios · %d músculos · %d series"
        % (OUT.relative_to(ROOT), len(days), len(seed["exercises"]), len(seed["muscles"]), sets)
    )
    print("v=%s · plan %r · %d ítems" % (seed["v"], plan_a["name"], sum(len(d["items"]) for d in days)))


if __name__ == "__main__":
    main()
