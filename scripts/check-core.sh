#!/usr/bin/env bash
# Gate L0/L1 de GimCheck: compila y testea :core sin Android SDK.
#
# Política de este equipo (AGENTS.md + docs/testing-policy.md):
#   - una corrida pesada a la vez, `taskset -c 0-1`
#   - no arrancar con menos de 800 MB de RAM libre
#   - no arrancar con la zona térmica más caliente en 55 °C o más
#   - abortar (matando el build) si la temperatura se sostiene en 58 °C o más
#   - techo de tiempo configurable
#   - `gradle --stop` al terminar: el daemon se queda con ~750 MB si sigue vivo
#
# El módulo único se selecciona con `-PcoreOnly` (Gradle 9 eliminó `-c`): así
# settings.gradle.kts no incluye `:app`, AGP no se resuelve y no hace falta el SDK.
#
# Uso:
#   ./scripts/check-core.sh                    # uso normal (deja el daemon apagado)
#   KEEP_DAEMON=1 ./scripts/check-core.sh      # mide régimen: no apaga el daemon
#   TIME_LIMIT_SECS=60 ./scripts/check-core.sh # techo de tiempo más ajustado
#
# Presupuesto (medido en docs/build.md): régimen con daemon vivo 18-21 s; peor
# caso legítimo 109 s (daemon nuevo + tests). El techo por defecto es 180 s.
#
# Códigos de salida:
#   0  verde (compiló y pasaron los tests)
#   1  guarda previa: RAM libre insuficiente o equipo ya caliente
#   2  falló el build o un test
#   3  techo de tiempo excedido (build cancelado)
#   4  abortado por temperatura sostenida (build cancelado)
set -euo pipefail

cd "$(dirname "$0")/.."

MIN_FREE_MB=${MIN_FREE_MB:-800}
START_MAX_TEMP_C=${START_MAX_TEMP_C:-55}
ABORT_TEMP_C=${ABORT_TEMP_C:-58}
TEMP_SUSTAIN_SECS=${TEMP_SUSTAIN_SECS:-5}
TIME_LIMIT_SECS=${TIME_LIMIT_SECS:-180}
KEEP_DAEMON=${KEEP_DAEMON:-0}
RERUN_TESTS=${RERUN_TESTS:-1}
GRADLE_BIN=${GRADLE_BIN:-/root/tools/gradle-9.9.1/bin/gradle}
LOG_DIR=${LOG_DIR:-/tmp/opencode}

start_tenths=$(( START_MAX_TEMP_C * 10 ))
abort_tenths=$(( ABORT_TEMP_C * 10 ))

# --- medición ---------------------------------------------------------------
# No hay /usr/bin/time en este equipo: la temperatura sale de
# /sys/class/thermal/thermal_zone*/temp y la RAM de /proc/<pid>/statm, sumando
# el árbol del cliente de Gradle más cualquier proceso con marca de build
# (el daemon puede estar ya arrancado y colgado de otro padre).
max_temp_tenths() {
    python3 - <<'PY'
import glob
best = 0.0
for path in glob.glob('/sys/class/thermal/thermal_zone*/temp'):
    try:
        raw = int(open(path).read().strip())
    except (OSError, ValueError):
        continue
    value = raw / 1000.0 if raw > 1000 else float(raw)
    best = max(best, value)
print(int(round(best * 10)))
PY
}

# Imprime "<temp en décimas de °C> <RSS total en KB>".
sample_build() {
    python3 - "$1" <<'PY'
import glob, os, sys

root = int(sys.argv[1])
markers = (b'GradleDaemon', b'GradleWorkerMain', b'gradle-launcher', b'kotlin.daemon')

best = 0.0
for path in glob.glob('/sys/class/thermal/thermal_zone*/temp'):
    try:
        raw = int(open(path).read().strip())
    except (OSError, ValueError):
        continue
    best = max(best, raw / 1000.0 if raw > 1000 else float(raw))

page = os.sysconf('SC_PAGE_SIZE')

def rss_kb(pid):
    try:
        with open('/proc/%d/statm' % pid) as f:
            return int(f.read().split()[1]) * page // 1024
    except (OSError, ValueError, IndexError):
        return 0

def ppid_of(pid):
    try:
        with open('/proc/%d/stat' % pid) as f:
            data = f.read()
        return int(data[data.rindex(')') + 2:].split()[1])
    except (OSError, ValueError, IndexError):
        return None

children = {}
targets = {root}
me = os.getpid()
for entry in os.listdir('/proc'):
    if not entry.isdigit():
        continue
    pid = int(entry)
    if pid == me:
        continue
    parent = ppid_of(pid)
    if parent is not None:
        children.setdefault(parent, []).append(pid)
    try:
        with open('/proc/%d/comm' % pid) as f:
            if f.read().strip() != 'java':
                continue
        with open('/proc/%d/cmdline' % pid, 'rb') as f:
            cmd = f.read().replace(b'\0', b' ')
    except OSError:
        continue
    if any(marker in cmd for marker in markers):
        targets.add(pid)

# El árbol del cliente cubre daemon/worker recién arrancados.
stack = list(targets)
seen = set()
total = 0
while stack:
    pid = stack.pop()
    if pid in seen:
        continue
    seen.add(pid)
    total += rss_kb(pid)
    stack.extend(children.get(pid, []))

print('%d %d' % (int(round(best * 10)), total))
PY
}

kill_build() {
    local sig=$1
    python3 - "$sig" <<'PY'
import os, signal, sys

sig = getattr(signal, 'SIG' + sys.argv[1].upper())
markers = (b'GradleDaemon', b'GradleWorkerMain', b'gradle-launcher', b'kotlin.daemon')
# Sólo JVMs: un shell/grep cuyo cmdline mencione el patrón no se toca.
# (Es la trampa de `pkill -f`: mataría al propio shell que ejecuta el script.)
me, my_parent = os.getpid(), os.getppid()
for entry in os.listdir('/proc'):
    if not entry.isdigit():
        continue
    pid = int(entry)
    if pid in (me, my_parent):
        continue
    try:
        with open('/proc/%d/comm' % pid) as f:
            if f.read().strip() != 'java':
                continue
        with open('/proc/%d/cmdline' % pid, 'rb') as f:
            cmd = f.read().replace(b'\0', b' ')
    except OSError:
        continue
    if any(marker in cmd for marker in markers):
        try:
            os.kill(pid, sig)
        except OSError:
            pass
PY
}

stop_gradle() {
    if [ -x "$GRADLE_BIN" ]; then
        timeout 30 "$GRADLE_BIN" --stop >/dev/null 2>&1 || true
    fi
}

# --- guardas previas -------------------------------------------------------
ram_mb=$(awk '/^MemAvailable:/ { print int($2 / 1024) }' /proc/meminfo)
temp_start=$(max_temp_tenths)
echo "check-core: ${ram_mb} MB libres · ${temp_start%?},${temp_start: -1} °C"

if [ "$ram_mb" -lt "$MIN_FREE_MB" ]; then
    echo "check-core: ABORTA — RAM libre ${ram_mb} MB < ${MIN_FREE_MB} MB (no arranco un build)" >&2
    exit 1
fi
if [ "$temp_start" -ge "$start_tenths" ]; then
    echo "check-core: ABORTA — ${temp_start%?},${temp_start: -1} °C >= ${START_MAX_TEMP_C} °C; esperá a que baje" >&2
    exit 1
fi

if [ ! -x "$GRADLE_BIN" ]; then
    GRADLE_BIN=$(command -v gradle || true)
fi
if [ -z "${GRADLE_BIN:-}" ]; then
    echo "check-core: ABORTA — no hay gradle (definí GRADLE_BIN o instalá Gradle 9.9.1)" >&2
    exit 1
fi

mkdir -p "$LOG_DIR"
log_file="$LOG_DIR/check-core-$(date +%Y%m%d-%H%M%S)-$$.log"

# `--rerun` fuerza la tarea :core:test: sin eso, una segunda corrida sale
# UP-TO-DATE en 3 s y no verifica nada. Sólo se re-ejecuta el test, no el compile.
gradle_args=(--daemon -Dorg.gradle.jvmargs=-Xmx768m -PcoreOnly :core:test)
if [ "$RERUN_TESTS" = "1" ]; then
    gradle_args+=(--rerun)
fi

cleanup() {
    if [ "$KEEP_DAEMON" != "1" ]; then
        stop_gradle
    fi
}
trap cleanup EXIT

# --- build vigilado --------------------------------------------------------
start_s=$(date +%s)
taskset -c 0-1 "$GRADLE_BIN" "${gradle_args[@]}" >"$log_file" 2>&1 &
gpid=$!

peak_kb=0
temp_peak=$temp_start
hot=0
abort_reason=""

while kill -0 "$gpid" 2>/dev/null; do
    sample=$(sample_build "$gpid" 2>/dev/null) || sample="0 0"
    cur_t=${sample%% *}
    cur_kb=${sample##* }

    [ "$cur_t" -gt "$temp_peak" ] && temp_peak=$cur_t
    [ "$cur_kb" -gt "$peak_kb" ] && peak_kb=$cur_kb

    if [ "$cur_t" -ge "$abort_tenths" ]; then
        hot=$((hot + 1))
    else
        hot=0
    fi

    if [ "$hot" -ge "$TEMP_SUSTAIN_SECS" ]; then
        abort_reason="temp"
        break
    fi
    if [ $(( $(date +%s) - start_s )) -gt "$TIME_LIMIT_SECS" ]; then
        abort_reason="time"
        break
    fi
    sleep 1
done

peak_mb=$(( peak_kb / 1024 ))
temp_peak_c="${temp_peak%?},${temp_peak: -1}"

if [ -n "$abort_reason" ]; then
    kill -TERM "$gpid" 2>/dev/null || true
    kill_build TERM
    sleep 2
    kill_build KILL
    wait "$gpid" 2>/dev/null || true
    elapsed=$(( $(date +%s) - start_s ))

    echo "check-core: log en $log_file" >&2
    tail -n 15 "$log_file" >&2 || true
    if [ "$abort_reason" = "temp" ]; then
        echo "check-core: ABORTADO a los ${elapsed} s — temperatura sostenida ${temp_peak_c} °C >= ${ABORT_TEMP_C} °C (pico ${temp_peak_c} °C, ${peak_mb} MB)" >&2
        echo "check-core: build cancelado; no se considera verde. Enfriá el equipo y reintentá." >&2
        exit 4
    fi
    echo "check-core: ABORTADO a los ${elapsed} s — techo de tiempo ${TIME_LIMIT_SECS} s excedido (pico ${temp_peak_c} °C, ${peak_mb} MB)" >&2
    echo "check-core: build cancelado; no se considera verde. Si fue compilación en frío, subí TIME_LIMIT_SECS con criterio." >&2
    exit 3
fi

rc=0
wait "$gpid" || rc=$?
elapsed=$(( $(date +%s) - start_s ))
temp_end=$(max_temp_tenths)

# `--stacktrace` sólo cuando falla: no ensucia la salida de una corrida verde.
if [ "$rc" -ne 0 ]; then
    echo "check-core: FALLÓ (código $rc) en ${elapsed} s · pico ${peak_mb} MB · ${temp_peak_c} °C" >&2
    echo "check-core: log completo en $log_file" >&2
    tail -n 25 "$log_file" >&2 || true
    if [ "$temp_end" -lt "$start_tenths" ]; then
        echo "check-core: re-ejecuto con --stacktrace" >&2
        taskset -c 0-1 "$GRADLE_BIN" "${gradle_args[@]}" --stacktrace 2>&1 | tail -n 60 >&2 || true
    fi
    exit 2
fi

echo "check-core: OK"
echo "  tiempo total : ${elapsed} s"
echo "  RSS pico     : ${peak_mb} MB (suma de los procesos del build)"
echo "  temperatura  : inicio ${temp_start%?},${temp_start: -1} °C · pico ${temp_peak_c} °C · final ${temp_end%?},${temp_end: -1} °C"
echo "  log          : $log_file"
if [ "$KEEP_DAEMON" = "1" ]; then
    echo "  daemon       : sigue vivo (KEEP_DAEMON=1); apagalo con 'gradle --stop'"
fi
exit 0
