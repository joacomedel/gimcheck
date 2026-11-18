package app.gimcheck.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.gimcheck.AppContainer

/**
 * El [AppContainer] disponible para las pantallas, o `null` cuando no hay app real
 * (preview, test de composición).
 *
 * Se provee en [GimCheckRoot] desde `MainActivity`, que es el único punto donde ya
 * existe el contenedor. Es `staticCompositionLocalOf` porque el contenedor no cambia
 * en toda la vida del proceso: no tiene sentido invalidar cada composición por él.
 *
 * Una pantalla que necesita datos de la base lo lee y, si es `null`, dibuja su estado
 * vacío: así no hay una dependencia global oculta ni un `!!` en un preview.
 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer?> { null }
