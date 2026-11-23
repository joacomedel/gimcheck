#!/usr/bin/env python3
"""Copia el backup real del dueño a `app/src/main/assets/seed.json`.

Por qué cambió (GIM-45): el dueño pidió que sus **dos** rutinas vinieran
precargadas. Antes este script recortaba el estado por defecto del HTML a la
Rutina A; ahora el seed es el backup que exportó de su navegador el 2026-11-18
(`owner_backup_v21.json`, formato `localStorage["rutinas-gym-v1"]`, v21):
Rutina A + Rutina B, 6 días cada una, 32 ejercicios y 15 músculos.

El archivo es evidencia, no un fixture generado: se copia **tal cual** y el
importador que lo carga es `importWeb` de `:core`, el mismo que valida
`OwnerBackupTest`. El script sólo comprueba la forma antes de copiarlo.

Uso:

    python3 scripts/gen-seed.py                 # regenera los fixtures web y copia el seed
    python3 scripts/gen-seed.py --no-regenerate # sólo copia el seed
"""

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_GENERATOR = ROOT / "core/src/test/resources/web/gen_fixture_web.py"
OWNER_BACKUP = ROOT / "core/src/test/resources/web/owner_backup_v21.json"
OUT = ROOT / "app/src/main/assets/seed.json"

EXPECTED_VERSION = 21
EXPECTED_PLANS = 2
EXPECTED_DAYS_PER_PLAN = 6
EXPECTED_EXERCISES = 32
EXPECTED_MUSCLES = 15


def main() -> None:
    if "--no-regenerate" not in sys.argv:
        print("regenerando", FIXTURE_GENERATOR.relative_to(ROOT), "desde el HTML…")
        subprocess.run([sys.executable, str(FIXTURE_GENERATOR)], check=True, cwd=ROOT)

    text = OWNER_BACKUP.read_text(encoding="utf-8")
    state = json.loads(text)

    if state.get("v") != EXPECTED_VERSION:
        raise SystemExit("el backup tiene v=%r, se esperaba %d" % (state.get("v"), EXPECTED_VERSION))

    plans = state.get("plans") or []
    if len(plans) != EXPECTED_PLANS:
        raise SystemExit("el backup tiene %d planes, se esperaban %d" % (len(plans), EXPECTED_PLANS))
    for plan in plans:
        days = plan.get("days") or []
        if len(days) != EXPECTED_DAYS_PER_PLAN:
            raise SystemExit(
                "el plan %r tiene %d días, se esperaban %d"
                % (plan.get("name"), len(days), EXPECTED_DAYS_PER_PLAN)
            )

    exercises = state.get("exercises") or []
    muscles = state.get("muscles") or []
    if len(exercises) != EXPECTED_EXERCISES:
        raise SystemExit("el catálogo tiene %d ejercicios, se esperaban %d" % (len(exercises), EXPECTED_EXERCISES))
    if len(muscles) != EXPECTED_MUSCLES:
        raise SystemExit("el catálogo tiene %d músculos, se esperaban %d" % (len(muscles), EXPECTED_MUSCLES))

    known = {exercise["id"] for exercise in exercises}
    for plan in plans:
        used = {item["ex"] for day in plan["days"] for item in day["items"]}
        unknown = used - known
        if unknown:
            raise SystemExit(
                "el plan %r usa ejercicios que no están en el catálogo: %s"
                % (plan["name"], sorted(unknown))
            )
    if state.get("curPlan") not in {plan["id"] for plan in plans}:
        raise SystemExit("curPlan=%r no está entre los planes" % state.get("curPlan"))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")

    sets = sum(item["sets"] for plan in plans for day in plan["days"] for item in day["items"])
    print(
        "%-40s %d planes · %d días · %d ejercicios · %d músculos · %d series"
        % (
            OUT.relative_to(ROOT),
            len(plans),
            sum(len(plan["days"]) for plan in plans),
            len(exercises),
            len(muscles),
            sets,
        )
    )
    print("v=%s · activo %r" % (state.get("v"), next(p["name"] for p in plans if p["id"] == state["curPlan"])))


if __name__ == "__main__":
    main()
