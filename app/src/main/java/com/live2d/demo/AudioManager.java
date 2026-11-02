/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

package com.live2d.demo;

import java.util.HashMap;
import java.util.Map;

public class AudioManager {
    public static int createAudioOutput(int sampleRate, int channels, int bitDepth) {
        int key = createAudioOutputKey();
        if (key == 0) {
            return 0;
        }

        AudioOutput audioOutput = AudioOutput.create(sampleRate, channels, bitDepth);
        if (audioOutput == null) {
            return 0;
        }
        audioOutputMap.put(key, audioOutput);
        return key;
    }

    public static boolean writeAudioOutput(int id, int writePos, byte[] data, int dataSize) {
        if (!audioOutputMap.containsKey(id)) {
            return false;
        }
        audioOutputMap.get(id).writeBuffer(writePos, data, dataSize);
        return true;
    }

    public static int getAudioOutputPosition(int id) {
        if (!audioOutputMap.containsKey(id)) {
            return -1;
        }
        return audioOutputMap.get(id).getPlayPosition();
    }

    public static boolean stopAudioOutput(int id) {
        if (!audioOutputMap.containsKey(id)) {
            return false;
        }
        audioOutputMap.get(id).stop();
        return true;
    }

    public static boolean deleteAudioOutput(int id) {
        if (!audioOutputMap.containsKey(id)) {
            return false;
        }
        audioOutputMap.get(id).delete();
        audioOutputMap.remove(id);
        return true;
    }

    private static int createAudioOutputKey() {
        int count = 0;
        do {
            if (KEY_RANGE == count) {
                return 0;
            }
            count++;
            if (keyOutputIndex == KEY_RANGE) {
                keyOutputIndex = 0;
            }
            keyOutputIndex++;
        } while (audioOutputMap.containsKey(keyOutputIndex));
        return keyOutputIndex;
    }

    private static final int KEY_RANGE = Integer.MAX_VALUE;
    private static final Map<Integer, AudioOutput> audioOutputMap = new HashMap<>();
    private static int keyOutputIndex = 0;
}
