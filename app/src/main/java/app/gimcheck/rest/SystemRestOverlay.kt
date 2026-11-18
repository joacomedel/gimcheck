package app.gimcheck.rest

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Overlay de `SYSTEM_ALERT_WINDOW`: el paso intermedio entre el full-screen intent y el
 * heads-up (§5, ADR 0004).
 *
 * Se usa cuando el sistema **niega** `USE_FULL_SCREEN_INTENT` pero el usuario concedió
 * "aparecer encima de otras apps". Es una `TextView` grande abajo de todo, sin foco, que
 * al tocarla abre [RestDoneActivity] y se retira.
 *
 * Este archivo es el que **no se puede verificar en CI**: el overlay real se prueba en
 * el teléfono del dueño (ver la nota de riesgos del ticket).
 */
class SystemRestOverlay(private val context: Context) : RestOverlayPresenter {

    private var overlay: View? = null

    override fun show(message: String, onClick: () -> Unit) {
        hide()
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return
        val view = TextView(context.applicationContext).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#D9101010"))
            setOnClickListener {
                hide()
                onClick()
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }
        overlay = try {
            windowManager.addView(view, params)
            view
        } catch (e: RuntimeException) {
            // Sin permiso (revocado entre el chequeo y el addView) o el sistema lo
            // rechaza: queda la notificación, que ya está publicada.
            null
        }
    }

    override fun hide() {
        val current = overlay ?: return
        overlay = null
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return
        try {
            windowManager.removeView(current)
        } catch (e: RuntimeException) {
            // Ya no estaba.
        }
    }
}
