package app.gimcheck.rest

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gimcheck.GimCheckApp
import app.gimcheck.MainActivity
import app.gimcheck.R
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.ui.components.AlertDiagnosisBlock
import app.gimcheck.ui.components.PrimaryButton
import app.gimcheck.ui.theme.GimCheckTheme
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **Descanso terminado** a pantalla completa (§5, §6 #3; ADR 0004 capa 1).
 *
 * Se abre sola cuando el descanso llega a cero y el sistema concede
 * `USE_FULL_SCREEN_INTENT`: es el *full-screen intent* de la notificación, el único
 * mecanismo con el que Android deja encender la pantalla de una app en segundo plano.
 *
 * `showWhenLocked` + `turnScreenOn` (API 27+) la muestran sobre el bloqueo y encienden
 * la pantalla; en API 26 se usan los flags equivalentes de la ventana (minSdk 26).
 *
 * Con la app en primer plano **no se usa**: ahí el aviso es inline en Entrenar y esta
 * actividad sólo existe como destino si el usuario la toca (overlay o notificación).
 */
class RestDoneActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        // Abrir la pantalla es "interactuar": se corta la repetición y se retira la
        // notificación de la sombra.
        RestAlertCenter.dismiss(this)
        // Lectura del sistema para el bloque de diagnóstico (GIM-43): se hace una vez,
        // antes de dibujar, y no depende de que la base esté lista.
        val diagnosis = RestAlertDiagnostics.collect(this)
        setContent {
            GimCheckTheme(mode = ThemeMode.SYSTEM) {
                // El próximo ítem se lee de la base cuando la actividad ya está arriba:
                // la pantalla nunca espera a Room para dibujarse.
                val next by produceState<Pair<String, Int>?>(initialValue = null) {
                    value = withContext(Dispatchers.IO) { nextItem() }
                }
                RestDoneContent(
                    next = next,
                    diagnosis = diagnosis,
                    onStart = {
                        startActivity(
                            Intent(this, MainActivity::class.java).addFlags(
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                            ),
                        )
                        finish()
                    },
                )
            }
        }
    }

    /** "Empezar" vuelve a Entrenar: la sesión sigue viva, el cursor ya está en el próximo ítem. */
    private suspend fun nextItem(): Pair<String, Int>? = try {
        val guided = GimCheckApp.container(application).sessions.guidedNow()
        val item = guided?.currentItem
        if (guided == null || item == null) {
            null
        } else {
            item.exercise.name to (guided.roundIndex + 1)
        }
    } catch (error: Throwable) {
        // Sin base legible se muestra el texto genérico: la pantalla no se rompe.
        null
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    companion object {

        /** Abre la pantalla desde un overlay o desde la notificación (contexto de app). */
        fun open(context: Context) {
            val intent = Intent(context, RestDoneActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (error: RuntimeException) {
                // Sin permiso de inicio en segundo plano: la notificación ya está.
            }
        }
    }
}

@Composable
private fun RestDoneContent(
    next: Pair<String, Int>?,
    diagnosis: AlertDiagnosis,
    onStart: () -> Unit,
) {
    val colors = GimTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(GimSpacing.l),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.ph_descanso_titulo),
            style = GimText.display,
            color = colors.ink,
        )
        Text(
            text = if (next == null) {
                stringResource(R.string.rest_done_sin_sesion)
            } else {
                stringResource(R.string.rest_done_siguiente, next.first, next.second)
            },
            style = GimText.detalle,
            color = colors.muted,
            modifier = Modifier.padding(top = GimSpacing.s),
        )
        PrimaryButton(
            label = stringResource(R.string.rest_done_empezar),
            onClick = onStart,
            modifier = Modifier.padding(top = GimSpacing.xxl),
        )
        // Los cinco datos reales del sistema: es lo que el dueño nos pasa cuando el aviso
        // no se comporta como espera (GIM-43). No es un mensaje inventado.
        AlertDiagnosisBlock(
            diagnosis = diagnosis,
            modifier = Modifier.padding(top = GimSpacing.xxl),
        )
    }
}
