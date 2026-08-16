package n7.bondcast.steps

/**
 * Арифметика шагомера.
 *
 * `TYPE_STEP_COUNTER` копит шаги с последней перезагрузки телефона и сбрасывается только вместе с
 * ней, поэтому «обнулить» — это запомнить текущее сырое число как базу и вычитать её.
 */
public object StepsMath {

    /** Что показываем зрителю: сырое число минус база. */
    public fun displayed(raw: Int, baseline: Int): Int = if (baselineStale(raw, baseline)) raw else raw - baseline

    /**
     * База протухла — телефон перезагружали, датчик начал счёт заново.
     *
     * Без этой проверки счётчик ушёл бы в минус и висел бы отрицательным до следующего обнуления.
     */
    public fun baselineStale(raw: Int, baseline: Int): Boolean = baseline > raw

    /** «12 345» — с телефона зрителя читается лучше, чем сплошное число. */
    public fun format(steps: Int): String = steps.toString()
        .reversed()
        .chunked(3)
        .joinToString(" ")
        .reversed()
}
