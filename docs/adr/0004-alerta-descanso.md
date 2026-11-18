# ADR 0004 — Alerta de fin de descanso: pantalla primero, con caídas seguras

- **Estado**: vigente (delegado; el modo por defecto es configurable por el dueño)
- **Contexto**: el pedido es "cuando termina el descanso toma control de la pantalla y me manda a
  la app". Android 14+ restringe `USE_FULL_SCREEN_INTENT` a apps de llamadas/alarma y Android 15+
  degrada el FSI a heads-up con el teléfono desbloqueado. Además los OEM matan servicios.
- **Decisión**: tres capas independientes, en este orden:
  1. **Notificación con full-screen intent** → `RestDoneActivity` (pantalla completa, muestra el
     próximo ejercicio y "Empezar"). Permiso pedido en un paso guiado con explicación.
  2. **Overlay** (`SYSTEM_ALERT_WINDOW`) si el FSI no está concedido.
  3. **Heads-up de alta prioridad** con sonido/vibración del canal — nunca silencio.
  El cronómetro vive en un servicio en primer plano (`specialUse`) **y** en una alarma exacta de
  respaldo (`AlarmManager.setExactAndAllowWhileIdle`) con `canScheduleExactAlarms()` chequeado.
  El deadline se persiste (`rest_ends_at`), así que aunque el proceso muera, al abrir la app el
  estado es correcto. Si la app está en primer plano no se roba la pantalla: aviso inline.
- **Consecuencias**: la app necesita 4 permisos (notificaciones, alarmas exactas, pantalla
  completa, overlay opcional) y una pantalla de Ajustes que muestre el estado real de cada uno.
  Se acepta que en algunos teléfonos la "toma de pantalla" sea una notificación grande: es el
  comportamiento que Android permite, y se documenta en la app.

## Restricciones de plataforma (fijadas 2026-11-18, estado `vigente`)

No son decisiones de producto: son lo que Android y la política de Play permiten hoy. Si
cambian, se cambia acá y en el manifiesto, no se improvisa en un ticket.

1. **`SCHEDULE_EXACT_ALARM`, nunca `USE_EXACT_ALARM`.** Google reserva `USE_EXACT_ALARM`
   para apps de reloj, alarma y calendario; un cronómetro de entrenamiento no califica
   (riesgo de rechazo en Play). Con `SCHEDULE_EXACT_ALARM` el permiso se consulta con
   `AlarmManager.canScheduleExactAlarms()` y se concede desde una pantalla de Ajustes
   (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`), no con un diálogo.
   **Si no está concedido, el cronómetro no se degrada**: el servicio en primer plano
   (capa 1) sigue contando desde `rest_ends_at`; sólo se pierde la red de la alarma.
2. **Servicio en primer plano de tipo `specialUse`**, con
   `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE = "rest timer / workout session"`.
   `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` declarados en el manifiesto.
   Un cronómetro de entrenamiento no entra en `mediaPlayback`, `location` ni `health`
   (este último pide `BODY_SENSORS`); `specialUse` es el tipo honesto y Play lo revisa
   con el subtipo declarado.
3. **`POST_NOTIFICATIONS` pedido en contexto**, al empezar la primera sesión y con la
   explicación de qué se pierde. Si el usuario lo niega, la app sigue funcionando: el
   servicio vive y la pantalla cuenta; sólo no hay notificación visible. `startForeground`
   se llama igual (el sistema permite el servicio aunque la notificación quede oculta).
4. **`USE_FULL_SCREEN_INTENT` no se asume concedido**: se pide con
   `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` y, si el sistema lo niega (Android 14+ lo
   reserva a llamadas/alarma), la capa 3 (heads-up de alta prioridad) es la que queda.
   La implementación de esa pantalla es GIM-23; GIM-22 declara los permisos del
   cronómetro y deja la campana apuntando al canal `rest_done`, que es el que GIM-23
   configura.

**Una sola fuente del tiempo**: `session_state.rest_ends_at` (epoch millis). El
servicio, la alarma y la pantalla leen ese deadline; nadie guarda un "faltan N
segundos". La notificación se reescribe cada 5 s (cada segundo en los últimos 10) con
`setOnlyAlertOnce`, y el servicio se apaga solo al llegar a cero para no dejar una
notificación colgada.

