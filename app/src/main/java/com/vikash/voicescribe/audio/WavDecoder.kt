package com.vikash.voicescribe.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a PCM16 WAV file to mono float samples at 16 kHz (whisper's expected input).
 * Handles multi-channel by averaging and other sample rates by linear resampling.
 */
fun decodeWavToMono16k(file: File): FloatArray {
    val bytes = file.readBytes()
    require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") {
        "Not a WAV file"
    }
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    var channels = 1
    var sampleRate = SAMPLE_RATE
    var bitsPerSample = 16
    var dataOffset = -1
    var dataLen = 0

    var pos = 12
    while (pos + 8 <= bytes.size) {
        val chunkId = String(bytes, pos, 4)
        val chunkSize = buf.getInt(pos + 4)
        when (chunkId) {
            "fmt " -> {
                channels = buf.getShort(pos + 10).toInt()
                sampleRate = buf.getInt(pos + 12)
                bitsPerSample = buf.getShort(pos + 22).toInt()
            }
            "data" -> {
                dataOffset = pos + 8
                dataLen = minOf(chunkSize, bytes.size - dataOffset)
            }
        }
        pos += 8 + chunkSize + (chunkSize and 1)
        if (dataOffset >= 0 && chunkId == "data") break
    }
    require(dataOffset >= 0) { "WAV has no data chunk" }
    require(bitsPerSample == 16) { "Only PCM16 WAV supported" }

    val frameCount = dataLen / (2 * channels)
    val mono = FloatArray(frameCount)
    for (i in 0 until frameCount) {
        var sum = 0f
        for (c in 0 until channels) {
            sum += buf.getShort(dataOffset + (i * channels + c) * 2) / 32768f
        }
        mono[i] = (sum / channels).coerceIn(-1f, 1f)
    }

    if (sampleRate == SAMPLE_RATE) return mono

    // Linear resample to 16 kHz
    val outCount = (frameCount.toLong() * SAMPLE_RATE / sampleRate).toInt()
    val out = FloatArray(outCount)
    val ratio = sampleRate.toDouble() / SAMPLE_RATE
    for (i in 0 until outCount) {
        val src = i * ratio
        val i0 = src.toInt().coerceAtMost(frameCount - 1)
        val i1 = (i0 + 1).coerceAtMost(frameCount - 1)
        val frac = (src - i0).toFloat()
        out[i] = mono[i0] * (1 - frac) + mono[i1] * frac
    }
    return out
}
