package app.gimcheck.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * El "abrir la app" de la sesión guiada (GIM-39): el llamador del ciclo de vida.
 *
 * **Por qué existe**: [SessionRepository.resumeActive] aplica `Restore(now)` con la
 * máquina de `:core` (deadline vencido → "descanso terminado"; deadline vigente →
 * restante calculado desde el deadline), pero un método que nadie llama no arregla
 * nada: al abrir la app el aviso no aparecía solo. Este objeto es el punto único que
 * el `LifecycleEventObserver` de la raíz de Compose invoca en `ON_START`, con el
 * reloj real.
 *
 * **Es una fuente de estado propia, no un espejo de [SessionRepository.observeGuided]**:
 * [resumed] publica el resultado de la rehidratación —estado ya pasado por `Restore`
 * y efectos en orden—, así que la pantalla que se abre tiene de dónde leerlo aunque
 * nadie haya llamado a `Tick`. `observeGuided` sigue sirviendo para la pantalla que
 * ya está viva (sigue el cursor cuando otra corrutina lo mueve).
 *
 * **Idempotente**: `Restore` sobre un estado que no está en `RESTING` no hace nada,
 * y sobre un deadline vencido deja el cursor limpio; `ON_START` puede dispararse
 * varias veces (recreación por rotación, volver del background) sin duplicar el
 * aviso en la base. El [gate] serializa las reanudaciones para que dos `ON_START`
 * solapados no lean el mismo `session_state` viejo y emitan `ShowRestDone` dos veces.
 *
 * El alcance de [scope] es de la app ([app.gimcheck.AppContainer]), no de la
 * activity: la rehidratación tiene que terminar aunque la activity se recree.
 */
class SessionResumer(
    private val sessions: SessionRepository,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
) {

    private val gate = Mutex()

    private val _resumed = MutableStateFlow<ActiveSession?>(null)

    /**
     * Lo que hay que mostrar al abrir: `null` hasta la primera reanudación, después
     * [ActiveSession.None], [ActiveSession.Resumable] o [ActiveSession.MissingPlan].
     */
    val resumed: StateFlow<ActiveSession?> = _resumed.asStateFlow()

    /**
     * Enganche del ciclo de vida (`ON_START`/`ON_RESUME`): reanuda en [scope] con el
     * reloj real y publica el resultado en [resumed]. Se llama desde
     * `GimCheckRoot`, que es donde vive el árbol de Compose.
     */
    fun onAppForeground(): Job = scope.launch { resume() }

    /**
     * Reanuda y publica. [at] existe para los tests: en la app siempre es el reloj real.
     *
     * @return lo mismo que queda en [resumed]; el llamador que necesita ejecutar los
     *   efectos (cronómetro, aviso de "descanso terminado") los lee de acá.
     */
    suspend fun resume(at: Instant = now()): ActiveSession = gate.withLock {
        sessions.resumeActive(at).also { _resumed.value = it }
    }
}
