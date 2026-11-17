# Análisis técnico — de la web al APK (2026-11-17)

Medido en este equipo (Moto G20, Debian 13 arm64, chroot sobre Android, 3.4 GiB RAM, 98 GB
libres). Nada de lo que sigue es teórico: cada límite está verificado con un comando.

## 1. Qué es lo que hay

`Rutinas de Gimnasio.html` (54 KB, 787 líneas) es una SPA vanilla sin dependencias externas
salvo Google Fonts. Persiste en `localStorage` con clave `rutinas-gym-v1`, esquema **v21** con
migraciones `ensureV2()`. El motor de cálculo (duración, series efectivas, volumen por músculo,
conflictos de superserie, comparación de planes) está en 250 líneas de JS puro, **sin DOM**:
es portable 1:1 a Kotlin y verificable con tests. Lo único acoplado al navegador es el render
(string de HTML + un listener de `click`/`change`) y `localStorage`.

Conclusión: la web es, en la práctica, **un dominio funcional + una capa de vista descartable**.

## 2. Stack: qué opciones había

| Opción | APK | Efecto en este equipo | Veredicto |
|---|---|---|---|
| **Kotlin + Compose (nativo)** | ~10–14 MB | Build imposible acá (§3), CI en GitHub | **Elegida** |
| Capacitor / Tauri mobile (WebView + la web actual) | ~6 MB + WebView | Mismo problema de build (necesita SDK + Gradle); el cronómetro de descanso con pantalla completa queda a merced de plugins, y `AlarmManager`/FSI/overlay igual hay que escribirlos en Java/Kotlin | Descartada: paga el costo del build igual y suma una capa frágil |
| Flutter | ~20 MB | SDK Dart (~1.5 GB) + SDK Android; build en CI; runtime propio para gráficos y vida en segundo plano | Descartada: sin beneficio para una app de datos |
| React Native / Expo | ~25 MB | Node no está instalado; Metro + Hermes + SDK Android; Expo EAS es cloud de pago | Descartada |
| PWA (la web + service worker) | no hay APK | Cero fricción, pero **no puede** tomar la pantalla ni garantizar el cronómetro con la app en background (iOS/Android matan los timers de página) | Descartada: incumple el requerimiento central |

Lo que decide: los requerimientos duros (alarma exacta, servicio en primer plano, *full-screen
intent*, vibración/sonido propio, pantalla que aparece sola) **sólo existen en la capa nativa**.
Cualquier stack de WebView termina escribiendo ese 30 % en Kotlin de todos modos.

## 3. Costo de cómputo en este servidor

### 3.1 Lo que falta

```
$ which java javac gradle kotlin adb sdkmanager   → (nada)
$ ls /usr/lib/android-sdk ~/Android               → (no existe)
```

### 3.2 El bloqueante real: `aapt2` no existe para linux-arm64

`aapt2` es el compilador de recursos de Android; sin él no hay APK, en ningún stack.
Verificado contra Google Maven:

```
aapt2-9.5.1-16666295-linux.jar       → HTTP 200
aapt2-9.5.1-16666295-linux-arm64.jar → HTTP 404
```

Google publica `linux` (x86_64), `osx`, `osx-arm64`, `windows`. **No hay binario arm64 de
Linux.** Se podría correr bajo `qemu-user` (`binfmt`), que en este SoC implicaría traducir la
compilación de recursos de cada build: minutos por corrida, 100 % de un núcleo, y es un
requerimiento de kernel que este chroot no tiene garantizado. No vale la pena.

### 3.3 Costo estimado de un toolchain Android local (si `aapt2` existiera)

| Componente | Tamaño |
|---|---|
| `openjdk-21-jdk-headless` (medido con `apt --print-uris`) | 123 MB de descarga / 301 MB en disco |
| Gradle 9.9.1 + wrapper + daemon | ~150 MB |
| Android SDK: platform-tools + `platforms;android-36` + `build-tools;36` | ~250–400 MB |
| Caché de Gradle con AGP + Compose + Room + KSP (~900 artefactos) | ~1.2–1.8 GB |

Y el costo de ejecución: `AGP + Kotlin + R8` pide **2.5–3 GB de RSS** para un build de debug
con Compose. Este equipo tiene **2.1 GB disponibles** (con zram como red de seguridad) y la
política local prohíbe builds pesados justamente por eso: es el escenario de OOM killer que ya
nos mató procesos del sistema. Sumado a `soc-thmzone` pegando 58–63 °C con procesos de 2
núcleos, un build local de Android queda **fuera de presupuesto por dos motivos independientes**
(RAM y temperatura), además del bloqueante de `aapt2`.

### 3.4 Lo que sí es barato acá (medido)

El **dominio** de la app (todo el cálculo de la web + máquina de estados de la sesión) es Kotlin
puro, sin Android. Se compila y testea con JDK + Gradle en un módulo `:core` sin SDK. Medido con
una sonda `kotlin("jvm")` 2.4.21 + Gradle 9.9.1 (`taskset -c 0-1`):

| Corpus | Medido (2026-11-17) |
|---|---|
| primer build (descarga de plugin Kotlin + JUnit) | 2 m 31 s |
| recompilando tests, `--no-daemon` | 1 m 03 s · 398 MB pico |
| con daemon, primer run | 25,4 s · 325 MB |
| con daemon, sin cambios | 3,3 s |

Temperatura durante toda la sonda: 33–36 °C (no se movió). Eso da un loop de trabajo de
**3–25 s por verificación**, con el 70 % del producto cubierto sin tocar CI.

## 4. Decisión: CI en GitHub Actions

- **Runner `ubuntu-latest` (x86_64, 4 vCPU, 16 GB)**: tiene `aapt2`, JDK 21 y RAM de sobra.
  Un build de debug con caché caliente: ~2–3 min. En frío (primera vez, ~1 GB de descargas):
  6–8 min.
- **Costo para el dueño**: repositorio **público** → minutos ilimitados y gratis. Si va privado,
  el plan gratuito da 2 000 min/mes: alcanza para ~150–300 builds, suficiente, pero es un techo
  que hay que mirar. **Decisión pendiente del dueño** (ADR 0002).
- **Cómo llega el APK al teléfono**: `actions/upload-artifact` en cada push a `master`
  (retención 30 días) + `gh release` en cada tag `v*`. Instalación por link directo desde el
  navegador del Moto (permiso "instalar apps desconocidas").
- **Firma**: debug keystore de CI es suficiente para sideload. La firma de release (keystore en
  secrets) queda como ticket opcional; no bloquea v1.

### 4.1 Pipeline

```
push / PR  →  ./gradlew :core:test  →  lint  →  assembleDebug  →  artifact gimcheck-debug.apk
tag v*     →  idem + assembleRelease (firmado si hay secrets)  →  GitHub Release con el APK
```

`gradle/actions/setup-gradle` cachea `~/.gradle` y el SDK (`android-actions/setup-android`).

### 4.2 Regla de trabajo que se deriva

- **Local (Moto)**: `scripts/check-core.sh` → `:core:test` con `settings-core.gradle.kts`
  (incluye solo `:core`, no toca `:app`, no necesita SDK). Es el gate L0/L1 de la política.
- **CI**: `:app` compile + lint + APK. Evidencia = link del run verde + APK descargable.
- **Teléfono del dueño**: único lugar donde se prueba de verdad la alerta de pantalla completa,
  la vibración, el sonido y el cronómetro con la app cerrada. Eso es `evidence_kind: manual`.

## 5. Dependencias (pinneadas, verificadas hoy contra los repos)

| Artefacto | Versión | Por qué |
|---|---|---|
| Gradle | 9.9.1 | última estable (services.gradle.org, 2026-11-10) |
| Android Gradle Plugin | 9.5.1 | última estable; fallback documentado 8.13.2 + Gradle 8.14.x |
| Kotlin | 2.4.21 | estable; el compilador de Compose viene incluido desde Kotlin 2.0 |
| KSP | 2.3.14 | procesador de Room (KSP2, versionado propio) |
| Compose BOM | 2026.09.00 | alinea todo Compose Material 3 |
| Room | 2.8.5 | SQLite tipado + migraciones |
| activity-compose | 1.13.0 | `setContent`, edge-to-edge |
| navigation-compose | 2.10.2 | 9 destinos, tipos simples |
| lifecycle-* | 2.11.0 | ViewModel + `collectAsStateWithLifecycle` |
| androidx.core-ktx | 1.19.1 | compat |
| kotlinx-serialization-json | 1.13.0 | backup/import JSON |
| kotlinx-datetime | 0.8.0 | fechas ISO, semanas ISO |
| Robolectric | 4.18 | tests de UI Compose en JVM (sin emulador) |
| JUnit4 + kotlin.test | 4.13.2 | tests de `:core` |

**Cero librerías de gráficos.** Los tres gráficos (e1RM, volumen, peso corporal) se dibujan con
`Canvas` de Compose: ~300 líneas, control total del estilo, cero dependencias que envejezcan.
**Cero librerías de terceros** más allá de AndroidX/JetBrains. Sin DI framework (constructor
manual + un `AppContainer`), sin Hilt/Koin, sin Retrofit (no hay red).

Tamaño esperado del APK: **10–14 MB** (Compose ya viene reducido por R8 en release).

## 6. Riesgos técnicos y mitigaciones

| Riesgo | Impacto | Mitigación |
|---|---|---|
| `USE_FULL_SCREEN_INTENT` restringido desde Android 14 (sólo llamadas/alarma lo reciben automáticamente) | La pantalla no aparece sola | Pedir el permiso con `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` en un paso guiado; fallback a overlay `SYSTEM_ALERT_WINDOW`; fallback final a heads-up de alta prioridad. Documentar el estado real en Ajustes ("pantalla completa: concedida / no") |
| Android 15/16 degradan FSI a heads-up con el teléfono desbloqueado | Menos "secuestro", más notificación | Aceptado y deseado: la app avisa igual con sonido/vibración. La pantalla completa se usa con el teléfono bloqueado |
| OEM mata servicios en primer plano (MIUI/EMUI/Samsung) | Cronómetro que no suena | `AlarmManager.setExactAndAllowWhileIdle` como respaldo independiente del proceso + persistencia de `rest_ends_at` + guía de "quitar optimización de batería" en Ajustes |
| `aapt2` arm64 inexistente | Sin build local | CI obligatorio; `:core` local para lógica |
| AGP 9.x cambió DSL/soporte Kotlin integrado | Fricción en el bootstrap | Ticket de bootstrap permite caer a AGP 8.13.2 y documentar la matriz que compila |
| Migración de datos web (`v21`) | Pérdida de rutinas | Importador con tests sobre el JSON real del dueño + backup antes de importar |
| Compose en Robolectric puede ser lento/inestable en CI | Tests de UI frágiles | Los tests de UI son opcionales y no bloquean el merge; la lógica se testea en `:core` |

## 7. Qué NO se hace (para que no se cobre después)

- No hay emulador Android acá ni en CI (sin KVM en runner gratis → emulador x86 sin aceleración
  es inusable). La verificación visual final es del dueño en su teléfono.
- No hay tests instrumentados (`connectedAndroidTest`).
- No hay publicación en Play Store (requiere cuenta, revisión y firma; fuera de v1).
- No se instala el SDK de Android en el Moto.
