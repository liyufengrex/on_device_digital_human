package com.ondevice.digitalhuman

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.live2d.demo.GLRenderer
import com.live2d.demo.JniBridgeJava
import com.ondevice.digitalhuman.component.RecordMockButton
import com.ondevice.digitalhuman.ui.theme.OnDeviceDigitalHumanTheme

private val CharacterLift = 220.dp

class MainActivity : ComponentActivity() {
    private var glSurfaceView: GLSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JniBridgeJava.setActivityInstance(this)
        JniBridgeJava.setContext(this)
        JniBridgeJava.setModelLoadedListener {
            loadDigitalHumanIDe(glSurfaceView)
        }
        setContent {
            OnDeviceDigitalHumanTheme {
                Live2DStage(
                    modifier = Modifier.fillMaxSize(),
                    onSurfaceCreated = { glSurfaceView = it },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        JniBridgeJava.nativeOnStart()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView?.onResume()
    }

    override fun onPause() {
        glSurfaceView?.onPause()
        JniBridgeJava.nativeOnPause()
        super.onPause()
    }

    override fun onStop() {
        JniBridgeJava.nativeOnStop()
        super.onStop()
    }

    override fun onDestroy() {
        JniBridgeJava.setModelLoadedListener(null)
        JniBridgeJava.nativeOnDestroy()
        super.onDestroy()
    }
}

@Composable
private fun Live2DStage(
    modifier: Modifier = Modifier,
    onSurfaceCreated: (GLSurfaceView) -> Unit,
) {
    val context = LocalContext.current
    val surface = remember { mutableStateOf<GLSurfaceView?>(null) }
    Box(
        modifier = modifier
            .background(Color.Black)
            .navigationBarsPadding(),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = CharacterLift),
            factory = { context ->
                GLSurfaceView(context).apply {
                    setEGLContextClientVersion(2)
                    setRenderer(GLRenderer())
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    surface.value = this
                    onSurfaceCreated(this)
                }
            },
        )
        RecordMockButton(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp),
            onPress = { mockSTTStart(surface.value, context) },
            onRelease = { mockSTTEnd(surface.value, context) },
        )
    }
}

/**
 * AI数字人加载完成后，执行 轻微晃动（呼吸感）+ 眨眼睛
 */
private fun loadDigitalHumanIDe(surface: GLSurfaceView?) {
    swayBody(surface)
    blinkEyes(surface)
}

/**
 * 模拟触发用户开始说话
 */
private fun mockSTTStart(surface: GLSurfaceView?, context: Context) {
    // 让AI数字人做倾听动作
    listenClosely(surface)
    // 让AI数字人播报开口语
    val wav = context.assets.open("audio_demo/digital_human_prompt.wav").use { it.readBytes() }
    playSpeech(surface, wav)
}

/**
 * 模拟触发用户说话结束
 */
private fun mockSTTEnd(surface: GLSurfaceView?, context: Context) {
    // 让AI数字人取消倾听动作
    cancelListen(surface)
    // 模拟 AI智能体 回复返回（中转）音频数据
    val wav = context.assets.open("audio_demo/digital_human_answer.wav").use { it.readBytes() }
    playSpeech(surface, wav)
}

/**
 * 对口型播报音频
 */
private fun playSpeech(surface: GLSurfaceView?, voice: ByteArray) {
    surface?.queueEvent { JniBridgeJava.nativePlayAudio(voice) }
}

/**
 * 让角色按模型里的预设进行眨眼动作
 */
private fun blinkEyes(surface: GLSurfaceView?) {
    surface?.queueEvent { JniBridgeJava.nativePlayEyes() }
}

/**
 * 让角色头部和身体轻微往复晃动，并带一点呼吸
 */
private fun swayBody(surface: GLSurfaceView?) {
    surface?.queueEvent { JniBridgeJava.nativePlayIdle() }
}

/**
 * 让角色把头和身体转到侧耳倾听的角度，并保持这个姿态
 */
private fun listenClosely(surface: GLSurfaceView?) {
    surface?.queueEvent { JniBridgeJava.nativePlayListen() }
}

/**
 * 取消附耳。从当前角度缓缓回到正面
 */
private fun cancelListen(surface: GLSurfaceView?) {
    surface?.queueEvent { JniBridgeJava.nativeReleaseListen() }
}

/**
 * 切换模型。当前只有一个模型时等于重新加载角色，口型、眨眼、晃动和附耳都会回到初始状态。
 */
private fun switchModel(surface: GLSurfaceView?) {
    surface?.queueEvent { JniBridgeJava.nativeSwitchModel() }
}
