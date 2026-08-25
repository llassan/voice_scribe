package com.vikash.voicescribe.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavInfo(
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataLen: Long,
)

/** Parses RIFF chunks from the header region only — safe for long recordings. */
fun parseWavHeader(file: File): WavInfo {
    val head = ByteArray(minOf(file.length(), 64L * 1024).toInt())
    file.inputStream().use { it.read(head) }
    require(head.size > 44 && String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE") {
        "Not a WAV file"
    }
    val buf = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)

    var channels = 1
    var sampleRate = SAMPLE_RATE
    var bitsPerSample = 16
    var pos = 12
    while (pos + 8 <= head.size) {
        val chunkId = String(head, pos, 4)
        val chunkSize = buf.getInt(pos + 4)
        when (chunkId) {
            "fmt " -> {
                channels = buf.getShort(pos + 10).toInt()
                sampleRate = buf.getInt(pos + 12)
                bitsPerSample = buf.getShort(pos + 22).toInt()
            }
            "data" -> {
                val offset = pos + 8L
                val len = if (chunkSize <= 0) file.length() - offset
                else minOf(chunkSize.toLong(), file.length() - offset)
                return WavInfo(channels, sampleRate, bitsPerSample, offset, len)
            }
        }
        pos += 8 + chunkSize + (chunkSize and 1)
    }
    throw IllegalArgumentException("WAV has no data chunk")
}

/** Interleaved PCM16 little-endian bytes → mono floats in [-1, 1]. */
fun pcm16ToMonoFloats(bytes: ByteArray, len: Int, channels: Int): FloatArray {
    val buf = ByteBuffer.wrap(bytes, 0, len).order(ByteOrder.LITTLE_ENDIAN)
    val frames = len / (2 * channels)
    val mono = FloatArray(frames)
    for (i in 0 until frames) {
        var sum = 0f
        for (c in 0 until channels) sum += buf.getShort((i * channels + c) * 2) / 32768f
        mono[i] = (sum / channels).coerceIn(-1f, 1f)
    }
    return mono
}

/** Linear resample to whisper's 16 kHz. */
fun resampleTo16k(input: FloatArray, fromRate: Int): FloatArray {
    if (fromRate == SAMPLE_RATE || input.isEmpty()) return input
    val outCount = (input.size.toLong() * SAMPLE_RATE / fromRate).toInt()
    val out = FloatArray(outCount)
    val ratio = fromRate.toDouble() / SAMPLE_RATE
    for (i in 0 until outCount) {
        val src = i * ratio
        val i0 = src.toInt().coerceAtMost(input.size - 1)
        val i1 = (i0 + 1).coerceAtMost(input.size - 1)
        val frac = (src - i0).toFloat()
        out[i] = input[i0] * (1 - frac) + input[i1] * frac
    }
    return out
}

/** Decodes WAV (PCM16) or compressed audio (M4A/AAC etc.) to mono 16 kHz floats. */
fun decodeAudioToMono16k(file: File): FloatArray =
    if (file.extension.lowercase() == "wav") decodeWavToMono16k(file)
    else decodeCompressedToMono16k(file)

fun decodeWavToMono16k(file: File): FloatArray {
    val info = parseWavHeader(file)
    require(info.bitsPerSample == 16) { "Only PCM16 WAV supported" }
    val data = ByteArray(info.dataLen.toInt())
    file.inputStream().use { ins ->
        var skipped = 0L
        while (skipped < info.dataOffset) skipped += ins.skip(info.dataOffset - skipped)
        var read = 0
        while (read < data.size) {
            val n = ins.read(data, read, data.size - read)
            if (n < 0) break
            read += n
        }
    }
    val mono = pcm16ToMonoFloats(data, data.size, info.channels)
    return resampleTo16k(mono, info.sampleRate)
}
