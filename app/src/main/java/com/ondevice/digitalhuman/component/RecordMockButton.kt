package com.ondevice.digitalhuman.component

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val Mist = Color(0xFFD5E2FF)
private val Pearl = Color(0xFFF6F8FF)
private val Ice = Color(0xFFB9CBFF)
private val Lilac = Color(0xFFE4D5FF)
private val Warm = Color(0xFFF4DDD4)
private val BodyCore = Color(0xFFE7EEFF)
private val BodyMid = Color(0xFF9AADD4)
private val BodyEdge = Color(0xFF3D5278)

private val OrbCanvas = 220.dp
private val IdleRadius = 90.dp
private val HeldRadius = 97.dp
private const val HeldScale = 1.03f

private data class ColorLobe(
    val color: Color,
    val distance: Float,
    val angle: Float,
    val turnWeight: Float,
    val reachScale: Float,
    val idleAlpha: Float,
    val heldAlpha: Float,
)

private val ColorLobes = listOf(
    ColorLobe(Ice, distance = 0.28f, angle = 210f, turnWeight = 1f, reachScale = 1f, idleAlpha = 0.22f, heldAlpha = 0.72f),
    ColorLobe(Lilac, distance = 0.30f, angle = 20f, turnWeight = 0.85f, reachScale = 0.92f, idleAlpha = 0.16f, heldAlpha = 0.64f),
    ColorLobe(Warm, distance = 0.24f, angle = 115f, turnWeight = -0.7f, reachScale = 0.75f, idleAlpha = 0.08f, heldAlpha = 0.40f),
)

/**
 * 模拟按住说话交互的按钮。
 *
 * 黑底上的一颗柔光球，按住时进入聆听。
 *
 * 光球没有硬边。空闲时只有很轻的呼吸。按下后球稍微变亮、变大，
 * 里面的白、蓝、淡紫慢慢流动，像在等对方开口。松开或手指滑出后收回。
 * 按下不等长按超时。按下、松开各有一次轻震。
 *
 * @param onPress 手指刚按下的时刻。在主线程回调。
 * @param onRelease 手指松开或滑出控件的时刻。按下之后一定会回调一次。在主线程回调。
 * @param modifier 建议放在页面底部并水平铺满，光球会自己居中。底部安全区由调用方留。
 */
@Composable
fun RecordMockButton(
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val holding = remember { mutableStateOf(false) }
    val onPressRef = rememberUpdatedState(onPress)
    val onReleaseRef = rememberUpdatedState(onRelease)
    val haptic = LocalHapticFeedback.current
    val pressed by animateFloatAsState(
        targetValue = if (holding.value) 1f else 0f,
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "recordMockPressed",
    )
    val motion = rememberInfiniteTransition(label = "recordMockMotion")
    val wave by motion.animateFloat(
        initialValue = 0f,
        targetValue = (PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 16000, easing = LinearEasing)),
        label = "recordMockWave",
    )
    val swell by motion.animateFloat(
        initialValue = 0f,
        targetValue = (PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 5200, easing = LinearEasing)),
        label = "recordMockSwell",
    )

    DisposableEffect(Unit) {
        onDispose {
            if (holding.value) {
                holding.value = false
                onReleaseRef.value()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        RecordMockFace(
            pressed = pressed,
            wave = wave,
            swell = swell,
            reduceMotion = rememberReduceMotion(),
            modifier = Modifier
                .semantics {
                    role = Role.Button
                    contentDescription = if (holding.value) "正在说话，松开结束" else "按住说话"
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        holding.value = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPressRef.value()
                        waitForUpOrCancellation()
                        releaseHold(holding, haptic, onReleaseRef.value)
                    }
                },
        )
    }
}

private fun releaseHold(
    holding: MutableState<Boolean>,
    haptic: HapticFeedback,
    onRelease: () -> Unit,
) {
    if (!holding.value) return
    holding.value = false
    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    onRelease()
}

@Composable
private fun RecordMockFace(
    pressed: Float,
    wave: Float,
    swell: Float,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier
                .size(OrbCanvas)
                .graphicsLayer {
                    val scale = lerp(1f, HeldScale, pressed)
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            drawListeningOrb(pressed, wave, swell, reduceMotion)
        }
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "按住说话",
                color = Color.White.copy(alpha = 0.72f * (1f - pressed)),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )
            Text(
                text = "我在听",
                color = Color.White.copy(alpha = 0.86f * pressed),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )
        }
    }
}

private fun DrawScope.drawListeningOrb(
    pressed: Float,
    wave: Float,
    swell: Float,
    reduceMotion: Boolean,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val breathe = if (reduceMotion) 0f else sin(swell)
    val radius = lerp(IdleRadius.toPx(), HeldRadius.toPx(), pressed) *
        (1f + breathe * 0.015f * pressed)
    val turn = if (reduceMotion) 24f else wave * (180f / PI.toFloat())
    val sway = radius * 0.03f * breathe * pressed
    val halo = radius * lerp(1.02f, 1.06f, pressed)
    val lobeReach = radius * lerp(0.55f, 0.68f, pressed)

    drawRadial(
        center = center,
        radius = halo,
        stops = arrayOf(
            0f to Mist.copy(alpha = lerp(0.10f, 0.22f, pressed)),
            0.7f to Ice.copy(alpha = lerp(0.05f, 0.10f, pressed)),
            1f to Color.Transparent,
        ),
    )
    drawRadial(
        center = center,
        radius = radius,
        stops = arrayOf(
            0f to BodyCore.copy(alpha = lerp(0.78f, 0.92f, pressed)),
            0.72f to BodyMid.copy(alpha = lerp(0.62f, 0.74f, pressed)),
            0.9f to BodyEdge.copy(alpha = lerp(0.48f, 0.58f, pressed)),
            1f to Color.Transparent,
        ),
    )
    ColorLobes.forEach { lobe ->
        drawLobe(
            center = place(center, radius * lobe.distance, lobe.angle + turn * lobe.turnWeight, sway),
            radius = lobeReach * lobe.reachScale,
            color = lobe.color,
            alpha = lerp(lobe.idleAlpha, lobe.heldAlpha, pressed),
        )
    }
    val shadeCenter = center + Offset(0f, radius * 0.38f)
    drawRadial(
        center = shadeCenter,
        radius = radius * 0.85f,
        stops = arrayOf(
            0f to Color.Black.copy(alpha = lerp(0.18f, 0.28f, pressed)),
            1f to Color.Transparent,
        ),
        blendMode = BlendMode.Multiply,
    )
    drawLobe(
        center = place(center, radius * 0.16f, 300f + turn, sway * 0.5f),
        radius = radius * lerp(0.28f, 0.34f, pressed),
        color = Pearl,
        alpha = lerp(0.35f, 0.7f, pressed),
    )
}

private fun place(origin: Offset, distance: Float, degrees: Float, sway: Float): Offset {
    val angle = degrees * (PI.toFloat() / 180f)
    return origin + Offset(cos(angle) * (distance + sway), sin(angle) * (distance + sway))
}

private fun DrawScope.drawLobe(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
) {
    drawRadial(
        center = center,
        radius = radius,
        stops = arrayOf(
            0f to color.copy(alpha = alpha),
            0.42f to color.copy(alpha = alpha * 0.72f),
            0.78f to color.copy(alpha = alpha * 0.28f),
            1f to color.copy(alpha = 0f),
        ),
    )
}

private fun DrawScope.drawRadial(
    center: Offset,
    radius: Float,
    stops: Array<Pair<Float, Color>>,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    drawCircle(
        brush = Brush.radialGradient(colorStops = stops, center = center, radius = radius),
        radius = radius,
        center = center,
        blendMode = blendMode,
    )
}

@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 360, heightDp = 280, name = "空闲")
@Composable
private fun RecordMockButtonPreview() {
    RecordMockButton(onPress = {}, onRelease = {})
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 360, heightDp = 280, name = "按住")
@Composable
private fun RecordMockButtonHoldingPreview() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        RecordMockFace(
            pressed = 1f,
            wave = 1.2f,
            swell = 0.6f,
            reduceMotion = false,
        )
    }
}
