# GimCheck

App Android de rutinas de gimnasio: port nativo de `Rutinas de Gimnasio.html` (SPA vanilla)
a Kotlin + Jetpack Compose, con sesión guiada, cronómetro de descanso con alarma y gráficos.

- `core/` — dominio Kotlin puro (`:core`): modelo, cálculo de series/volumen, e1RM,
  máquina de estados de la sesión, JSON. Se compila y testea sin Android SDK.
- `app/` — aplicación Android (Compose, Material 3).
- `docs/` — especificación funcional, ADRs y notas de build.
- El APK se compila **solo en CI** (`aapt2` no existe para linux-arm64): cada push a
  `master` publica el artifact `gimcheck-debug.apk`.

## Gate local (sin Android SDK)

```bash
./scripts/check-core.sh     # = gradle -PcoreOnly :core:test, con guardas (o: make check)
```

## Build completo

```bash
make build
# equivalente a: gh workflow run build.yml --repo joacomedel/gimcheck --ref master
./gradlew :core:test :app:lintDebug :app:assembleDebug   # sólo en CI / PC con Android SDK
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Versiones exactas, mediciones y cómo se genera el APK: [`docs/build.md`](docs/build.md).
Especificación funcional: [`docs/especificacion.md`](docs/especificacion.md).
