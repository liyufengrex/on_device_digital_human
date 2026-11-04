package com.ondevice.digitalhuman.tools

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 录音权限。Activity 实现这个接口，再把 [PermissionTool] 的方法转出去，申请能力就混进页面。
 *
 * 已授权时 [requestRecordPermission] 不弹窗，直接回调 true。
 */
interface PermissionMixin {
    fun hasRecordPermission(): Boolean

    fun requestRecordPermission(onResult: (Boolean) -> Unit = {})
}

/**
 * [PermissionMixin] 的实现。构造时向 Activity 注册权限回调，要在 Activity 初始化阶段创建。
 */
class PermissionTool(
    private val activity: ComponentActivity,
) : PermissionMixin {
    private var onResult: ((Boolean) -> Unit)? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val callback = onResult
        onResult = null
        callback?.invoke(granted)
    }

    override fun hasRecordPermission(): Boolean {
        return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun requestRecordPermission(onResult: (Boolean) -> Unit) {
        if (hasRecordPermission()) {
            onResult(true)
            return
        }
        val waiting = this.onResult != null
        this.onResult = onResult
        if (!waiting) {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
