# ADR 0003 — Persistencia local con Room y export/import por JSON

- **Estado**: vigente
- **Contexto**: la web guarda todo en `localStorage` con esquema `rutinas-gym-v1` v21 y una
  función de migración hecha a mano. Los requerimientos van a cambiar (más métricas, más ajustes).
- **Decisión**: Room (SQLite) con entidades normalizadas, migraciones versionadas y **tests de
  migración** desde cada versión previa. Backup y restauración por JSON
  (`schema: "gimcheck", version: N`), con reemplazo o fusión por id. Se mantiene además un
  importador del JSON de la web (v21) para no perder los datos actuales del dueño.
- **Consecuencias**: no hay nube ni sync. Cambiar el modelo implica migración + test, no borrar
  la base. El archivo de backup es la vía de escape y de mudanza de teléfono.
