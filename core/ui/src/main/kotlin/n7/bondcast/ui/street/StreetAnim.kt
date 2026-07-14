package n7.bondcast.ui.street

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

public fun Modifier.pressBounce(interactionSource: InteractionSource): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    // реагируем на само событие нажатия — анимация играет целиком, не рвётся на отпускании
    LaunchedEffect(interactionSource) {
        val scope = this
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                scope.launch {
                    scale.animateTo(0.85f, spring(stiffness = Spring.StiffnessHigh))
                    scale.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessMedium))
                }
                scope.launch {
                    shake.snapTo(0f)
                    shake.animateTo(
                        targetValue = 0f,
                        animationSpec = keyframes {
                            durationMillis = 300
                            (-7f) at 40
                            6f at 100
                            (-4f) at 165
                            2f at 230
                            0f at 300
                        },
                    )
                }
            }
        }
    }
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        rotationZ = shake.value
    }
}

/**
 * Лёгкая тряска-вобл при появлении: панель «дрожит», когда выезжает. Завязана на
 * transition окна, поэтому переигрывается на каждое открытие — даже если прошлое
 * закрытие прервали на полпути и окно не успело покинуть композицию.
 */
@Composable
public fun AnimatedVisibilityScope.shakeOnAppear(amplitude: Float = 2.2f): Modifier {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(transition.targetState) {
        shake.snapTo(0f)
        if (transition.targetState != EnterExitState.Visible) return@LaunchedEffect
        shake.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 440
                (-amplitude) at 120
                amplitude * 0.75f at 210
                (-amplitude * 0.4f) at 300
                amplitude * 0.18f at 370
                0f at 440
            },
        )
    }
    return Modifier.graphicsLayer { rotationZ = shake.value }
}

@Composable
public fun SpringAppear(
    modifier: Modifier = Modifier,
    delayMillis: Long = 0L,
    content: @Composable () -> Unit,
) {
    var appeared by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.8f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "appearScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "appearAlpha",
    )
    LaunchedEffect(Unit) {
        if (delayMillis > 0) delay(delayMillis)
        appeared = true
    }
    Box(
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        },
    ) {
        content()
    }
}

public val panelEnter: EnterTransition =
    fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
        slideInHorizontally(
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        ) { -it }

public val panelExit: ExitTransition =
    fadeOut(spring(stiffness = Spring.StiffnessMedium)) +
        slideOutHorizontally(
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        ) { -it }
