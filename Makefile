# GimCheck — atajos de build/verificación.
#
# Este equipo (Moto G20, Debian arm64) NO compila Android: no existe aapt2 para
# linux-arm64. Por eso hay tres puertas y este Makefile las nombra sin mentir:
#
#   make check      → gate local L0/L1: compila :core y corre sus tests (aquí)
#   make check-ci   → lo que corre CI, en lo que se puede correr acá (sólo :core)
#   make build      → delega en CI (publica el APK) e imprime el comando gh exacto
#
# Detalle y mediciones: docs/build.md.

GRADLE      ?= /root/tools/gradle-9.9.1/bin/gradle
REPO        ?= joacomedel/gimcheck
WORKFLOW    ?= build.yml
BRANCH      ?= master

.DEFAULT_GOAL := help
.PHONY: help check check-ci check-core build build-gh stop

help:
	@echo "make check      gate local :core (scripts/check-core.sh: guardas + medición)"
	@echo "make check-ci   pasos de CI que corren acá (:core:test, sin guardas del Moto)"
	@echo "make build      no compila Android acá: imprime cómo disparar/leer el run de CI"
	@echo "make stop       apaga el daemon de Gradle (~750 MB)"

## Gate local del Moto: taskset 0-1, guardas de RAM/temperatura, techo de tiempo.
## Corre exactamente lo mismo que antes: `:core:test` con -PcoreOnly.
check:
	@./scripts/check-core.sh

## CI corre `:core:test`, `:app:testDebugUnitTest`, `:app:lintDebug` y
## `:app:assembleDebug` con el Android SDK instalado. Acá sólo se puede el
## primero: los de `:app` necesitan AGP/aapt2/D8 para linux-arm64, que no
## existe. Este target hace ese paso (sin las guardas del Moto, pensado para
## comparar contra CI) y deja escrito qué falta y dónde corre.
check-ci:
	@echo "== :core:test (paso 1 de $(WORKFLOW), idéntico al de CI) =="
	@echo "   acá va con -PcoreOnly: mismo task, sin evaluar :app (AGP necesita SDK)"
	@$(GRADLE) -PcoreOnly :core:test --stacktrace
	@echo
	@echo "== lo que NO corre acá (necesita Android SDK x86_64) =="
	@echo "  ./gradlew :app:testDebugUnitTest → corre en CI (JVM, sin emulador)"
	@echo "  ./gradlew :app:lintDebug         → corre en CI"
	@echo "  ./gradlew :app:assembleDebug     → corre en CI y publica gimcheck-debug.apk"
	@echo "  ver el run: gh run list --repo $(REPO) --workflow $(WORKFLOW) --limit 5"

## "Delegar en CI": no hay forma de producir el APK en este equipo. Este target
## imprime el comando gh exacto; el push a master ya dispara el workflow.
build:
	@echo "El APK se compila sólo en CI (aapt2 no existe para linux-arm64)."
	@echo
	@echo "  1) disparar el workflow (o simplemente: git push origin $(BRANCH)):"
	@echo "     gh workflow run $(WORKFLOW) --repo $(REPO) --ref $(BRANCH)"
	@echo
	@echo "  2) seguir el run hasta el final:"
	@echo "     gh run watch \$$(gh run list --repo $(REPO) --workflow $(WORKFLOW) --limit 1 --json databaseId -q '.[0].databaseId') --repo $(REPO) --exit-status"
	@echo
	@echo "  3) bajar el artifact gimcheck-debug.apk:"
	@echo "     gh run download \$$(gh run list --repo $(REPO) --workflow $(WORKFLOW) --limit 1 --json databaseId -q '.[0].databaseId') --repo $(REPO) -n gimcheck-debug.apk -D /tmp/opencode/apk"
	@echo
	@echo "  (en este equipo: .github/workflows/$(WORKFLOW))"

stop:
	@$(GRADLE) --stop || true
