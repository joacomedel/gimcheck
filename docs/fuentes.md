# Fuentes

Las dos familias de [`diseno.md`](diseno.md) §4, **empaquetadas** como `.ttf` en
`app/src/main/res/font/`. La app no tiene permiso de red: no hay Google Fonts en runtime
ni `androidx.compose.ui:ui-text-google-fonts`.

## Archivos

| Archivo en `res/font/` | Familia | Peso | Peso (nombre) | Bytes | SHA-256 |
|---|---|---|---|---|---|
| `barlow_condensed_semibold.ttf` | Barlow Condensed | 600 | SemiBold | 109 428 | `7b619d14bc2327509a9ef32b0890f709626f7ecc9ff61191c2a4314c5499d2d9` |
| `barlow_condensed_bold.ttf` | Barlow Condensed | 700 | Bold | 109 912 | `e476562ec9c1e16cf16475895b511f08c804f438cc9a9f80a44ea50a0eeb5b65` |
| `ibm_plex_sans_regular.ttf` | IBM Plex Sans | 400 | Regular | 207 768 | `b47ebd72b5ce53b60f909033ff84d6fc12358cc147e2981f8c321ad3f36246b6` |
| `ibm_plex_sans_medium.ttf` | IBM Plex Sans | 500 | Medium | 209 592 | `a494d5a69fa094f7071a9ffbc98e86c85337d1a29f3fc1ff7c2e1c46161ddf27` |
| `ibm_plex_sans_semibold.ttf` | IBM Plex Sans | 600 | SemiBold | 209 768 | `c51119b25f4a296da2b66edf3201b9aa1735091ed7efc1fca0313346f3851a4d` |

Total: **836 KB** de TTF en el repo (el APK los comprime; ver `Riesgos` del ticket GIM-12).

Sólo los cinco pesos que usa el contrato. Cada peso extra son ~200 KB de APK y una
decisión de diseño que nadie pidió: Barlow Condensed *Italic* y los demás pesos de IBM
Plex no se bajan hasta que una pantalla los necesite.

Los nombres de recurso son minúsculas con guiones bajos porque Android no acepta otra
cosa en `res/font/`.

## Origen

| Familia | Autor | Origen del archivo |
|---|---|---|
| Barlow Condensed | Jeremy Tribby (The Barlow Project Authors) | [google/fonts `ofl/barlowcondensed`](https://github.com/google/fonts/tree/main/ofl/barlowcondensed) — estáticos |
| IBM Plex Sans | IBM Corp. | [IBM/plex `packages/plex-sans/fonts/complete/ttf`](https://github.com/IBM/plex/tree/master/packages/plex-sans/fonts/complete/ttf) — estáticos |

Barlow Condensed se bajó del repo de Google Fonts (son estáticos, uno por peso). IBM Plex
Sans se bajó del repo oficial de IBM y **no** de Google Fonts porque ahí sólo se publica
la variable (`IBMPlexSans[wdth,wght].ttf`): con un archivo variable habría que resolver
cada peso con `fontVariationSettings`, que funciona desde API 26 (el `minSdk` de la app)
pero es más frágil de leer y de revisar que cinco archivos estáticos.

```
curl -LO https://raw.githubusercontent.com/google/fonts/main/ofl/barlowcondensed/BarlowCondensed-SemiBold.ttf
curl -LO https://raw.githubusercontent.com/google/fonts/main/ofl/barlowcondensed/BarlowCondensed-Bold.ttf
curl -LO https://raw.githubusercontent.com/IBM/plex/master/packages/plex-sans/fonts/complete/ttf/IBMPlexSans-Regular.ttf
curl -LO https://raw.githubusercontent.com/IBM/plex/master/packages/plex-sans/fonts/complete/ttf/IBMPlexSans-Medium.ttf
curl -LO https://raw.githubusercontent.com/IBM/plex/master/packages/plex-sans/fonts/complete/ttf/IBMPlexSans-SemiBold.ttf
```

## Licencia

Las dos familias están bajo **SIL Open Font License 1.1** (OFL), que permite
empaquetarlas y redistribuirlas dentro de una app, incluso comercial, siempre que la
licencia viaje con las fuentes y que no se vendan por separado.

El texto completo se commitea en dos lugares:

- en el repo, en `docs/fuentes.md` (este archivo) con el detalle de autoría;
- **dentro del APK**, en `app/src/main/res/raw/ofl_barlow_condensed.txt` y
  `app/src/main/res/raw/ofl_ibm_plex_sans.txt`. Es a propósito: la OFL pide que el aviso
  acompañe a la fuente donde se distribuya, y el APK es donde se distribuye. Son 9 KB.

Ninguna de las dos tiene Reserved Font Name que afecte a la app (IBM Plex reserva
"Plex", que la app no usa como nombre de producto).

## Números tabulares

`docs/diseno.md` §4 pide `fontFeatureSettings = "tnum"`.

- **Barlow Condensed** tiene la feature `tnum` (sus cifras por defecto son
  proporcionales). Es la familia de la cuenta regresiva y de los `StatTile`: sin la
  feature, el `40 sp` baila al cambiar de dígito.
- **IBM Plex Sans** ya trae cifras de ancho uniforme y **no** declara `tnum` en el
  archivo (sólo `lnum`/`onum`, para cifras "old style"). Declararla en un `TextStyle` con
  esta familia es inocuo: el motor de texto ignora una feature que la fuente no tiene.

Ambas cosas se verifican en `app/src/test/java/app/gimcheck/ui/theme/TokensContractTest.kt`
(`los numeros son tabulares`).
