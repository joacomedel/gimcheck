# Revisión de UX y tickets pendientes — 2026-11-27

Revisión hecha sobre `master` (`d3bc600`) antes de esta tanda, leyendo el tablero de
AgentBank (`proyecto gimcheck`), el código de `:app` y las nueve pantallas de
`docs/diseno.md`. El objetivo era encontrar los huecos que más se notan al **usar** la
app, no sólo los que faltan en la lista de tickets.

## Lo que estaba pendiente y se cerró en esta tanda

| Ticket | Qué faltaba | Qué se hizo |
|---|---|---|
| **GIM-25** | Historial era un placeholder: terminabas un entrenamiento y no quedaba nada visible | Lista por semana ISO, detalle por ejercicio, corrección de peso/reps, borrar serie y borrar entrenamiento con confirmación en el lugar |
| **GIM-14** | No existía motor de gráficos | `ui/chart`: escalas con ticks "lindos", línea con área y puntos de PR, barras de volumen, selección por toque. Sin librerías |
| **GIM-26** | No había gráfico de PR | Progreso → PR: selector de ejercicio, rango 3 M/6 M/1 A/Todo, curva de e1RM por sesión y tarjeta con valor, fecha, serie y delta contra el PR anterior |
| **GIM-27** | No había volumen por semana ni series por músculo | Progreso → Volumen: barras por semana o por sesión, semana actual vs anterior y `Meter` por músculo con banda y estado |
| **GIM-28** | El peso corporal sólo se anotaba en Hoy | Progreso → Peso: curva, promedio móvil de 7 días, delta desde el inicio y registros editables/borrables |
| **GIM-29** | Ajustes era un placeholder con steppers y chips que no hacían nada | 18 de las 19 claves de §8 conectadas a `app_setting` (falta `unit`, que exige conversión global: GIM-50), alertas por capas, tema real aplicado por `GimCheckTheme` y **export de backup** completo (JSON del contrato) |
| **GIM-18** | El catálogo de ejercicios era un placeholder | Buscador, filtro por músculo, alta/edición con principal, multiarticular, carga, tiempos y secundarios con factor; aviso "en N lugares" y alta de músculos sin duplicados |
| **GIM-19** | Comparar era un placeholder | Dos selectores A/B, tabla resumen con delta (B−A), duración por día, series por músculo con banda y toggle de indirectas |

## Huecos de UX que no tenían ticket (y qué pasó)

1. **Hoy seguía hablando de "sesión"** (`Empezar sesión` / `Continuar sesión`) aunque el
   ADR 0007 y GIM-49 cambiaron Entrenar a un entrenamiento libre. Peor: como el
   entrenamiento libre no tiene `routine_day_id`, `resumeActive` lo clasificaba como
   `MissingPlan` y Hoy **nunca** mostraba el entrenamiento abierto.
   → Hoy ahora observa la sesión `ACTIVE` directo, muestra "Entrenamiento abierto · 12
   min", el botón dice "Seguir entrenando" y el aviso de otro día usa la fecha real.
2. **El tema era un ajuste muerto.** `ThemeMode` existía y `MainActivity` lo aplicaba,
   pero Ajustes no tenía forma de cambiarlo. → Chips Sistema/Claro/Oscuro conectados.
3. **La fórmula de e1RM no se podía elegir** y por lo tanto Progreso no tenía el
   "cambiar de fórmula repinta la curva" del criterio. → Chips Epley/Brzycki en Ajustes.
4. **Los ajustes de entrenamiento eran decorativos** (calentamiento, traslado, drops,
   rango semanal). → Steppers reales; los consume `:core` al calcular duración/volumen.
5. **No había forma de sacar los datos del teléfono.** El import de la web existía,
   pero un backup propio no. → "Datos → Exportar backup" arma el JSON completo con
   `exportBackup` de `:core` y lo guarda con el selector del sistema (sin permisos de
   storage).
6. **No se podía crear un ejercicio nuevo** (sólo usar los 32 del seed). → Alta completa
   en el catálogo, con validación de nombre duplicado y factor de secundarios.
7. **El estado vacío de Historial decía "Empezar sesión"**, un concepto que ya no
   existe en la UI. → Copia a "Entrenar", con el test de copy y `docs/diseno.md` §9
   actualizados en el mismo cambio.

## Lo que sigue pendiente (y por qué no se cerró acá)

| Ticket | Estado | Por qué |
|---|---|---|
| **GIM-22 / GIM-23** | en revisión | El cronómetro y el aviso de fin de descanso necesitan probarse **en el teléfono del dueño**: con la app cerrada, pantalla bloqueada y las 8 combinaciones de alerta. En este equipo no hay emulador |
| **GIM-30** | abierto | Release por tag y firma: depende de decidir si se publica debug firmado con la debug key o se configuran los secrets del keystore |
| **GIM-31** | abierto | Checklist de QA en el teléfono; es el paso que valida GIM-22/23 y la UX real |
| **GIM-32** | abierto | Guía de instalación y limitaciones de Android; se escribe mejor después del QA |
| **GIM-33 / GIM-34** | abierto | Robustez del gate local (`check-core.sh`) y correcciones de docs. No son UX de la app; tienen criterios propios |
| **GIM-15** | abierto | Auditoría de diseño de las 9 pantallas: ahora que existen, se puede hacer contra `docs/diseno.md` |
| **GIM-41 / GIM-42** | obsoletos | El ADR 0007 vigente es `docs/adr/0007-entrenamiento-libre.md` y GIM-49 ya implementó el entrenamiento libre. Deberían cancelarse o reescribirse: piden un "selector rutina → día → ejercicio" y una rotación por rondas que el ADR vigente descartó |
| **GIM-50** | abierto | Unidades kg/lb: el ajuste existe pero la UI muestra kg en todos lados. Requiere conversión global y test de ida y vuelta |
| **GIM-51** | abierto | Restaurar un backup propio (hoy sólo se exporta; el import existente es el de la web) |
| **GIM-52** | abierto | Búsqueda/filtro en Historial: la lista por semana se hace larga con muchos meses |

## Transferencia de rutinas (GIM-53, 2026-11-27)

El dueño pidió un exportador y un importador. Antes sólo existía "Exportar plan" en el
formato de la web, que servía para pegarlo en la web pero **no se podía volver a importar**
(el import de Ajustes lo rechazaba por no ser un backup completo). Ahora Rutinas tiene una
sección **Transferir**:

- **Exportar todas** → `gimcheck-rutinas-AAAA-MM-DD.json`, un pack propio con las rutinas,
  sus días/ítems y sólo los músculos y ejercicios que usan. Se guarda con el selector del
  sistema, sin permisos de storage.
- **Importar** → acepta ese pack, un plan exportado de la web (`tipo: "plan"`) o un backup
  completo. Muestra cuántas rutinas, días y ejercicios nuevos va a agregar y pide
  confirmación. **No borra nada**: las rutinas existentes y el historial quedan intactos.
- Los músculos y ejercicios se reutilizan por id o por nombre normalizado (sin acentos ni
  mayúsculas). Importar dos veces no pisa: la segunda copia se llama "… (importada)".
- Las rutinas importadas quedan seleccionadas al terminar.

## Quality of life que ya está en la app

Vale la pena tenerlo presente al probar, porque son las cosas que hacen que el día a día
no duela:

- **Pantalla encendida** durante el entrenamiento (`keepScreenOn`, ajuste real).
- **Precarga del último peso/reps** del ejercicio (`autoFillLast`), y si no hay historial,
  el objetivo del ítem.
- **Descanso con ±15 s, saltar y repetir aviso**; el deadline se persiste, así que cerrar
  la app no pierde la cuenta.
- **Peso corporal del día** en un toque desde Hoy, con corrección.
- **Entrenamiento abierto** visible en Hoy ("Seguir entrenando · N min").
- **Tema** sistema/claro/oscuro y **fórmula e1RM** Epley/Brzycki desde Ajustes.
- **Historial corregible**: editar peso/reps, borrar una serie o un entrenamiento.
- **Export de backup** completo y **export/import de rutinas**; el import de la web sigue
  disponible para no perder los datos del navegador.

## Deudas menores (no bloquean usar la app)

- **Unidades lb**: el modelo y los ajustes ya tienen `unit`, pero toda la UI muestra kg.
  Un toggle que no convierte sería peor que no tenerlo; queda como ticket aparte.
- **Restaurar un backup propio**: se exporta el JSON del contrato, pero el import de
  Ajustes sigue siendo el de la web. Falta un import que reemplace el estado completo
  (con sesiones y métricas).
- **RPE y notas por serie**: la tabla `session_set` los guarda y el backup los lleva,
  pero Entrenar no los pide. No están en el MVP del dueño.
- **Búsqueda en Historial**: con muchos meses, la lista por semana se hace larga. Un
  filtro por ejercicio/fecha es la próxima mejora natural.
- **Ver resumen desde Hoy**: el resumen del día ya existe al terminar; desde el
  entrenamiento abierto se llega saliendo de Entrenar, no hay un acceso directo.

## El cierre al salir de Entrenar (2026-11-27)

El reporte del dueño ("al salir me saca de la app y cuando vuelvo a intentar entrar no
anda") tenía dos causas, las dos reproducidas en el emulador:

1. **Hoy se caía al calcular los minutos del entrenamiento abierto.** `HoyViewModel`
   hacía `Duration.between(startedAt.toJavaLocalDateTime(), nowInstant())`: mezclaba un
   `LocalDateTime` con un `Instant`, y `java.time` lanza `DateTimeException`. La rama
   corre cuando hay una sesión `ACTIVE`, o sea al volver de Entrenar y al reabrir la app
   con el entrenamiento abierto; la excepción mataba el proceso en el dispatcher de la
   UI. De ahí el "me saca" y el "no entra".
2. **El overlay de "Descanso terminado" podía quedar pegado encima de la app.**
   `SystemRestOverlay.show` agregaba la ventana y después publicaba `active`; si un
   `dismiss` caía en el medio, la ventana quedaba sin referencia y no había forma de
   retirarla. Además el retiro usaba `removeViewImmediate` desde el hilo que llamaba:
   desde uno que no era el main lanzaba `CalledFromWrongThreadException`, el `catch` la
   tragaba y la ventana seguía visible. Al abrir la app, Hoy quedaba tapada y parecía
   que no abría.

Arreglos: Hoy usa `now()` (LocalDateTime) para la duración, con test JVM que crea una
sesión abierta y verifica los minutos; el overlay marca la vista como pendiente antes de
`addView`, la retira si el hide la canceló y remueve siempre en el main looper. El smoke
test instrumentado `EntrenarExitDeviceTest` recorre entrar → Salir → confirmar → Hoy con
"Seguir entrenando" → home → reabrir.

## Cómo se verificó

- `:core`: `./scripts/check-core.sh` (137 tests; incluye `RoutinePackTest` con ida y
  vuelta del pack, plan de la web y backup).
- `:app`: tests JVM con Robolectric de los ViewModels y de `RoutineTransfer`
  (`RoutineTransferTest`: pack completo, reutilización por nombre, plan de la web,
  doble import y archivo inválido; `HoyViewModelTest`: entrenamiento abierto y minutos).
  Corren en CI (`:app:testDebugUnitTest`), no en el Moto (no hay SDK Android arm64).
- Emulador API 34: `RestAlertDeviceTest` (overlay, alarma, canal) y
  `EntrenarExitDeviceTest` (entrar/salir/reabrir) en verde.
- CI: run [46057301725](https://github.com/joacomedel/gimcheck/actions/runs/46057301725),
  los dos jobs en `success` (commit `16c3e09`, APK 14.218.093 B).
- **No hay verificación visual acá**: la hace el dueño instalando el APK. Para esta
  tanda conviene mirar:
  1. Historial: terminar un entrenamiento y verlo en la semana con volumen y PR.
  2. Progreso: tocar los gráficos (cambia el valor de arriba), cambiar Epley/Brzycki en
     Ajustes y volver a Progreso (la curva cambia sin perder datos).
  3. Ajustes: cambiar a tema claro y volver; exportar backup y abrir el JSON.
  4. Ejercicios: crear uno nuevo, usarlo en un día y ver "en 1 lugar".
  5. Rutinas: "Exportar todas", guardar el archivo y volver a importarlo; debería
     aparecer una copia con " (importada)" y la rutina nueva seleccionada. Repetir con
     un plan exportado de la web.
