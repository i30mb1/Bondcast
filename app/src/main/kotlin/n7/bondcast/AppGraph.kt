package n7.bondcast

import android.app.Application
import android.content.Context
import android.util.Log
import n7.bondcast.bonding.srtlaClient
import n7.bondcast.chat.impl.chatController
import n7.bondcast.chat.twitch.twitchChat
import n7.bondcast.obs.ObsController
import n7.bondcast.overlay.overlayCompositor
import n7.bondcast.service.StreamService
import n7.bondcast.settings.SettingsRepository
import n7.bondcast.stream.StreamController
import n7.bondcast.stream.StreamForeground
import n7.bondcast.thermal.ThermalMitigations
import n7.bondcast.thermal.thermalMonitor

internal class AppGraph(application: Application) {
    val settingsRepository = SettingsRepository(application)
    val thermalMonitor = thermalMonitor(application) { Log.i("Thermal", it) }
    val thermalMitigations = ThermalMitigations()
    val obsController = ObsController(settingsRepository)
    val overlayCompositor = overlayCompositor()
    val twitchChat = twitchChat(application)
    val chatController = chatController(listOf(twitchChat.source), settingsRepository)
    val streamController =
        StreamController(
            application,
            settingsRepository,
            srtlaClient(application),
            thermalMitigations,
            object : StreamForeground {
                override fun start() = StreamService.start(application)
                override fun stop() = StreamService.stop(application)
            },
            overlayCompositor,
        )
}

internal fun Context.appGraph(): AppGraph = (applicationContext as BondcastApp).graph
