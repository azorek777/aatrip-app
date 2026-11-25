package pl.aatrip

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Punkt wejścia, przez który Android Auto uruchamia aplikację. */
class AaTripCarAppService : CarAppService() {

    // Aplikacja prywatna, instalowana ręcznie: akceptujemy każdego hosta.
    // Przy publicznej dystrybucji trzeba tu wstawić listę zaufanych hostów.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = AaTripSession()
}

class AaTripSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = TestScreen(carContext)
}
