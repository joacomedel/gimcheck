package app.gimcheck.data.repository

import app.gimcheck.core.E1rmFormula
import app.gimcheck.data.db.AppDatabase
import app.gimcheck.data.db.AppSettingEntity
import app.gimcheck.data.db.inMemoryDatabase
import app.gimcheck.data.settings.AlertMode
import app.gimcheck.data.settings.AppSettings
import app.gimcheck.data.settings.RestSound
import app.gimcheck.data.settings.RestVibration
import app.gimcheck.data.settings.SettingKeys
import app.gimcheck.data.settings.ThemeMode
import app.gimcheck.data.settings.WeightUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ajustes tipados de §8: defaults, persistencia y **emisión por `Flow`**.
 *
 * El test de emisión es el criterio del ticket: se suscribe una vez, se cambia un
 * ajuste y la misma suscripción recibe el valor nuevo, sin recrear el repositorio ni
 * reiniciar la app.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repository = SettingsRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `sin filas los ajustes son los defaults de la seccion 8`() = runBlocking {
        assertEquals(
            AppSettings(
                warm = 5,
                trans = 30,
                drops = 2,
                dropSec = 25,
                dropVol = 0.5,
                optMin = 10,
                optMax = 20,
                includeIndirect = false,
                e1rmFormula = E1rmFormula.EPLEY,
                weightStep = 2.5,
                unit = WeightUnit.KG,
                alertMode = AlertMode.SCREEN_VIBRATE,
                restSound = RestSound.DEFAULT,
                restVibration = RestVibration.DOUBLE,
                restAlertRepeat = 0,
                autoFillLast = true,
                bodyweightVolume = false,
                theme = ThemeMode.SYSTEM,
                keepScreenOn = true,
            ),
            repository.current(),
        )
    }

    @Test
    fun `cambiar un ajuste emite por Flow sin reiniciar`() = runBlocking {
        val emissions = Channel<AppSettings>(Channel.UNLIMITED)
        val subscription = launch(Dispatchers.IO) {
            repository.settings.collect { emissions.send(it) }
        }
        try {
            val initial = withTimeout(TIMEOUT) { emissions.receive() }
            assertNotEquals(9, initial.warm)

            withTimeout(TIMEOUT) { repository.update { it.copy(warm = 9) } }

            // Una escritura puede invalidar la tabla más de una vez: se espera el valor.
            var latest = withTimeout(TIMEOUT) { emissions.receive() }
            while (latest.warm != 9) latest = withTimeout(TIMEOUT) { emissions.receive() }
            assertEquals(9, latest.warm)
        } finally {
            subscription.cancel()
        }
    }

    @Test
    fun `todos los campos sobreviven la ida y vuelta por la base`() = runBlocking {
        val custom = AppSettings(
            warm = 7,
            trans = 45,
            drops = 3,
            dropSec = 20,
            dropVol = 0.75,
            optMin = 8,
            optMax = 24,
            includeIndirect = true,
            e1rmFormula = E1rmFormula.BRZYCKI,
            weightStep = 1.25,
            unit = WeightUnit.LB,
            alertMode = AlertMode.SCREEN_VIBRATE_SOUND,
            restSound = RestSound.CHIME,
            restVibration = RestVibration.LONG,
            restAlertRepeat = 15,
            autoFillLast = false,
            bodyweightVolume = true,
            theme = ThemeMode.DARK,
            keepScreenOn = false,
        )

        repository.set(custom)

        // Otra instancia sobre la misma base: es lo que ve un proceso nuevo.
        assertEquals(custom, SettingsRepository(db).current())
        // Y la Flow del repositorio ya suscripto emite lo mismo.
        assertEquals(custom, repository.settings.first())
    }

    @Test
    fun `una fila corrupta cae al default en vez de romper`() = runBlocking {
        db.settingDao().put(AppSettingEntity(key = SettingKeys.WARM, value = "no-es-un-numero"))
        db.settingDao().put(AppSettingEntity(key = SettingKeys.ALERT_MODE, value = "MODO_FUTURO"))

        val settings = repository.current()

        assertEquals(AppSettings.DEFAULT.warm, settings.warm)
        assertEquals(AppSettings.DEFAULT.alertMode, settings.alertMode)
    }

    @Test
    fun `escribir guarda las 19 claves y reset vuelve a los defaults`() = runBlocking {
        repository.update { it.copy(theme = ThemeMode.LIGHT, keepScreenOn = false) }
        assertEquals(19, db.settingDao().all().size)
        assertTrue(db.settingDao().all().any { it.key == SettingKeys.THEME && it.value == "LIGHT" })

        repository.reset()

        assertEquals(AppSettings.DEFAULT, repository.current())
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
