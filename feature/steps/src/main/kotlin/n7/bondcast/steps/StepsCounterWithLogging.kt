package n7.bondcast.steps

import android.util.Log

internal class StepsCounterWithLogging(
    private val origin: StepsCounter,
) : StepsCounter by origin {

    override fun start() {
        Log.i(TAG, "start available=${origin.available}")
        origin.start()
    }

    override fun stop() {
        Log.i(TAG, "stop raw=${origin.rawSteps.value}")
        origin.stop()
    }

    private companion object {
        const val TAG = "StepsCounter"
    }
}
