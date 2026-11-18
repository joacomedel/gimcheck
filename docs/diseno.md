# Contrato visual — GimCheck

Aplica a todo lo que se dibuja en `app/` (Kotlin + Jetpack Compose + Material 3). Es el contrato
que usan los tickets de UI (GIM-12 a GIM-15): si una pantalla no sigue esto, es un bug de review.
Si algo de acá choca con un pedido del dueño, gana el dueño y **se actualiza este archivo en el
mismo commit**.

Referencia funcional: [`especificacion.md`](especificacion.md). Build y verificación:
[`build.md`](build.md).

## 1. La regla

**Una sola idea visual: números grandes y quietos, superficies planas, un único color de acento,
cero decoración.** Si un elemento no informa ni se toca, no existe. Antes de agregar un borde, una
sombra o un ícono, preguntarse qué problema resuelve. La respuesta por defecto es "no".

## 2. Por qué una pantalla se ve "generada por IA" (y cómo se evita)

| Síntoma | Por qué pasa | Regla acá |
|---|---|---|
| Cards dentro de cards con sombras suaves | Material 3 por defecto + `ElevatedCard` en todos lados | Máximo **un** nivel de superficie. `Surface` plana con borde de 1 dp; sombra sólo en hojas modales |
| Degradés, glassmorphism, blur | moda sin función | Prohibidos. El fondo es un color plano |
| Emojis como íconos (💪🔥✅) | relleno | Solo `Icons.Rounded.*` de `material-icons-core`; si falta el ícono, texto |
| Todo redondeado 28 dp + animaciones con rebote | default del tema | Radio **10 dp** (12 en hojas), motion ≤ 150 ms, sin rebotes |
| 5 acentos y 3 tipografías | entusiasmo | 1 acento, 1 semáforo (ok/warn/bad), 2 familias tipográficas |
| Title Case y mayúsculas gritadas | relleno | *Sentence case* en todo; mayúsculas sólo en la tipografía display (Barlow) con `letterSpacing` chico |
| Placeholders "Lorem ipsum" / copy genérico | falta de dominio | Cada pantalla define sus textos reales en `app/src/main/res/values/strings.xml` (español rioplatense, voseo: "Anotá", "Elegí") |
| Pantalla muerta sin datos | no se pensó el estado vacío | Estado vacío = 1 frase + 1 acción. Siempre |

## 3. Tokens

Un solo archivo de tokens: `ui/theme/Tokens.kt` (a crear). Nada de colores literales en pantallas:
`Color(0xFF…)` fuera de ese archivo es bug de review.

### 3.1 Color (claro / oscuro)

| Token | Claro | Oscuro | Uso |
|---|---|---|---|
| `bg` | `#F4F4F1` | `#121211` | fondo de la app |
| `surface` | `#FCFCFB` | `#1A1A19` | tarjetas, filas, hojas |
| `surface2` | `#ECECE7` | `#262625` | campos, pistas de barra, banda de referencia |
| `line` | `#D9D8D1` | `#34332F` | bordes de 1 dp, separadores |
| `ink` | `#0B0B0B` | `#F0EFEC` | texto principal, números |
| `ink2` | `#52514E` | `#C3C2B7` | texto secundario |
| `muted` | `#706F6A` | `#8F8E86` | metadatos, ejes de gráfico, placeholders |
| `accent` | `#2770C7` | `#3987E5` | **único** acento: acción primaria, selección, serie principal de un gráfico |
| `onAccent` | `#FFFFFF` | `#121211` | texto **sobre** un relleno `accent` (label del botón primario) |
| `good` | `#0A7A3D` | `#4CC27F` | "en rango", PR nuevo, descanso listo |
| `warn` | `#A35F00` | `#E0A03A` | fuera de rango, advertencia de superserie |
| `bad` | `#C0302E` | `#EC7A78` | conflicto, eliminar, sesión abandonada |
| `series1` | = `accent` | = `accent` | gráfico: serie principal |
| `series2` | `#EB6834` | `#D95926` | gráfico: segunda serie (comparar, plan B) |
| `series3` | `#1BAF7A` | `#199E70` | gráfico: tercera categoría |

Reglas de uso:

- El acento es **uno**: no hay acento secundario, no hay "azul de links" distinto, no hay
  "morado de Progreso". Si dos cosas compiten por el acento, una está mal jerarquizada.
- El semáforo (`good`/`warn`/`bad`) sólo aparece donde hay un estado medido: banda semanal,
  conflicto de superserie, error. Nunca como decoración de sección.
- Modo oscuro primero (el gimnasio es de noche y con luz baja); el claro es la variante.
  `theme` en Ajustes: `SYSTEM` (default) / `LIGHT` / `DARK`.

**Contraste medido** (WCAG 2.1, texto sobre esos fondos; la tabla la verifica
`TokensContractTest`):

| Par | Claro | Oscuro | Mínimo |
|---|---|---|---|
| `ink` / `surface` | 19,2:1 | 15,2:1 | 4,5 (AA) |
| `ink` / `bg` | 17,9:1 | 16,3:1 | 4,5 |
| `ink` / `surface2` | 16,6:1 | 13,2:1 | 4,5 |
| `ink2` / `surface` | 7,7:1 | 9,7:1 | 4,5 |
| `muted` / `surface` | 4,9:1 | 5,3:1 | 4,5 |
| `muted` / `bg` | 4,6:1 | 5,7:1 | 4,5 |
| `accent` / `surface` | 4,8:1 | 4,8:1 | 4,5 |
| `accent` / `bg` | 4,5:1 | 5,2:1 | 4,5 |
| `onAccent` / `accent` | 5,0:1 | 5,2:1 | 4,5 |
| `good` / `surface` | 5,3:1 | 7,7:1 | 4,5 |
| `warn` / `surface` | 4,9:1 | 7,7:1 | 4,5 |
| `bad` / `surface` | 5,5:1 | 6,3:1 | 4,5 |

Dos valores del contrato original no llegaban al mínimo como **texto** y se corrigieron
acá (GIM-12), no en el requisito: `muted` claro `#7A7974` daba 4,25:1 sobre `surface`
(es el color de los metadatos de 12–13 sp) y `accent` claro `#2A78D6` daba 4,30:1 como
texto de acción y 4,42:1 con label blanco encima. Los reemplazos son del mismo tono, un
8 % más oscuros, y ningún par baja de 4,5:1. `line` queda afuera de la tabla: es un borde
de 1 dp, no texto.

### 3.2 Espaciado, radios y alturas

Grilla de 4:

| Token | Valor | Uso |
|---|---|---|
| `xs` | 4 dp | separación entre label y valor |
| `s` | 8 dp | `verticalArrangement` de listas, gap entre filas |
| `m` | 12 dp | padding interno de tarjetas chicas, gap entre bloques |
| `l` | 16 dp | **padding de pantalla** y `contentPadding` de `LazyColumn` |
| `xl` | 24 dp | separación entre secciones |
| `xxl` | 32 dp | aire antes de la acción primaria |

- Radios: **10 dp** en controles y tarjetas, **999 dp** en chips, **12 dp** en hojas.
- Alturas mínimas táctiles: **48 dp** filas y botones de ícono, **44 dp** chips, **40 dp**
  steppers chicos, **52 dp** botón primario ancho completo, **56 dp** `ExerciseRow`.
- Nada de `elevation` salvo `ModalBottomSheet` (2 dp). El borde de 1 dp `line` es lo que separa.

## 4. Tipografía

Dos familias, empaquetadas en `res/font` (**no** Google Fonts en runtime; la app no tiene red).
Archivos, pesos y licencia OFL: [`fuentes.md`](fuentes.md).

| Familia | Pesos | Dónde |
|---|---|---|
| **Barlow Condensed** | 600, 700 | títulos de pantalla, números grandes (tiles, cuenta regresiva, PR, peso) |
| **IBM Plex Sans** | 400, 500, 600 | todo lo demás: cuerpo, etiquetas, botones, tablas |

Escala:

| Rol | Tamaño | Fuente | Uso |
|---|---|---|---|
| `display` | 40 sp | Barlow 700 | cuenta regresiva del descanso, número de PR |
| `stat` | 30 sp | Barlow 600 | valor de un `StatTile` |
| `titulo` | 26 sp | Barlow 600 | título de pantalla dentro del contenido |
| `section` | 22 sp | Barlow 600, mayúsculas, `letterSpacing 0.02em` | `SectionHeader` |
| `subtitulo` | 18 sp | IBM Plex 600 | nombre de ejercicio en ficha, título de hoja |
| `cuerpo` | 15 sp | IBM Plex 400 | texto normal |
| `detalle` | 13 sp | IBM Plex 400 | secundario, subtítulo de fila |
| `etiqueta` | 12 sp | IBM Plex 500 | labels de campo, metadatos, ticks |

Números: `fontFeatureSettings = "tnum"` (cifras tabulares) y alineados a la derecha en tablas.
`letterSpacing 0` en Barlow. Separador decimal **coma** (`82,5 kg`, `1.240 kg`) y `1:30` para
tiempo; sin ceros de relleno (`82,5`, no `82,50`).

Los estilos viven en `ui/theme/GimTypography.kt` (`GimText.display`, `GimText.stat`, …). El
label de un botón —rol que esta tabla no fija— es **15 sp IBM Plex 600**: 12 sp en un botón
de 52 dp se ve chico. Los 15 roles de `MaterialTheme.typography` se llenan a propósito con
esos estilos: un rol sin asignar cae a Roboto y una app de dos familias con un `AlertDialog`
en Roboto se nota. El mapeo se valida contra `GimText`, no leyendo `Typography` de vuelta:
en material3 1.4.0 esos roles devuelven `null` en la JVM (§11).

## 5. Componentes

Una sola implementación de cada uno, en `ui/components/` (a crear). Si una pantalla necesita algo
distinto, se agrega acá y se reusa; no se copia el layout.

| Componente | Firma mínima | Notas |
|---|---|---|
| `StatTile` | `k: String, v: String, s: String?` | valor en `stat` (30 sp); sin borde interno, sin sombra |
| `Meter` | `value, max, bandMin?, bandMax?, color` | barra de 20 dp sobre `surface2`, banda de referencia detrás, esquinas redondeadas sólo a la derecha; reusa para "series por músculo" |
| `SetStepper` | `value, step, onValue, label` | `− valor +`, 40 dp, `tnum`, háptico `TextHandleMove`; en Entrenar va a 56 dp de alto y `display` para el número |
| `ExerciseRow` | `name, detail, trailing?` | fila de 56 dp, máximo 2 líneas de texto |
| `ChipFilter` | `label, selected, onClick` | borde 1 dp `line`; seleccionado = fondo `ink` y texto `bg` |
| `SectionHeader` | `title, action?` | `section` (22 sp Barlow, mayúsculas), acción a la derecha en `accent` |
| `EmptyState` | `text, actionLabel, onAction` | centrado, sin ilustración, 1 frase + 1 botón |
| `ConfirmInline` | `question, onConfirm, onCancel` | **nunca** `AlertDialog` para borrar: reemplaza el botón en el lugar |
| `TimerRing` | `remaining, total` | Canvas: arco `accent` de 8 dp sobre `surface2`, sin animación de entrada, tiempo en `display` |
| `BottomSheet` | `title, content` | `ModalBottomSheet` plana, radio 12 arriba, `imePadding()` |
| `NumPad` | `onDigit, onConfirm` | teclado propio **sólo** si el del sistema tapa el contexto; si no, `KeyboardOptions(KeyboardType.Number)` |
| `SetRow` | `set, onEdit, onToggle` | fila de serie anotada: `#`, peso, reps, check; 48 dp; editable al toque |
| `DayChips` | `days, selected, onSelect` | fila de `ChipFilter` scrollable horizontal, alto 44 dp |

## 6. Gráficos (Canvas propio, sin librerías)

Motor de escalas compartido en `ui/chart/ChartScales.kt` (a crear): dominio→píxeles, ticks
"lindos", path builder. Lo decide el ADR [`adr/0005-graficos-canvas.md`](adr/0005-graficos-canvas.md).

- **Línea (e1RM, peso corporal)**: área rellena al 10 % de opacidad bajo la curva, línea de 2 dp
  `series1`, puntos de 4 dp **sólo** en el último valor y en los PR, eje Y con 3 marcas en `muted`
  11 sp, sin rejilla horizontal salvo la línea base. Rango 3 M / 6 M / 1 A / Todo como chips.
- **Barras (volumen por sesión/semana)**: 12 dp de ancho, 4 dp de separación, `series1`, sin
  sombra, etiqueta de valor sólo en la barra máxima.
- **Barras horizontales por músculo**: reusar `Meter`, no un gráfico nuevo.
- Interacción: `pointerInput` + `detectTapGestures` para mostrar el valor tocado en un tooltip de
  6 dp de radio. Sin zoom, sin pinch, sin animaciones de carga.
- Ejes: nunca más de 1 decimal; separador coma; el sufijo `kg` va una sola vez en el título, no en
  cada tick.

## 7. Composición

1. Un `Scaffold` con barra superior **sólo** si hay una acción de pantalla; si no, el título va
   dentro del contenido (así no se gasta 56 dp en repetir lo que ya dice la barra inferior).
2. Máximo 2 bloques visuales antes del primer dato accionable.
3. La acción primaria va donde está el pulgar: abajo, ancho completo, 52 dp.
4. `LazyColumn` con `contentPadding` 16 y `verticalArrangement` 8.
5. Estados: `Loading` (nada; si tarda menos de 200 ms no se muestra), `Empty`, `Content`, `Error`
   (texto + "Reintentar"). Se modelan como `sealed interface` de `UiState` en el ViewModel, no
   como booleanos (`isLoading` + `isEmpty` + `hasError` es bug de review).
6. Una pantalla = un `UiState` inmutable; los eventos suben (`onEvent`), los composables no tienen
   lógica de negocio. Todo cálculo vive en `:core` (ADR [`adr/0006-dominio-puro-core.md`](adr/0006-dominio-puro-core.md)).
7. Navegación: barra inferior con 5 destinos (Hoy, Rutinas, Progreso, Historial, Ajustes).
   Entrenar es modal y pide confirmación al salir (la sesión está viva).

## 8. Las 9 pantallas

Ninguna existe todavía en el código (todas "sin empezar": el `MainActivity` actual pinta un texto).
Esta tabla es el contrato que ejecutan GIM-12..GIM-15.

| # | Pantalla | Jerarquía visual (arriba → abajo) | Componentes | Acción primaria |
|---|---|---|---|---|
| 1 | **Hoy** | 1) título con fecha (Barlow 26) · 2) fila rutina + día (`DayChips` + `ChipFilter` de rutina) · 3) `StatTile` de duración y series del día · 4) peso corporal de hoy (`SetStepper` chico + "Anotar") · 5) botón primario · 6) `SectionHeader` "Últimos" + 3 `ExerciseRow`-like de entrenamientos | `SectionHeader`, `DayChips`, `StatTile`, `SetStepper`, `EmptyState` | "Empezar sesión" / "Continuar sesión" (52 dp, ancho completo) |
| 2 | **Entrenar** | 1) barra con bloque actual + progreso (`Meter` de series hechas) · 2) indicador "Superserie 1/2" en `etiqueta` · 3) nombre del ejercicio en `titulo` + "Ronda 2/3" · 4) dos `SetStepper` grandes (peso, reps) · 5) botón "Serie hecha" · 6) si hay descanso: `TimerRing` + "Saltar" / "±15 s" · 7) series anotadas del bloque (`SetRow`) | `Meter`, `SetStepper`, `TimerRing`, `SetRow`, `SectionHeader`, `ConfirmInline` | "Serie hecha" (52 dp) y, en descanso, "Empezar ya" |
| 3 | **Descanso terminado** | 1) "Descansá hasta acá" en Barlow 40 (fondo `bg`, sin barra) · 2) próximo ejercicio + ronda/serie en `subtitulo`/`detalle` · 3) `TimerRing` opcional apagado (queda el tiempo transcurrido) · 4) botón "Empezar" · 5) texto chico "se cierra en N s" si hay auto-cierre | `TimerRing`, botón primario grande | "Empezar" (cierra y vuelve a Entrenar con el cursor en el próximo ítem) |
| 4 | **Rutinas** | 1) `ChipFilter` de plan activo + "Nuevo plan" · 2) `DayChips` de días · 3) `StatTile` de duración/series del día · 4) lista de ítems (`ExerciseRow` con "3×10 · descanso 90 s" en `detalle`, drop set y superserie como sufijo en `muted`) · 5) advertencias en `warn` debajo de la lista · 6) acciones del día: duplicar, renombrar, exportar, eliminar (`ConfirmInline`) | `ChipFilter`, `DayChips`, `StatTile`, `ExerciseRow`, `ConfirmInline`, `BottomSheet` (agregar ítem) | "Editar día" / "Agregar ejercicio" (según modo) |
| 5 | **Ejercicios** | 1) buscador (campo `surface2`, 48 dp) · 2) `ChipFilter` por músculo, scroll horizontal · 3) lista `ExerciseRow` con músculo principal en `detalle` · 4) ficha en `BottomSheet`: nombre, músculo principal, secundarios con factor, segundos por serie, descanso, multiarticular | `ChipFilter`, `ExerciseRow`, `BottomSheet`, `SetStepper`, `ConfirmInline`, `EmptyState` | "Nuevo ejercicio" (botón flotante de texto, no FAB redondo) |
| 6 | **Progreso** | 1) tabs de texto (PR · Volumen · Peso · Músculos) · 2) selector de ejercicio y rango (chips 3M/6M/1A/Todo) · 3) gráfico (Canvas) con título que lleva la unidad · 4) `StatTile` del último valor + variación · 5) leyenda opcional en `detalle` | Tabs de texto, `ChipFilter`, gráfico Canvas, `StatTile`, `EmptyState` | ninguna (pantalla de lectura); el rango es la única acción |
| 7 | **Historial** | 1) sesiones agrupadas por semana ISO (`SectionHeader`) · 2) fila de sesión: fecha, rutina/día, "1.240 kg · 52 min · 18 series" en `detalle` · 3) detalle en `BottomSheet` con series editables (`SetRow`) · 4) badge "PR" en `good` si hubo | `SectionHeader`, `SetRow`, `BottomSheet`, `EmptyState`, `ConfirmInline` | "Ver sesión" (fila entera tocable, 48 dp) |
| 8 | **Comparar** | 1) dos selectores de plan (chips) · 2) tabla de 2 columnas: tiempo, series, multiarticulares, % multiarticular, brazo, drop sets, superseries, conflictos; números tabulares a la derecha · 3) `Meter` por músculo (2 barras) · 4) diferencias en `good`/`warn` según convenga | `ChipFilter`, tabla propia, `Meter`, `EmptyState` | ninguna; cambiar plan es la acción |
| 9 | **Ajustes** | 1) secciones con `SectionHeader` (Entrenamiento · Alertas · Datos · Apariencia · Acerca de) · 2) filas de ajuste con valor a la derecha (`60 s`, `10–20`) · 3) permisos del sistema con estado real (pantalla completa: concedida / no, alarmas exactas: sí / no) en `good`/`bad` · 4) backup/restore: "Exportar" y "Importar" que abren hoja con el JSON y copiar/pegar · 5) versión de la app | `SectionHeader`, filas de ajuste, `SetStepper`, `BottomSheet`, `ConfirmInline` | "Exportar backup" y "Importar" |

## 9. Estados vacíos (copy exacto)

| Pantalla | Frase | Acción |
|---|---|---|
| Hoy | "Todavía no hay rutinas." | "Crear rutina" |
| Entrenar | no aplica (sin sesión no se entra) | — |
| Descanso terminado | no aplica | — |
| Rutinas | "Este día no tiene ejercicios." | "Agregar ejercicio" |
| Ejercicios | "No hay ejercicios con ese músculo." | "Limpiar filtro" |
| Progreso | "Sin datos todavía. Anotá una sesión." | "Ir a Hoy" |
| Historial | "No hay sesiones anotadas." | "Empezar sesión" |
| Comparar | "Necesitás dos rutinas para comparar." | "Crear rutina" |
| Ajustes | no aplica | — |

Errores: texto en `bad` + "Reintentar". Nunca un `Toast` como única señal.

## 10. Accesibilidad y detalles que se notan

- `contentDescription` en todo ícono accionable; nunca `null` en un botón de sólo ícono.
- `Modifier.semantics { role = Role.Button }` en filas tocables.
- Contraste mínimo 4.5:1 (`ink` sobre `surface` ya lo cumple; no inventar grises medios).
- `LocalHapticFeedback` en steppers y al completar una serie. Nunca vibración de navegación.
- `Modifier.imePadding()` en formularios; el botón primario no puede quedar tapado por el teclado.
- `keepScreenOn` en Entrenar, según `keepScreenOn` de Ajustes (default `true`).
- Edge-to-edge con `WindowInsets.safeDrawing`; la barra inferior respeta el gesto del sistema.
- Texto escalable: nada de alturas fijas que corten a 200 % de `fontScale`; las filas crecen.
- El tema entero está en `ui/theme/`: `Tokens.kt` (paleta, espaciado, radios, alturas),
  `GimTypography.kt` (las dos familias y la escala) y `GimCheckTheme.kt` (light/dark/system
  y la proyección a Material 3). Un color nuevo se agrega en `Tokens.kt`, nunca en una
  pantalla.

## 11. Verificación (no hay emulador acá)

- En este equipo **no se puede previsualizar**: no hay SDK de Android ni `aapt2` arm64 (ver
  [`build.md`](build.md)). El gate real de una pantalla es `./gradlew :app:assembleDebug` en CI +
  `lint` + los tests de `:core`.
- El contrato de §3 y §4 tiene test propio y corre en cada push: `:app:testDebugUnitTest` →
  `TokensContractTest` (valores de la tabla, contraste WCAG de todo par de texto, escala
  tipográfica, cifras tabulares y que los 15 roles de Material 3 salgan de `GimText` con la
  familia, el peso y el `tnum` del contrato).
- Los roles de `Typography` **no** se leen de vuelta: en material3 1.4.0 (BOM 2026.09.00)
  `displayLarge`, `bodyLarge`, … devuelven `null` en la JVM por la ruta del constructor de 15
  argumentos, aunque el tema funcione en el APK. Por eso el gate es sobre `GimText` (la fuente
  del mapeo) más un smoke de que `Typography` se construye; lo que se pinta se mira en el
  teléfono.
- Screenshot de UI con Robolectric es opcional (`captureToImage`), nunca gate de merge.
- La verificación visual la hace el dueño instalando el APK. Cada ticket de UI lista **3 bullets
  de qué mirar** en el teléfono (ej.: "la cuenta regresiva no salta", "el botón no lo tapa el
  teclado", "la fila tocada se marca entera durante 100 ms").

## 12. Copy (español rioplatense)

- Voseo, sin signos de admiración, sin "¡Genial!".
- Verbos al frente: "Anotá la serie", "Empezá el descanso", "Cambiar rutina".
- Números con coma decimal y sin ceros de relleno: `82,5 kg`, `1.240 kg`, `1:30`.
- Estados: "En rango", "Bajo", "Sin datos todavía".
- Nombres de pantalla tal como están en esta tabla (Hoy, Entrenar, Rutinas, Ejercicios, Progreso,
  Historial, Comparar, Ajustes). "Descanso terminado" es el título de la pantalla completa.
