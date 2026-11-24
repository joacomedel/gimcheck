package app.gimcheck.core

/**
 * Reglas del **entrenamiento libre** (GIM-49).
 *
 * El dueño cambió el modelo: Entrenar deja de recorrer el día planificado y pasa a ser un
 * entrenamiento abierto donde se cargan series del ejercicio que se elija. Lo único que
 * sigue saliendo del plan es la **superserie**, que acá es un salto puntual: al terminar
 * la serie del ejercicio actual se pasa una vez al siguiente de la cadena, como si se lo
 * hubiera elegido en el selector, y no se vuelve solo por rondas.
 *
 * Estas funciones son puras y viven en `:core` para que la regla se pruebe en la JVM y no
 * dependan de Room ni de Compose.
 */

/**
 * Siguiente ejercicio de la cadena de superserie que arranca en [exerciseId], o `null` si
 * ese ejercicio no es el primero de una cadena en ningún día de [routine].
 *
 * La marca es `DayItem.supersetNext` (la misma que usa `dayStats` y el editor de días):
 * si el ítem del ejercicio está marcado, el salto es al ítem siguiente del mismo día. Se
 * recorre el primer día que tenga esa marca; un ejercicio que aparece en varios días con
 * cadenas distintas no es un caso del producto (el seed no lo tiene) y elegir el primero
 * es determinista.
 */
fun supersetNextExercise(routine: Routine, exerciseId: String): Exercise? {
    routine.days.forEach { day ->
        val items = day.items
        val index = items.indexOfFirst { it.exerciseId == exerciseId && it.supersetNext }
        if (index >= 0) return items.getOrNull(index + 1)?.exercise
    }
    return null
}

/**
 * Descanso que corresponde al ejercicio: el override del ítem del plan si existe (el mismo
 * que usaba la sesión guiada) y, si no, el del catálogo.
 *
 * Se busca el primer ítem del ejercicio en la rutina; el override es una decisión del día,
 * no del catálogo, así que sin plan se usa `exercise.restSeconds`.
 */
fun restSecondsFor(routine: Routine?, exercise: Exercise): Int {
    val item = routine?.days
        ?.asSequence()
        ?.flatMap { it.items.asSequence() }
        ?.firstOrNull { it.exerciseId == exercise.id }
    return item?.restOverrideSeconds ?: exercise.restSeconds
}
