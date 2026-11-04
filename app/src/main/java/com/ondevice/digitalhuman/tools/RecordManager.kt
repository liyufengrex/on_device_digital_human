package com.ondevice.digitalhuman.tools

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * 麦克风录音。
 *
 * [startRecording] 开始采集，[stopRecording] 结束并返回音频。
 * 返回值是 48000 Hz、16 bit、单声道、带 RIFF 头的线性 PCM WAV，可直接交给 [com.ondevice.live2d.Live2DController.playAudio]。
 * 调用前需要已获得 [android.Manifest.permission.RECORD_AUDIO]。
 */
class RecordManager {
    private val lock = Any()
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var pcm: ByteArrayOutputStream? = null
    private var inputSampleRate = SAMPLE_RATE

    @Volatile
    private var recording = false

    /**
     * 开始录音。已经在录时直接返回 true。设备打不开麦克风时返回 false。
     */
    fun startRecording(): Boolean {
        synchronized(lock) {
            if (recording) {
                return true
            }
            val opened = openRecorder() ?: return false
            try {
                opened.recorder.startRecording()
            } catch (_: IllegalStateException) {
                opened.recorder.release()
                return false
            } catch (_: SecurityException) {
                opened.recorder.release()
                return false
            }
            if (opened.recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                opened.recorder.release()
                return false
            }
            val output = ByteArrayOutputStream()
            recording = true
            audioRecord = opened.recorder
            pcm = output
            inputSampleRate = opened.sampleRate
            worker = thread(name = "RecordManager", isDaemon = true) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                readLoop(opened.recorder, output, opened.readSize)
            }
            return true
        }
    }

    /**
     * 结束录音。没在录音，或没有采到采样时返回 null。
     */
    fun stopRecording(): ByteArray? {
        val session = synchronized(lock) {
            if (!recording) {
                return null
            }
            recording = false
            val current = audioRecord
            val currentWorker = worker
            val output = pcm
            val rate = inputSampleRate
            audioRecord = null
            worker = null
            pcm = null
            if (current == null || output == null) {
                current?.release()
                return null
            }
            Session(current, currentWorker, output, rate)
        }
        try {
            session.recorder.stop()
        } catch (_: IllegalStateException) {
            // startRecording 未进入录音态时，stop 会抛异常。
        }
        try {
            session.worker?.join(JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        session.recorder.release()
        val captured = session.output.toByteArray()
        if (captured.size < 2) {
            return null
        }
        val aligned = if (captured.size % 2 == 0) captured else captured.copyOf(captured.size - 1)
        val pcm48k = if (session.sampleRate == SAMPLE_RATE) {
            aligned
        } else {
            resamplePcm16(aligned, session.sampleRate, SAMPLE_RATE)
        }
        if (pcm48k.size < 2) {
            return null
        }
        return pcm48k.toWav(SAMPLE_RATE)
    }

    private fun readLoop(recorder: AudioRecord, output: ByteArrayOutputStream, readSize: Int) {
        val buffer = ByteArray(readSize)
        while (recording) {
            val read = try {
                recorder.read(buffer, 0, buffer.size)
            } catch (_: Exception) {
                break
            }
            if (read > 0) {
                output.write(buffer, 0, read)
            } else if (read < 0) {
                break
            }
        }
    }

    private data class Session(
        val recorder: AudioRecord,
        val worker: Thread?,
        val output: ByteArrayOutputStream,
        val sampleRate: Int,
    )

    private data class OpenedRecorder(
        val recorder: AudioRecord,
        val sampleRate: Int,
        val readSize: Int,
    )

    private fun openRecorder(): OpenedRecorder? {
        for (rate in intArrayOf(SAMPLE_RATE, 44_100, 16_000)) {
            val minBuffer = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                continue
            }
            val recorder = try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build()
            } catch (_: IllegalArgumentException) {
                continue
            } catch (_: SecurityException) {
                return null
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                continue
            }
            return OpenedRecorder(recorder, rate, minBuffer)
        }
        return null
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val JOIN_TIMEOUT_MS = 1_000L
    }
}

private fun resamplePcm16(pcm: ByteArray, fromRate: Int, toRate: Int): ByteArray {
    val sampleCount = pcm.size / 2
    if (sampleCount == 0 || fromRate == toRate) {
        return pcm
    }
    val outCount = (sampleCount.toLong() * toRate / fromRate).toInt()
    if (outCount <= 0) {
        return ByteArray(0)
    }
    val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
    val samples = ShortArray(sampleCount) { input.short }
    val output = ByteBuffer.allocate(outCount * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (index in 0 until outCount) {
        val position = index.toDouble() * fromRate / toRate
        val left = position.toInt().coerceIn(0, sampleCount - 1)
        val right = (left + 1).coerceAtMost(sampleCount - 1)
        val fraction = (position - left).toFloat()
        val mixed = samples[left] * (1f - fraction) + samples[right] * fraction
        output.putShort(mixed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
    }
    return output.array()
}

private fun ByteArray.toWav(sampleRate: Int): ByteArray {
    val channels = 1
    val bitsPerSample = 16
    val blockAlign = channels * bitsPerSample / 8
    val dataSize = size - size % blockAlign
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36 + dataSize)
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1)
        putShort(channels.toShort())
        putInt(sampleRate)
        putInt(sampleRate * blockAlign)
        putShort(blockAlign.toShort())
        putShort(bitsPerSample.toShort())
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(dataSize)
    }
    val wav = ByteArray(44 + dataSize)
    System.arraycopy(header.array(), 0, wav, 0, 44)
    System.arraycopy(this, 0, wav, 44, dataSize)
    return wav
}
