#!/usr/bin/env bash
# Gate L0/L1 de GimCheck: compila y testea :core sin Android SDK.
# Reglas del equipo: taskset 0-1, una corrida a la vez, techo de RAM y temperatura.
# Presupuesto medido (docs/build.md): 3-25 s con daemon, pico ~400 MB.
set -euo pipefail

cd "$(dirname "$0")/.."

MIN_FREE_MB=${MIN_FREE_MB:-800}
MIN_TEMP_ABORT=${MIN_TEMP_ABORT:-58}
GRADLE_BIN=${GRADLE_BIN:-/root/tools/gradle-9.9.1/bin/gradle}

max_temp_c() {
    python3 - <<'PY'
import glob
vals = []
for path in glob.glob('/sys/class/thermal/thermal_zone*/temp'):
    try:
        raw = int(open(path).read().strip())
    except (OSError, ValueError):
        continue
    if raw > 1000:
        vals.append(raw / 1000.0)
    elif raw > 0:
        vals.append(float(raw))
print(int(max(vals)) if vals else 0)
PY
}

free_mb() {
    awk '/^MemAvailable:/ { print int($2 / 1024) }' /proc/meminfo
}

temp=$(max_temp_c)
ram=$(free_mb)
echo "check-core: ${ram} MB libres, ${temp} °C"

if [ "$ram" -lt "$MIN_FREE_MB" ]; then
    echo "ABORTA: RAM libre ${ram} MB < ${MIN_FREE_MB} MB" >&2
    exit 1
fi
if [ "$temp" -ge 55 ]; then
    echo "ABORTA: temperatura ${temp} °C >= 55 °C; esperar a que baje" >&2
    exit 1
fi

if [ ! -x "$GRADLE_BIN" ]; then
    GRADLE_BIN=$(command -v gradle || true)
fi
if [ -z "$GRADLE_BIN" ]; then
    echo "ABORTA: no hay gradle (definí GRADLE_BIN o instalá Gradle 9.9.1)" >&2
    exit 1
fi

start=$(date +%s)
taskset -c 0-1 "$GRADLE_BIN" --daemon \
    -Dorg.gradle.jvmargs=-Xmx768m \
    -PcoreOnly :core:test
elapsed=$(( $(date +%s) - start ))

temp_final=$(max_temp_c)
echo "check-core: OK en ${elapsed} s · ${temp_final} °C"
if [ "$temp_final" -ge "$MIN_TEMP_ABORT" ]; then
    echo "AVISO: temperatura final ${temp_final} °C >= ${MIN_TEMP_ABORT} °C" >&2
fi
