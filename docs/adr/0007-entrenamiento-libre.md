# ADR 0007 — Entrenar es un entrenamiento libre, no una sesión guiada por el día

- **Estado**: vigente (decisión del dueño, 2026-11-24; reemplaza el recorrido por rondas de §4
  para la pantalla Entrenar).
- **Contexto**: el dueño pidió «la sesión va a desaparecer, es una única cosa donde voy cargando
  ejercicios» y «al cargar una serie sobre un ejercicio no estoy obligado [a seguir] lo que me
  dice la rutina». El modelo anterior exigía empezar una sesión desde Hoy, recorría el día del
  plan por bloques y rondas y, en una superserie, rotaba el cursor entre los participantes en
  cada ronda. Eso obliga a armar el día exacto en Rutinas y a terminar todas las series
  planificadas para poder cargar otra cosa.
- **Decisión**:
  1. **Un entrenamiento abierto** por dispositivo. Entrar a Entrenar crea la sesión `ACTIVE` si
     no existe y retoma la que haya; salir no la cierra. La fila `session` y las series
     (`session_set`) son las mismas de siempre, así el historial, el volumen y los PR siguen
     funcionando. `routine_id` guarda la rutina activa; `routine_day_id` queda `null` porque el
     entrenamiento ya no pertenece a un día.
  2. **El ejercicio se elige**: el nombre es un botón con los ejercicios de la rutina activa
     (agrupados por día) y `+ Ver todas las rutinas`. Elegir uno no exige que esté en el día.
  3. **La superserie es un salto puntual**: si el ejercicio está marcado `supersetNext` en
     algún día de la rutina activa, anotar una serie pasa **una vez** al siguiente ejercicio de
     la cadena, sin descanso, como si se hubiera elegido en el selector. Después de anotar allí
     no se vuelve solo: descansa y se queda. No hay rotación por rondas.
  4. **El descanso** es el del ejercicio (`restOverrideSeconds` del ítem o `restSeconds` del
     catálogo). Su deadline se persiste en `app_setting` (`training.*`) para sobrevivir al
     cierre de la app; el servicio en primer plano y la alarma exacta de GIM-22 no cambian.
  5. **Terminar** cierra la sesión (`FINISHED`) y muestra el resumen de GIM-24; **descartar** la
     deja `ABANDONED` con sus series y sin resumen. Si un entrenamiento queda abierto de un día
     anterior, al volver a Entrenar se cierra solo (con la última serie como `ended_at`) y se
     arranca uno nuevo: el entrenamiento abierto es del día.
- **Consecuencias**:
  - `core/SessionMachine.kt` (la máquina de rondas) deja de usarse en Entrenar. Sigue existiendo
    para los tests y el import del historial; no se borra en este cambio.
  - `session_state` deja de escribirse para los entrenamientos nuevos. Las sesiones guiadas
    viejas siguen rehidratándose como antes (`SessionResumer`/`GuidedSession`).
  - Peso y reps se escriben tocando el número (GIM-48): el stepper deja de ser solo `−`/`+`.
  - Docs: `docs/especificacion.md` §4 describe el modelo guiado histórico; este ADR es la
    decisión vigente para la pantalla Entrenar. Si se documenta de nuevo §4, se cita este ADR.
