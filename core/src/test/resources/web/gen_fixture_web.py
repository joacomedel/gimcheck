#!/usr/bin/env python3
"""Genera los fixtures de importación web desde `Rutinas de Gimnasio.html`.

Por qué existe: el JSON que la web guarda en el navegador (`localStorage["rutinas-gym-v1"]`,
producido por `serialize()`) **no** se puede inventar a mano sin que el test se valide a sí
mismo. Este script lo arma leyendo los literales del propio HTML, con el mismo efecto que
tienen `seed()` y `ensureV2()` sobre el estado por defecto:

  - `var muscles=[...]`, `var E=[...]`, `var days=[...]` de `seed()`
  - `var NEW=[...]`, `MULTI` y la tabla de firmas `OLD*` de `ensureV2()`
  - los `D('Día N',[...])` de `planBDays()`
  - la clave de almacenamiento `LS_KEY`

Lo único que no se puede copiar del HTML son los ids: la web usa `uid()` (aleatorio). Acá
se usan ids deterministas (mismo formato que un backup real: texto corto), para que el
fixture sea reproducible y el test pueda comparar.

Salidas (todas en este directorio):
  web_v21.json        estado por defecto tal como lo guarda la web (v = 21)
  web_sin_v.json      backup viejo sin `v`, sólo `days`, con `hu.work = 35` (pre-ensureV2)
  web_v3.json         v = 3, con las firmas OLD* del HTML en la Rutina B
  web_v20.json        v = 20, Rutina B moderna pero con `ha` y `ds` para las migraciones 19/20

Uso:  python3 core/src/test/resources/web/gen_fixture_web.py [ruta al HTML]
"""

import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
HTML = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE.parents[4] / "Rutinas de Gimnasio.html"


# --- parser mínimo de literales JS (arrays, strings, números, true/false) -------------
class JsLiteral:
    def __init__(self, text):
        self.text = text
        self.i = 0

    def parse(self):
        value = self.value()
        self.skip()
        if self.i != len(self.text):
            raise ValueError("sobra texto en %r: %r" % (self.text, self.text[self.i:]))
        return value

    def skip(self):
        while self.i < len(self.text) and self.text[self.i] in " \t\r\n":
            self.i += 1

    def value(self):
        self.skip()
        c = self.text[self.i]
        if c == "[":
            return self.array()
        if c in "'\"":
            return self.string(c)
        if c in "-0123456789.":
            return self.number()
        for word, val in (("true", True), ("false", False), ("null", None)):
            if self.text.startswith(word, self.i):
                self.i += len(word)
                return val
        raise ValueError("token inesperado en %r" % self.text[self.i:])

    def array(self):
        self.i += 1
        out = []
        self.skip()
        if self.text[self.i] == "]":
            self.i += 1
            return out
        while True:
            out.append(self.value())
            self.skip()
            if self.text[self.i] == ",":
                self.i += 1
                continue
            if self.text[self.i] == "]":
                self.i += 1
                return out
            raise ValueError("array mal formado")

    def string(self, quote):
        self.i += 1
        out = []
        while True:
            c = self.text[self.i]
            if c == "\\":
                nxt = self.text[self.i + 1]
                out.append({"n": "\n", "t": "\t", "'": "'", '"': '"', "\\": "\\"}.get(nxt, nxt))
                self.i += 2
                continue
            if c == quote:
                self.i += 1
                return "".join(out)
            out.append(c)
            self.i += 1

    def number(self):
        m = re.compile(r"-?\d*\.?\d+(?:[eE][-+]?\d+)?").match(self.text, self.i)
        raw = m.group(0)
        self.i = m.end()
        return float(raw) if ("." in raw or "e" in raw.lower()) else int(raw)


def lit(text):
    return JsLiteral(text.strip()).parse()


def slice_between(text, start_marker, end_marker):
    start = text.index(start_marker) + len(start_marker)
    end = text.index(end_marker, start)
    return text[start:end]


def main():
    html = HTML.read_text(encoding="utf-8")

    ls_key = re.search(r"var LS_KEY='([^']+)'", html).group(1)
    seed_body = slice_between(html, "function seed(){", "function planBDays(){")
    ensure_body = slice_between(html, "function ensureV2(o){", "function serialize(")
    planb_body = slice_between(html, "function planBDays(){", "/* migra datos viejos")

    muscles = lit("[" + re.search(r"var muscles=\[(.*?)\];", seed_body, re.S).group(1) + "]")
    raw_settings = re.search(r"settings:\{(.*?)\},\s*\n", seed_body, re.S).group(1)
    settings = {k: lit(v) for k, v in re.findall(r"(\w+):([\d.]+)", raw_settings)}
    raw_exercises = lit("[" + re.search(r"var E=\[(.*?)\n  \];", seed_body, re.S).group(1) + "]")
    raw_new = lit("[" + re.search(r"var NEW=\[(.*?)\n  \];", ensure_body, re.S).group(1) + "]")
    multi_ids = lit("[" + re.search(r"var MULTI=\[(.*?)\];", ensure_body, re.S).group(1) + "]")

    # `D(name, items)` de seed() y de planBDays(): el nombre y el array de ítems.
    def d_calls(body, anchor):
        chunk = body[body.index(anchor):]
        calls = []
        for m in re.finditer(r"D\('([^']+)',\[(.*?)\]\)", chunk):
            calls.append((m.group(1), lit("[" + m.group(2) + "]")))
        return calls

    seed_days = d_calls("var days=[" + seed_body.split("var days=[", 1)[1], "D(")
    planb_days = d_calls(planb_body, "D(")

    # Firmas de migración: `var OLDn='...'` o `var OLDn=[...]`.
    olds = {}
    for m in re.finditer(r"var (OLD\d?)\s*=\s*('(?:[^']*)'|\[.*?\]);", ensure_body, re.S):
        olds[m.group(1)] = lit(m.group(2))

    def exercise_obj(row):
        ex_id, name, primary, secondary, work, rest = row[:6]
        multi = bool(row[6]) if len(row) > 6 else ex_id in multi_ids
        return {
            "id": ex_id,
            "name": name,
            "primary": primary,
            "secondary": [{"m": m, "f": f} for m, f in secondary],
            "work": work,
            "rest": rest,
            "multi": multi,
        }

    def day_obj(day_id, name, items):
        return {
            "id": day_id,
            "name": name,
            "items": [
                {
                    "id": "%s-%d" % (day_id, index + 1),
                    "ex": str(item[0]),
                    "sets": int(item[1]),
                    "ds": bool(item[2]) if len(item) > 2 else False,
                    "ss": bool(item[3]) if len(item) > 3 else False,
                }
                for index, item in enumerate(items)
            ],
        }

    def items_from_signature(signature, day_id):
        items = []
        for index, part in enumerate(signature.split("|")):
            ex_id, sets, ds, ss = part.split(":")
            items.append([ex_id, int(sets), int(ds), int(ss)])
        return day_obj(day_id, "Día %d" % 0, items)

    # ensureV2: catálogo final = E + NEW, con `multi` completado y hu.work 35→60 (`!o.v`).
    exercises = [exercise_obj(row) for row in raw_exercises]
    exercises += [exercise_obj(row) for row in raw_new]
    for ex in exercises:
        if ex["id"] == "hu" and ex["work"] == 35:
            ex["work"] = 60

    plan_a_days = [day_obj("da%d" % (i + 1), name, items) for i, (name, items) in enumerate(seed_days)]
    plan_b_days = [day_obj("db%d" % (i + 1), name, items) for i, (name, items) in enumerate(planb_days)]

    def web_state(version, with_plans=True, days=None, plan_b=None, cur="pa", include_indirect=False):
        state = {
            "settings": settings,
            "muscles": muscles,
            "exercises": exercises,
            "includeIndirect": include_indirect,
        }
        if with_plans:
            state["plans"] = [
                {"id": "pa", "name": "Rutina A (original)", "days": plan_a_days, "activeDay": plan_a_days[0]["id"]},
                {"id": "pb", "name": "Rutina B (más multiarticulares)",
                 "days": plan_b or plan_b_days, "activeDay": (plan_b or plan_b_days)[0]["id"]},
            ]
            state["curPlan"] = cur
        else:
            state["days"] = days
            state["activeDay"] = days[0]["id"]
        state["v"] = version
        return state

    def write(name, data):
        path = HERE / name
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("%-14s %7d bytes" % (name, path.stat().st_size))

    # 1. Estado por defecto (seed() + ensureV2 con o.v = 21).
    write("web_v21.json", web_state(21))

    # 2. Backup viejo: sin `plans`, sin `v`, `hu.work` todavía 35 y sin los 7 ejercicios
    #    de ensureV2 (es lo que había en el navegador antes de la primera migración).
    legacy_exercises = [
        {k: v for k, v in ex.items() if k != "multi"}
        for ex in (exercise_obj(row) for row in raw_exercises)
    ]
    for ex in legacy_exercises:
        if ex["id"] == "hu":
            ex["work"] = 35
    legacy = web_state(None, with_plans=False, days=plan_a_days)
    legacy.pop("v")
    legacy["exercises"] = legacy_exercises
    write("web_sin_v.json", legacy)

    # 3. v = 3: la Rutina B tiene las firmas viejas que el HTML reescribe (`OLD*`).
    old_order = ["OLD", "OLD2", "OLD3", "OLD4", "OLD5", "OLD6"]
    plan_b_old = []
    for index, key in enumerate(old_order):
        value = olds[key]
        signature = value[0] if isinstance(value, list) else value
        plan_b_old.append(items_from_signature(signature, "db%d" % (index + 1)))
        for day in plan_b_old:
            for position, item in enumerate(day["items"], start=1):
                item["id"] = "%s-%d" % (day["id"], position)
    write("web_v3.json", web_state(3, plan_b=plan_b_old))

    # 4. v = 20: Rutina B ya moderna, pero con `ha` y drop sets: las migraciones 19 (ds) y
    #    20 (ha→vl) siguen vigentes y las firmas ya no matchean.
    plan_b_modern = json.loads(json.dumps(plan_b_days))
    plan_b_modern[0]["items"][1]["ex"] = "ha"
    plan_b_modern[2]["items"][3]["ex"] = "ha"
    plan_b_modern[4]["items"][5]["ds"] = True
    write("web_v20.json", web_state(20, plan_b=plan_b_modern))

    print("LS_KEY=%s · %d músculos · %d ejercicios · %d días A · %d días B"
          % (ls_key, len(muscles), len(exercises), len(plan_a_days), len(planb_days)))

    emit_kotlin_catalog(ls_key, multi_ids, raw_new, planb_days, ensure_body, olds)


# --- catálogo Kotlin ---------------------------------------------------------------
KOTLIN_OUT = HERE.parents[2] / "main" / "kotlin" / "app" / "gimcheck" / "core" / "WebCatalog.kt"


def condition_window(condition):
    """`o.v===3` → (3, 3); `o.v>=3&&o.v<=6` → (3, 6)."""
    exact = re.search(r"o\.v===(\d+)", condition)
    if exact:
        return int(exact.group(1)), int(exact.group(1))
    low = re.search(r"o\.v>=(\d+)", condition)
    high = re.search(r"o\.v<=(\d+)", condition)
    if low and high:
        return int(low.group(1)), int(high.group(1))
    raise ValueError("condición no reconocida: %s" % condition)


def emit_kotlin_catalog(ls_key, multi_ids, raw_new, planb_days, ensure_body, olds):
    lines = []
    add = lines.append
    add("package app.gimcheck.core")
    add("")
    add("/**")
    add(" * Catálogo y tabla de migraciones del formato de `Rutinas de Gimnasio.html`.")
    add(" *")
    add(" * **Generado**, no editar a mano: lo escribe")
    add(" * `core/src/test/resources/web/gen_fixture_web.py` leyendo los literales del HTML")
    add(" * (`seed()`, `planBDays()`, `ensureV2()`). Regenerar con:")
    add(" *")
    add(" *     python3 core/src/test/resources/web/gen_fixture_web.py")
    add(" *")
    add(" * Existe en `main` y no en el test porque el importador de verdad (`importWeb`)")
    add(" * necesita agregar los 7 ejercicios y la Rutina B a los backups viejos, igual que")
    add(" * `ensureV2()`. Tenerlo generado evita que la tabla se desincronice del HTML.")
    add(" */")
    add("internal object WebCatalog {")
    add("    /** `var LS_KEY` de la web: la clave de `localStorage` donde vive el estado. */")
    add('    const val LS_KEY: String = "%s"' % ls_key)
    add("")
    add("    /** `o.v` de la última versión del HTML: el techo de las migraciones. */")
    add("    const val VERSION: Int = 21")
    add("")
    add("    /** `MULTI` de `ensureV2()`: los multiarticulares del catálogo de ejemplo. */")
    add("    val multiIds: Set<String> = linkedSetOf(%s)" % ", ".join('"%s"' % m for m in multi_ids))
    add("")
    add("    data class LegacyExercise(")
    add("        val id: String,")
    add("        val name: String,")
    add("        val primary: String,")
    add("        val secondaries: List<Pair<String, Double>>,")
    add("        val work: Int,")
    add("        val rest: Int,")
    add("        val compound: Boolean,")
    add("    )")
    add("")
    add("    /** `NEW` de `ensureV2()`: los ejercicios que se agregaron después de la v1. */")
    add("    val legacyExercises: List<LegacyExercise> = listOf(")
    for row in raw_new:
        ex_id, name, primary, secondary, work, rest = row[:6]
        compound = bool(row[6]) if len(row) > 6 else False
        secondaries = ", ".join('"%s" to %s' % (m, repr(f)) for m, f in secondary)
        add('        LegacyExercise("%s", "%s", "%s", listOf(%s), %d, %d, %s),'
            % (ex_id, name, primary, secondaries, work, rest, "true" if compound else "false"))
    add("    )")
    add("")
    add("    /** Nombre con el que `ensureV2()` crea y después reconoce la Rutina B. */")
    add('    const val PLAN_B_NAME: String = "Rutina B (más multiarticulares)"')
    add("")
    add("    /** `planBDays()`: 6 días de 8 ítems, en formato de firma `ex:sets:ds:ss`. */")
    add("    val planBDays: List<List<String>> = listOf(")
    for name, items in planb_days:
        signature = ", ".join(
            '"%s:%d:%d:%d"' % (item[0], item[1],
                               int(item[2]) if len(item) > 2 else 0,
                               int(item[3]) if len(item) > 3 else 0)
            for item in items
        )
        add("        listOf(%s)," % signature)
    add("    )")
    add("")
    add("    /**")
    add("     * Reescritura de un día de la Rutina B cuando su firma coincide con una vieja")
    add("     * (`OLD*` de `ensureV2()`). `o.v` en [minVersion, maxVersion].")
    add("     */")
    add("    data class DayPatch(")
    add("        val dayIndex: Int,")
    add("        val minVersion: Int,")
    add("        val maxVersion: Int,")
    add("        val signatures: List<String>,")
    add("    )")
    add("")
    add("    /** Tabla de parches, en el orden en que los aplica `ensureV2()`. */")
    add("    val dayPatches: List<DayPatch> = listOf(")
    for match in re.finditer(r"if\((o\.v[^)]*)\)\{", ensure_body):
        start = match.end()
        following = re.search(r"if\((!o\.v|o\.v)", ensure_body[start:])
        block = ensure_body[start:start + following.start()] if following else ensure_body[start:]
        old = re.search(r"var (OLD\d?)\s*=", block)
        day = re.search(r"pl\.days\[(\d+)\]", block)
        if not old or not day:
            continue
        low, high = condition_window(match.group(1))
        value = olds[old.group(1)]
        signatures = value if isinstance(value, list) else [value]
        add("        DayPatch(%d, %d, %d, listOf(%s)),"
            % (int(day.group(1)), low, high, ", ".join('"%s"' % s for s in signatures)))
    add("    )")
    add("}")

    KOTLIN_OUT.parent.mkdir(parents=True, exist_ok=True)
    KOTLIN_OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("%-14s %7d bytes" % (KOTLIN_OUT.name, KOTLIN_OUT.stat().st_size))


if __name__ == "__main__":
    main()
