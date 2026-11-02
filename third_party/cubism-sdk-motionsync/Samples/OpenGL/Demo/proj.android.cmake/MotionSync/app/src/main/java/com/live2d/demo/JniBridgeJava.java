/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

package com.live2d.demo;

import android.app.Activity;
import android.content.Context;

import java.io.IOException;
import java.io.InputStream;

public class JniBridgeJava {
    // Native -----------------------------------------------------------------

    public static native void nativeOnStart();

    public static native void nativeOnPause();

    public static native void nativeOnStop();

    public static native void nativeOnDestroy();

    public static native void nativeOnSurfaceCreated();

    public static native void nativeOnSurfaceChanged(int width, int height);

    public static native void nativeOnDrawFrame();

    public static native void nativeOnTouchesBegan(float pointX, float pointY);

    public static native void nativeOnTouchesEnded(float pointX, float pointY);

    public static native void nativeOnTouchesMoved(float pointX, float pointY);

    /**
     * 只做口型，并播放这段音频。不启动、不停止眼睛动作。
     * 必须在 GL 线程调用（{@code GLSurfaceView.queueEvent}）。
     *
     * @param wav 带 RIFF 头的线性 PCM WAV，不要传去掉文件头的裸 PCM。
     *            口型分析上下文是 48000 Hz，立体声使用第 0 声道。8/16/24 bit 可以解析。
     * @return 解析成功并开始播放时为 true
     */
    public static native boolean nativePlayAudio(byte[] wav);

    /**
     * 只播放模型里唯一的眼睛动作。不读音频，不改口型缓冲。
     * 必须在 GL 线程调用（{@code GLSurfaceView.queueEvent}）。
     * 眨眼会按动作文件反复播放，直到切换模型。再次调用会从头开始。
     *
     * @return 动作开始播放时为 true
     */
    public static native boolean nativePlayEyes();

    /**
     * 让头和身体轻微往复晃动，并带一点呼吸。头发会跟着角度摆。
     * 不播放音频，不改眼睛和口型。调用后一直循环，直到切换模型。
     * 必须在 GL 线程调用（{@code GLSurfaceView.queueEvent}）。
     * 晃动幅度是各参数活动范围的一小部分，没有额外入参。
     *
     * @return 模型已加载并开始晃动时为 true
     */
    public static native boolean nativePlayIdle();

    /**
     * 把头和身体转到侧耳倾听的角度，并保持这个姿态。
     * 不播放音频，不改眼睛和口型。晃动如果也开着，头和身体仍停在这个姿态，呼吸可以继续。
     * 调用 {@link #nativeReleaseListen()} 会缓缓回到正面。切换模型会立刻回到正面。
     * 没有额外入参。必须在 GL 线程调用（{@code GLSurfaceView.queueEvent}）。
     *
     * @return 模型已加载并开始转向该姿态时为 true
     */
    public static native boolean nativePlayListen();

    /**
     * 从当前的附耳角度缓缓回到正面。转头进行到一半时调用，会从那个角度接着回来。
     * 不播放音频，不改眼睛和口型。回到正面后，若晃动开着，头和身体重新跟着晃。
     * 当前不在附耳姿态时返回 false。没有额外入参。
     * 必须在 GL 线程调用（{@code GLSurfaceView.queueEvent}）。
     *
     * @return 开始回正时为 true
     */
    public static native boolean nativeReleaseListen();

    public static native void nativePlayNext();

    public static native void nativeSwitchModel();

    // Java -----------------------------------------------------------------

    public static void setContext(Context context) {
        JniBridgeJava.context = context;
    }

    public static void setActivityInstance(Activity activity) {
        activityInstance = activity;
    }

    public interface ModelLoadedListener {
        void onModelLoaded();
    }

    public static void setModelLoadedListener(ModelLoadedListener listener) {
        modelLoadedListener = listener;
    }

    /**
     * 模型在 GL 线程加载成功后由原生调用。回调切回界面线程。
     */
    public static void onModelLoaded() {
        final ModelLoadedListener listener = modelLoadedListener;
        final Activity activity = activityInstance;
        if (listener == null || activity == null) {
            return;
        }
        activity.runOnUiThread(listener::onModelLoaded);
    }

    public static String[] getAssetList(String dirPath) {
        try {
            return context.getAssets().list(dirPath);
        } catch (IOException e) {
            e.printStackTrace();
            return new String[0];
        }
    }

    public static byte[] loadFile(String filePath) {
        InputStream fileData = null;
        try {
            fileData = context.getAssets().open(filePath);
            int fileSize = fileData.available();
            byte[] fileBuffer = new byte[fileSize];
            fileData.read(fileBuffer, 0, fileSize);
            return fileBuffer;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            try {
                if (fileData != null) {
                    fileData.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static String getJniLibPath() {
        return context.getApplicationInfo().nativeLibraryDir;
    }

    public static void moveTaskToBack() {
        activityInstance.moveTaskToBack(true);
    }

    private static final String LIBRARY_NAME = "Demo";
    private static Activity activityInstance;
    private static Context context;
    private static volatile ModelLoadedListener modelLoadedListener;

    static {
        System.loadLibrary(LIBRARY_NAME);
    }
}
