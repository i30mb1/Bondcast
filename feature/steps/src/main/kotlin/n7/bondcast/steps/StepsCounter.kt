package n7.bondcast.steps

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/** Аппаратный шагомер телефона. */
public interface StepsCounter {

    /** Сырое число с датчика: копится с последней перезагрузки телефона. */
    public val rawSteps: StateFlow<Int>

    /** Есть ли в телефоне шагомер вообще — без него оверлей нечем кормить. */
    public val available: Boolean

    public fun start()

    public fun stop()
}

public fun stepsCounter(context: Context): StepsCounter = StepsCounterWithLogging(StepsCounterImpl(context))
