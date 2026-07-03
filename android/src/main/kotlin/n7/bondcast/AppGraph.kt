package n7.bondcast

import android.app.Application
import android.content.Context
import n7.bondcast.bonding.srtlaClient
import n7.bondcast.settings.SettingsRepository
import n7.bondcast.stream.StreamController

internal class AppGraph(application: Application) {
    val settingsRepository = SettingsRepository(application)
    val streamController = StreamController(application, settingsRepository, srtlaClient(application))
}

internal fun Context.appGraph(): AppGraph = (applicationContext as BondcastApp).graph
