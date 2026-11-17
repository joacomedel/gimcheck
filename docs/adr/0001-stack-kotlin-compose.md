# ADR 0001 — Stack: Kotlin + Jetpack Compose nativo

- **Estado**: vigente (decidido por delegación explícita del dueño, 2026-11-17: "vos analizá y decidí")
- **Contexto**: la web `Rutinas de Gimnasio.html` hay que llevarla a Android sumando sesión guiada,
  cronómetro de descanso con toma de pantalla, vibración/sonido y gráficos.
- **Decisión**: app nativa Kotlin + Compose (Material 3), un solo módulo de app + un módulo `:core`
  de Kotlin puro. Sin WebView, sin frameworks multiplataforma, sin DI framework, sin librerías de
  gráficos ni de red.
- **Alternativas descartadas**: Capacitor/Tauri (paga el build de Android igual y luego hay que
  escribir en Kotlin el 30 % nativo), Flutter (SDK y runtime extra sin beneficio para datos),
  React Native/Expo (Node + Metro + servicios cloud), PWA (no puede garantizar cronómetro ni
  pantalla completa).
- **Consecuencias**: los requerimientos duros (`AlarmManager`, servicio en primer plano,
  `full-screen intent`, vibración, overlay) son código de primera clase. El costo es que el APK
  se compila solo en CI (ver ADR 0002).

> Nota de implementación (GIM-1, 2026-11-17): el esqueleto ya tiene los dos módulos (`:core` con
> `kotlin("jvm")`, `:app` con `com.android.application` + Compose) y ninguna de las dependencias
> descartadas: sin WebView, sin DI, sin librería de gráficos, sin red.
