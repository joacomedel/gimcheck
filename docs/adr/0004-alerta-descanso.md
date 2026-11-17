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
