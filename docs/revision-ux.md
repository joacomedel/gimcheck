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
| **GIM-29** | Ajustes era un placeholder con steppers y chips que no hacían nada | Todos los ajustes de §8 conectados a `app_setting`, alertas por capas, tema real aplicado por `GimCheckTheme` y **export de backup** completo (JSON del contrato) |
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
   existe en la UI. Queda anotado: el copy de §9 se actualiza cuando se revise el
   contrato completo (no se tocó para no romper `EmptyStateCopyTest` sin actualizar
   `docs/diseno.md` en el mismo cambio).

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

## Cómo se verificó

- `:core`: `./scripts/check-core.sh` (tests nuevos de `Progress` incluidos).
- `:app`: tests JVM con Robolectric de los cuatro ViewModels nuevos
  (`HistorialViewModelTest`, `ProgresoViewModelTest`, `AjustesViewModelTest`,
  `EjerciciosViewModelTest`) + `ChartScaleTest` y `AlertModeTest`. Corren en CI
  (`:app:testDebugUnitTest`), no en el Moto (no hay SDK Android arm64).
- **No hay verificación visual acá**: la hace el dueño instalando el APK. Para esta
  tanda conviene mirar:
  1. Historial: terminar un entrenamiento y verlo en la semana con volumen y PR.
  2. Progreso: tocar los gráficos (cambia el valor de arriba), cambiar Epley/Brzycki en
     Ajustes y volver a Progreso (la curva cambia sin perder datos).
  3. Ajustes: cambiar a tema claro y volver; exportar backup y abrir el JSON.
  4. Ejercicios: crear uno nuevo, usarlo en un día y ver "en 1 lugar".
