# ADR 0006 — Dominio puro en `:core`, testeable sin Android SDK

- **Estado**: vigente
- **Contexto**: en este equipo no se puede compilar `:app` (ADR 0002). Si toda la lógica vive en
  el módulo Android, no hay ningún gate local y cada verificación cuesta un push.
- **Decisión**: módulo `:core` (plugin `kotlin("jvm")`, sin Android) con: modelo de dominio,
  cálculo de duración/series/volumen, conflictos de superserie, e1RM/PR, máquina de estados de la
  sesión, validación y migración de JSON. Tests JUnit4 + `kotlin.test`. Gate local:
  `scripts/check-core.sh` → `gradle --daemon -PcoreOnly :core:test` (sin SDK, con
  `-Xmx768m`, `taskset 0-1`; el settings reducido se elige con `-PcoreOnly` porque
  Gradle 9 eliminó `-c`, y el daemon existe porque arrancarlo cuesta ~78 s acá).
- **Consecuencias**: el 70 % del producto se verifica en el Moto en segundos. `:core` no puede
  importar nada de `android.*` ni de Compose; los ViewModels de `:app` solo orquestan. Regla de
  review: si un cálculo aparece en un composable o en un ViewModel, se mueve a `:core`.

> Nota de implementación (GIM-1/GIM-2, 2026-11-17): medido en este equipo con `:core` real
> (1 test, KGP 2.2.10) — régimen sin cambios con daemon vivo: 18–21 s, ~440 MB de RSS pico, ~50 °C;
> frío (daemon apagado + `rm -rf core/build`): 77–105 s. El script corta a 180 s, sin RAM
> (< 800 MB libres) o con temperatura (≥ 55 °C al arrancar, 58 °C sostenidos).
