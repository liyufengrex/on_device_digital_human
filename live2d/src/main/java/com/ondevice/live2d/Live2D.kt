package com.ondevice.live2d

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.live2d.demo.GLRenderer
import com.live2d.demo.JniBridgeJava

/**
 * 数字人的操作入口。画面由 [Live2DView] 绘制，模型从应用的 assets 读取。
 *
 * 换模型时不改这个库。把新模型放到 `assets/live_2d_model/<目录名>/`，
 * 并且该目录里要有同名的 `<目录名>.model3.json`。贴图、物理、动作和 motionsync 跟这个文件放在一起。
 * 有多个模型时，[switchModel] 按目录名切换。
 *
 * 口型音频由调用方传入完整的 WAV 字节，这个库不打开音频文件。
 */
class Live2DController internal constructor() {
    internal var surface: GLSurfaceView? = null
    private var onLoaded: () -> Unit = {}
    private var started = false
    private var resumed = false

    /**
     * 对口型并播放这段音频。不启动、不停止眨眼。
     *
     * @param wav 带 RIFF 头的线性 PCM WAV。口型分析是 48000 Hz，立体声使用第 0 声道。
     */
    fun playAudio(wav: ByteArray) {
        queue { JniBridgeJava.nativePlayAudio(wav) }
    }

    /**
     * 播放模型里的眼睛动作，并一直重复，直到切换模型。不读音频，不改口型。
     */
    fun playEyes() {
        queue { JniBridgeJava.nativePlayEyes() }
    }

    /**
     * 头和身体轻微晃动，带一点呼吸。一直循环，直到切换模型。不改眼睛和口型。
     */
    fun playIdle() {
        queue { JniBridgeJava.nativePlayIdle() }
    }

    /**
     * 把头和身体转到侧耳倾听的角度并保持。晃动开着时，头和身体停在这个姿态，呼吸可以继续。
     */
    fun playListen() {
        queue { JniBridgeJava.nativePlayListen() }
    }

    /**
     * 从当前角度缓缓回到正面。转到一半时调用，会从那个角度接着回来。
     */
    fun releaseListen() {
        queue { JniBridgeJava.nativeReleaseListen() }
    }

    /**
     * 切换到下一个模型。只有一个模型时等于重新加载，口型、眨眼、晃动和附耳回到初始状态。
     */
    fun switchModel() {
        queue { JniBridgeJava.nativeSwitchModel() }
    }

    internal fun setOnLoaded(callback: () -> Unit) {
        onLoaded = callback
    }

    internal fun dispatchLoaded() {
        onLoaded()
    }

    internal fun attach(surface: GLSurfaceView) {
        this.surface = surface
        if (resumed) {
            surface.onResume()
        }
    }

    internal fun onStart() {
        if (started) {
            return
        }
        started = true
        JniBridgeJava.nativeOnStart()
    }

    internal fun onResume() {
        onStart()
        resumed = true
        surface?.onResume()
    }

    internal fun onPause() {
        if (!resumed) {
            return
        }
        resumed = false
        surface?.onPause()
        JniBridgeJava.nativeOnPause()
    }

    internal fun onStop() {
        if (!started) {
            return
        }
        onPause()
        started = false
        JniBridgeJava.nativeOnStop()
    }

    internal fun release() {
        onStop()
        JniBridgeJava.setModelLoadedListener(null)
        JniBridgeJava.nativeOnDestroy()
    }

    private fun queue(block: () -> Unit) {
        surface?.queueEvent(block)
    }
}

@Composable
fun rememberLive2DController(): Live2DController {
    return remember { Live2DController() }
}

/**
 * 绘制数字人。生命周期跟当前界面走，调用方不用管理 GLSurfaceView。
 *
 * [onModelLoaded] 在模型加载成功后回到主线程。切换模型并重新加载成功时也会调用。
 * 同一界面只放一个。
 */
@Composable
fun Live2DView(
    controller: Live2DController,
    modifier: Modifier = Modifier,
    onModelLoaded: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    SideEffect {
        JniBridgeJava.setContext(context)
        context.findActivity()?.let { JniBridgeJava.setActivityInstance(it) }
        controller.setOnLoaded(onModelLoaded)
        JniBridgeJava.setModelLoadedListener { controller.dispatchLoaded() }
    }

    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.onStart()
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                Lifecycle.Event.ON_PAUSE -> controller.onPause()
                Lifecycle.Event.ON_STOP -> controller.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            controller.onStart()
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            controller.onResume()
        }
        onDispose {
            lifecycle.removeObserver(observer)
            controller.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            JniBridgeJava.setContext(viewContext)
            viewContext.findActivity()?.let { JniBridgeJava.setActivityInstance(it) }
            JniBridgeJava.setModelLoadedListener { controller.dispatchLoaded() }
            GLSurfaceView(viewContext).apply {
                setEGLContextClientVersion(2)
                // 权限弹窗只会暂停页面。保留上下文后，恢复时不会重建 EGL，模型不用重新加载。
                setPreserveEGLContextOnPause(true)
                setRenderer(GLRenderer())
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                controller.attach(this)
            }
        },
    )
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) {
            return current
        }
        current = current.baseContext
    }
    return null
}
