package n7.bondcast.camerax

import android.util.Size
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionFilter
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

/**
 * Подбирает разрешение камеры под целевое (то, что уйдёт в энкодер).
 *
 * Важно не брать разрешение «с запасом»: лишние пиксели гоняются через GL-проход оверлея на каждом
 * кадре, а потом всё равно даунскейлятся энкодером в целевое — это чистый расход GPU и нагрева.
 * Поэтому FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER (недобрать лучше, чем перебрать) плюс фильтр,
 * который ставит точное совпадение на первое место.
 */
internal fun resolutionSelectorFor(target: Size): ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
    .setResolutionFilter(exactThenClosestFilter(target))
    .build()

/**
 * Оставляет ровно целевой размер, если камера его поддерживает.
 *
 * Урезание списка, а не сортировка, — намеренно: при двух `Preview` с эффектом CameraX включает
 * StreamSharing, и родительское разрешение общего потока (то, через которое идёт GL-проход оверлея)
 * выбирается по требованиям детей — `ResolutionsMerger.getMergedResolutions` читает
 * OPTION_SUPPORTED_RESOLUTIONS, а `needToAddSensorResolutions` дотягивает сенсорные разрешения,
 * если размеры детей не ложатся в fallback-соотношение. Пока в списке ребёнка оставались крупные
 * варианты, родитель брался «с запасом» (4096x2304) и кадр даунскейлился в 1080p, теряя резкость.
 *
 * Если точного размера нет — отдаём список как есть, пусть CameraX выбирает сам по стратегии.
 */
internal fun exactThenClosestFilter(target: Size): ResolutionFilter = ResolutionFilter { supported, _ ->
    val exact = supported.filter { it.width == target.width && it.height == target.height }
    exact.ifEmpty {
        supported.sortedWith(
            compareBy(
                // соотношение как у целевого (16:9) — иначе уедем в 4:3 с лишней высотой
                { kotlin.math.abs(it.aspect() - target.aspect()) > ASPECT_EPS },
                // сначала те, что не превышают целевое: даунскейл дешевле лишнего GL-прохода
                { it.pixels() > target.pixels() },
                { kotlin.math.abs(it.pixels() - target.pixels()) },
            ),
        )
    }
}

private const val ASPECT_EPS = 0.01

private fun Size.aspect(): Double = maxOf(width, height).toDouble() / minOf(width, height).toDouble()

private fun Size.pixels(): Long = width.toLong() * height.toLong()

// ВНИМАНИЕ, проверено на A024 и НЕ подошло: SessionConfig.setViewPort(16:9, ROTATION_0) заставил
// CameraX выбрать 4:3-источник 4096x3072 с портретным кропом 1728x3072 и вернуть поворот кадра на
// 90° — то есть разом вернулась проблема поворота и выросло разрешение. Согласовывать кадр превью и
// эфира нужно другим способом (см. TODO.md), viewPort здесь не ставить.
