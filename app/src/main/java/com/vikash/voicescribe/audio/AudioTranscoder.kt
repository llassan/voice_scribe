package com.vikash.voicescribe.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

private const val AAC_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
private const val TIMEOUT_US = 10_000L

/**
 * WAV (PCM16) → M4A/AAC via MediaCodec + MediaMuxer. Streams the PCM from disk,
 * so lecture-length files don't need to fit in memory. ~10× smaller than WAV;
 * the M4A stays re-transcribable through [decodeCompressedToMono16k].
 */
fun transcodeWavToM4a(src: File, dst: File, bitRate: Int = 32_000) {
    val info = parseWavHeader(src)
    require(info.bitsPerSample == 16) { "Only PCM16 WAV supported" }
    val bytesPerSec = info.sampleRate * info.channels * 2L

    val format = MediaFormat.createAudioFormat(AAC_MIME, info.sampleRate, info.channels).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
    }
    val codec = MediaCodec.createEncoderByType(AAC_MIME)
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    codec.start()
    val muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    var track = -1
    var muxerStarted = false
    val outInfo = MediaCodec.BufferInfo()

    try {
        FileInputStream(src).use { ins ->
            var skipped = 0L
            while (skipped < info.dataOffset) skipped += ins.skip(info.dataOffset - skipped)

            var remaining = info.dataLen
            var fedBytes = 0L
            var inputDone = false
            val chunk = ByteArray(16 * 1024)

            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val ptsUs = fedBytes * 1_000_000L / bytesPerSec
                        val buf = codec.getInputBuffer(inIdx)!!
                        val toRead = minOf(remaining, minOf(buf.capacity(), chunk.size).toLong()).toInt()
                        val n = if (toRead > 0) ins.read(chunk, 0, toRead) else -1
                        if (n <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.clear()
                            buf.put(chunk, 0, n)
                            codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                            fedBytes += n
                            remaining -= n
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(outInfo, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIdx >= 0 -> {
                        if (outInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) outInfo.size = 0
                        if (outInfo.size > 0 && muxerStarted) {
                            muxer.writeSampleData(track, codec.getOutputBuffer(outIdx)!!, outInfo)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (outInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    } finally {
        runCatching { if (muxerStarted) muxer.stop() }
        muxer.release()
        runCatching { codec.stop() }
        codec.release()
    }
}

/** M4A/AAC (or any framework-decodable audio) → mono 16 kHz floats for whisper. */
fun decodeCompressedToMono16k(file: File): FloatArray {
    val extractor = MediaExtractor()
    extractor.setDataSource(file.absolutePath)
    var trackIdx = -1
    var trackFormat: MediaFormat? = null
    for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
            trackIdx = i
            trackFormat = f
            break
        }
    }
    require(trackIdx >= 0 && trackFormat != null) { "No audio track in ${file.name}" }
    extractor.selectTrack(trackIdx)

    var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

    val codec = MediaCodec.createDecoderByType(trackFormat.getString(MediaFormat.KEY_MIME)!!)
    codec.configure(trackFormat, null, null, 0)
    codec.start()

    val pcm = ByteArrayOutputStream()
    val outInfo = MediaCodec.BufferInfo()
    var inputDone = false

    try {
        while (true) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(outInfo, TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                outIdx >= 0 -> {
                    if (outInfo.size > 0) {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        val bytes = ByteArray(outInfo.size)
                        buf.position(outInfo.offset)
                        buf.get(bytes)
                        pcm.write(bytes)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (outInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    } finally {
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
    }

    val bytes = pcm.toByteArray()
    val mono = pcm16ToMonoFloats(bytes, bytes.size - bytes.size % (2 * channels), channels)
    return resampleTo16k(mono, sampleRate)
}
