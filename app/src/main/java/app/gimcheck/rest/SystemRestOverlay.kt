package app.gimcheck.rest

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.gimcheck.R

/**
 * Overlay de `SYSTEM_ALERT_WINDOW`: **la capa que aparece encima de otra app** (§5, ADR
 * 0004; bug real de GIM-43/44).
 *
 * El full-screen intent no toma la pantalla con el teléfono desbloqueado y otra app en
 * primer plano: Android lo degrada a heads-up. El overlay es la única vía para
 * interrumpir un juego, el navegador o cualquier otra app. Por eso ocupa la pantalla
 * entera, con fondo oscuro y un botón grande ("Empezar serie"), en vez del cartel al pie
 * que había antes.
 *
 * Se retira al tocar el botón, al tocar "Ahora no" o cuando la app vuelve al frente
 * ([RestAlertCenter.dismiss]). No se puede verificar en la JVM: el test instrumentado
 * (`app/src/androidTest/.../RestAlertDeviceTest`) comprueba con `dumpsys window` que la
 * ventana `TYPE_APPLICATION_OVERLAY` de la app está puesta encima de otra app.
 */
class SystemRestOverlay(context: Context) : RestOverlayPresenter {

    private val app: Context = context.applicationContext

    override fun show(message: String, onClick: () -> Unit) {
        hideAny(app)
        val windowManager = app.getSystemService(WindowManager::class.java) ?: return
        val view = buildView(message, onClick)
        val params = fullScreenParams()
        try {
            windowManager.addView(view, params)
            synchronized(lock) {
                active = view
                manager = windowManager
            }
        } catch (e: RuntimeException) {
            // Sin permiso (revocado entre el chequeo y el addView) o el sistema lo
            // rechaza: queda la notificación, que ya está publicada.
        }
    }

    override fun hide() = hideAny(app)

    private fun buildView(message: String, onClick: () -> Unit): View {
        val root = FrameLayout(app).apply {
            setBackgroundColor(Color.parseColor("#F2121212"))
            isClickable = true
        }
        val column = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        column.addView(
            TextView(app).apply {
                text = app.getString(R.string.entrenar_descanso_terminado)
                setTextColor(Color.WHITE)
                textSize = 34f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            },
            matchWidthWrap(),
        )
        column.addView(
            TextView(app).apply {
                text = message
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = 18f
                gravity = Gravity.CENTER
            },
            matchWidthWrap(top = dp(12)),
        )
        column.addView(
            Button(app).apply {
                text = app.getString(R.string.rest_done_empezar)
                textSize = 20f
                isAllCaps = false
                setTextColor(Color.parseColor("#FF121212"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.parseColor("#FFFFB300"))
                }
                setOnClickListener {
                    hideAny(app)
                    onClick()
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64),
            ).apply { topMargin = dp(32) },
        )
        column.addView(
            TextView(app).apply {
                text = app.getString(R.string.rest_alert_ahora_no)
                setTextColor(Color.parseColor("#B3FFFFFF"))
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(20), dp(16), dp(20))
                setOnClickListener { hideAny(app) }
            },
            matchWidthWrap(),
        )
        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        return root
    }

    private fun fullScreenParams(): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun matchWidthWrap(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }

    private fun dp(value: Int): Int =
        (value * app.resources.displayMetrics.density).toInt()

    companion object {

        private val lock = Any()
        private var active: View? = null
        private var manager: WindowManager? = null

        /**
         * Retira el overlay si hay uno puesto. Idempotente y seguro desde cualquier
         * hilo: la app lo llama al volver al frente (`MainActivity.onResume` y
         * [RestDoneActivity.onCreate]) y cada aviso nuevo lo retira antes de dibujar.
         */
        fun hideAny(context: Context) {
            val view: View?
            val windowManager: WindowManager?
            synchronized(lock) {
                view = active
                windowManager = manager
                active = null
                manager = null
            }
            if (view == null) return
            val wm = windowManager ?: context.applicationContext
                .getSystemService(WindowManager::class.java) ?: return
            try {
                wm.removeViewImmediate(view)
            } catch (e: RuntimeException) {
                // Ya no estaba (el sistema lo retiró al morir el proceso).
            }
        }

        /** `true` mientras haya una ventana de overlay agregada por la app. */
        fun isShowing(): Boolean = synchronized(lock) { active != null }
    }
}
