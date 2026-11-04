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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ondevice.digitalhuman.component.RecordMockButton
import com.ondevice.digitalhuman.tools.PermissionMixin
import com.ondevice.digitalhuman.tools.PermissionTool
import com.ondevice.digitalhuman.tools.RecordManager
import com.ondevice.digitalhuman.ui.theme.OnDeviceDigitalHumanTheme
import com.ondevice.live2d.Live2DController
import com.ondevice.live2d.Live2DView
import com.ondevice.live2d.rememberLive2DController

private val CharacterLift = 220.dp

class MainActivity : ComponentActivity(), PermissionMixin {
    private val recordPermission = PermissionTool(this)

    override fun hasRecordPermission(): Boolean = recordPermission.hasRecordPermission()

    override fun requestRecordPermission(onResult: (Boolean) -> Unit) {
        recordPermission.requestRecordPermission(onResult)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OnDeviceDigitalHumanTheme {
                val live2d = rememberLive2DController()
                Live2DStage(
                    controller = live2d,
                    permission = this@MainActivity,
                )
            }
        }
    }
}

@Composable
private fun Live2DStage(
    controller: Live2DController,
    permission: PermissionMixin,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val recorder = remember { RecordManager() }
    DisposableEffect(recorder) {
        onDispose { recorder.stopRecording() }
    }
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
                val wav = context.assets.open("audio_demo/digital_human_prompt.wav").use { it.readBytes() }
                controller.playAudio(wav)
            },
        )
        RecordMockButton(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp),
            onPress = { mockSTTStart(controller, permission, recorder) },
            onRelease = { mockSTTEnd(controller, recorder) },
        )
    }
}

/**
 * 模拟触发用户开始说话。
 * 还没有录音权限时先申请，这一次不打开麦克风。
 */
private fun mockSTTStart(
    controller: Live2DController,
    permission: PermissionMixin,
    recorder: RecordManager,
) {
    if (!permission.hasRecordPermission()) {
        permission.requestRecordPermission()
        return
    }
    controller.playListen()
    recorder.startRecording()
}

/**
 * 模拟触发用户说话结束。
 * 真实场景是调用AI大模型拿到回复后交由数字人播报 。
 * 这里仅模拟（把刚录下的声音交给数字人播报）。
 */
private fun mockSTTEnd(controller: Live2DController, recorder: RecordManager) {
    controller.releaseListen()
    val wav = recorder.stopRecording() ?: return
    controller.playAudio(wav)
}
