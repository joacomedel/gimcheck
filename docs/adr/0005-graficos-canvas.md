# ADR 0005 — Gráficos propios con Canvas de Compose

- **Estado**: vigente
- **Contexto**: hacen falta tres gráficos (e1RM/PR, volumen, peso corporal) y opcionalmente
  barras por músculo. Las librerías habituales (MPAndroidChart, Vico) traen View/Compose extra,
  estilos propios que hay que domar y un ciclo de vida de mantenimiento ajeno.
- **Decisión**: `Canvas` de Compose con un pequeño motor de escalas compartido
  (`ui/chart/ChartScales.kt`): dominio→píxeles, ticks "lindos", path builder. ~300 líneas.
- **Consecuencias**: control total del estilo (coherente con el contrato de diseño), cero
  dependencias, cero riesgo de incompatibilidad con Compose BOM. El costo es escribir el hit-test
  del tooltip a mano (una función).
