package n7.srtla.scheduler

/**
 * Приёмник действий шедулера без промежуточных аллокаций: io-цикл пишет прямо в сокеты,
 * минуя List и объекты SchedulerAction на каждый пакет. List-перегрузка onEvent сохранена
 * для тестов и бенчмарков, прод (LinkIoLoop) ходит через этот sink.
 */
public interface SchedulerActionSink {
    public fun sendOnLink(linkId: Int, data: ByteArray, length: Int = data.size)
    public fun sendToLocal(data: ByteArray, length: Int = data.size)
}
