# ADR 0002 — El APK se compila en CI, no en el Moto

- **Estado**: vigente. Repositorio **público** confirmado por el dueño el 2026-11-17 (minutos de
  GitHub Actions ilimitados).
- **Contexto**: medido en el equipo el 2026-11-17:
  - `aapt2` (compilador de recursos, obligatorio) **no existe para linux-arm64**:
    `aapt2-9.5.1-…-linux.jar` → 200, `…-linux-arm64.jar` → 404.
  - un build de Android con Compose pide 2.5–3 GB de RSS; el equipo tiene 2.1 GB disponibles.
  - la política local prohíbe builds pesados (RAM + temperatura).
- **Decisión**: GitHub Actions `ubuntu-latest` (x86_64) compila, lintea y publica el APK. En el
  Moto solo corre `:core:test` (Kotlin puro, sin SDK) vía `scripts/check-core.sh`.
- **Detalle**: repositorio **público** = minutos ilimitados; privado = 2 000 min/mes (alcanza,
  pero hay que vigilarlo).
- **Consecuencias**: el loop visual es "push → run verde → descargar APK al teléfono". La
  verificación de alertas/cronómetro es manual y del dueño. Ningún ticket de UI se cierra sin
  indicar qué mirar en el teléfono.
