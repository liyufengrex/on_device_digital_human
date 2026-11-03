package com.ondevice.digitalhuman

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ondevice.digitalhuman.component.RecordMockButton
import com.ondevice.digitalhuman.ui.theme.OnDeviceDigitalHumanTheme
import com.ondevice.live2d.Live2DController
import com.ondevice.live2d.Live2DView
import com.ondevice.live2d.rememberLive2DController

private val CharacterLift = 220.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OnDeviceDigitalHumanTheme {
                val live2d = rememberLive2DController()
                Live2DStage(controller = live2d)
            }
        }
    }
}

@Composable
private fun Live2DStage(
    controller: Live2DController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .background(Color.Black)
            .navigationBarsPadding(),
    ) {
        Live2DView(
            controller = controller,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = CharacterLift),
            onModelLoaded = {
                controller.playIdle()
                controller.playEyes()
            },
        )
        RecordMockButton(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp),
            onPress = { mockSTTStart(controller, context) },
            onRelease = { mockSTTEnd(controller, context) },
        )
    }
}

/**
 * 模拟触发用户开始说话
 */
private fun mockSTTStart(controller: Live2DController, context: Context) {
    controller.playListen()
    val wav = context.assets.open("audio_demo/digital_human_prompt.wav").use { it.readBytes() }
    controller.playAudio(wav)
}

/**
 * 模拟触发用户说话结束
 */
private fun mockSTTEnd(controller: Live2DController, context: Context) {
    controller.releaseListen()
    val wav = context.assets.open("audio_demo/digital_human_answer.wav").use { it.readBytes() }
    controller.playAudio(wav)
}
