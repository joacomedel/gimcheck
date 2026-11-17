# ADR 0006 — Dominio puro en `:core`, testeable sin Android SDK

- **Estado**: vigente
- **Contexto**: en este equipo no se puede compilar `:app` (ADR 0002). Si toda la lógica vive en
  el módulo Android, no hay ningún gate local y cada verificación cuesta un push.
- **Decisión**: módulo `:core` (plugin `kotlin("jvm")`, sin Android) con: modelo de dominio,
  cálculo de duración/series/volumen, conflictos de superserie, e1RM/PR, máquina de estados de la
  sesión, validación y migración de JSON. Tests JUnit4 + `kotlin.test`. Gate local:
  `scripts/check-core.sh` → `gradle -c settings-core.gradle.kts :core:test` (sin SDK, con
  `--no-daemon`, `-Xmx768m`, `taskset 0-1`).
- **Consecuencias**: el 70 % del producto se verifica en el Moto en segundos. `:core` no puede
  importar nada de `android.*` ni de Compose; los ViewModels de `:app` solo orquestan. Regla de
  review: si un cálculo aparece en un composable o en un ViewModel, se mueve a `:core`.
