package com.vikash.voicescribe.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

const val SAMPLE_RATE = 16_000

/** Records mono 16 kHz PCM16 straight into a WAV file — whisper's native input format. */
class WavRecorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var dataBytes = 0L

    val durationMs: Long get() = dataBytes * 1000 / (SAMPLE_RATE * 2)

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO before starting the service
    fun start(file: File, onAmplitude: (Float) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, 64 * 1024)
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        record = rec
        running = true
        dataBytes = 0

        thread = Thread {
            val shorts = ShortArray(2048)
            val bytes = ByteArray(shorts.size * 2)
            BufferedOutputStream(FileOutputStream(file)).use { out ->
                out.write(ByteArray(44)) // header placeholder, patched on stop
                rec.startRecording()
                while (running) {
                    val n = rec.read(shorts, 0, shorts.size)
                    if (n <= 0) continue
                    var peak = 0
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) {
                        bb.putShort(shorts[i])
                        peak = maxOf(peak, abs(shorts[i].toInt()))
                    }
                    out.write(bytes, 0, n * 2)
                    dataBytes += n * 2L
                    onAmplitude(peak / 32768f)
                }
                rec.stop()
            }
            patchHeader(file, dataBytes)
        }.apply { start() }
    }

    /** Stops recording, finalizes the WAV header, returns duration in ms. */
    fun stop(): Long {
        running = false
        thread?.join()
        thread = null
        record?.release()
        record = null
        return durationMs
    }

    private fun patchHeader(file: File, dataLen: Long) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt((36 + dataLen).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)                       // fmt chunk size
            putShort(1)                      // PCM
            putShort(1)                      // mono
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2)          // byte rate
            putShort(2)                      // block align
            putShort(16)                     // bits per sample
            put("data".toByteArray())
            putInt(dataLen.toInt())
        }
        RandomAccessFile(file, "rw").use {
            it.seek(0)
            it.write(header.array())
        }
    }
}
