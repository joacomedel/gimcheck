package app.gimcheck.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import app.gimcheck.R
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.ui.theme.GimCheckTheme
import app.gimcheck.ui.theme.GimHeight
import app.gimcheck.ui.theme.GimRadius
import app.gimcheck.ui.theme.GimSpacing
import app.gimcheck.ui.theme.GimStroke
import app.gimcheck.ui.theme.GimText
import app.gimcheck.ui.theme.GimTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * `SetStepper` — `docs/diseno.md` §5: `− valor +` con `tnum` y **háptico
 * `TextHandleMove`**; 40 dp de alto en su forma chica (`GimHeight.stepper`) y 56 dp con
 * el número en `display` cuando va en Entrenar (`large = true`, §5 "en Entrenar va a
 * 56 dp de alto y `display` para el número").
 *
 * El valor es `Double` a propósito: el paso del contrato es `weightStep = 2.5` kg
 * (`especificacion.md` §ajustes) y las reps usan 1, así que un `Int` obligaría a cada
 * pantalla a inventar su unidad. El formateo es del componente: coma decimal y sin ceros
 * de relleno (§12), con cifras tabulares para que el número no baile al cambiar.
 *
 * La háptica es la del contrato (§10 "`LocalHapticFeedback` en steppers"): un tick por
 * toque, nunca en navegación.
 */
@Composable
fun SetStepper(
    value: Double,
    step: Double,
    onValue: (Double) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    min: Double = 0.0,
    max: Double = 999.0,
) {
    val colors = GimTheme.colors
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(GimRadius.control)
    val box = if (large) GimHeight.exerciseRow else GimHeight.stepper
    val downLabel = stringResource(R.string.accion_bajar)
    val upLabel = stringResource(R.string.accion_subir)

    val stepDown: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onValue(steppedValue(value, -step, step, min, max))
    }
    val stepUp: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onValue(steppedValue(value, step, step, min, max))
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(GimSpacing.xs)) {
        Text(text = label, style = GimText.etiqueta, color = colors.muted, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(
                sign = "−",
                description = downLabel,
                size = box,
                shape = shape,
                onClick = stepDown,
            )
            Text(
                text = formatStepperValue(value),
                style = if (large) GimText.display else GimText.stat,
                color = colors.ink,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = GimSpacing.xs),
            )
            StepButton(
                sign = "+",
                description = upLabel,
                size = box,
                shape = shape,
                onClick = stepUp,
            )
        }
    }
}

/**
 * Un lado del stepper. `Box` con borde de 1 dp `line` y radio de control: no es un
 * `IconButton` porque el contrato no trae los íconos de `+`/`−` y "si falta el ícono,
 * texto" (§2).
 */
@Composable
private fun StepButton(
    sign: String,
    description: String,
    size: Dp,
    shape: Shape,
    onClick: () -> Unit,
) {
    val colors = GimTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .background(colors.surface, shape)
            .border(GimStroke.border, colors.line, shape)
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = sign, style = GimText.subtitulo, color = colors.ink2, maxLines = 1)
    }
}

/**
 * Aplica `delta`, lo pega al múltiplo de [step] y lo encierra en `min..max`.
 *
 * Pura y separada del composable para poder probarla en la JVM: sumar 2.5 repetido en
 * `Double` acumula error (`82.50000000000001`) y el formateo ya mostraría otra cosa.
 */
internal fun steppedValue(current: Double, delta: Double, step: Double, min: Double, max: Double): Double {
    val raw = current + delta
    val snapped = if (step > 0.0) (raw / step).roundToInt() * step else raw
    return snapped.coerceIn(min, max)
}

/**
 * Número del stepper con la convención de §12: coma decimal y **sin ceros de relleno**
 * (`82,5`, no `82,50` ni `82.5`). Se redondea a un decimal, que es la precisión del
 * contrato (kg con paso 2.5 y reps enteras).
 */
internal fun formatStepperValue(value: Double): String {
    val tenths = (value * 10.0).roundToInt()
    val whole = tenths / 10
    val fraction = abs(tenths % 10)
    return if (fraction == 0) whole.toString() else "$whole,$fraction"
}

/** Documentación viva: no se renderiza acá (§11). */
@Preview(showBackground = true, backgroundColor = 0xFF121211)
@Composable
private fun SetStepperPreview() {
    GimCheckTheme(mode = ThemeMode.DARK) {
        Column(
            modifier = Modifier.padding(GimSpacing.l),
            verticalArrangement = Arrangement.spacedBy(GimSpacing.l),
        ) {
            SetStepper(value = 82.5, step = 2.5, onValue = {}, label = "Peso (kg)", large = true)
            SetStepper(value = 10.0, step = 1.0, onValue = {}, label = "Reps")
        }
    }
}
