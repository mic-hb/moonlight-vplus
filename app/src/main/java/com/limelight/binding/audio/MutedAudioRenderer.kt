package com.limelight.binding.audio

import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge

/**
 * Keeps the stream's audio off this device. Everything is forwarded to [inner] except the audio
 * data itself, so setup, start, stop and cleanup behave as usual and only the sound is dropped.
 * Used when "Play audio on this device" is off (owner decision 2026-11-08).
 */
class MutedAudioRenderer(private val inner: AudioRenderer) : AudioRenderer {
    override fun setup(
        audioConfiguration: MoonBridge.AudioConfiguration,
        sampleRate: Int,
        samplesPerFrame: Int,
        codec: Int,
        bitrate: Int,
    ): Int = inner.setup(audioConfiguration, sampleRate, samplesPerFrame, codec, bitrate)

    override fun start() = inner.start()

    override fun stop() = inner.stop()

    override fun playDecodedAudio(audioData: ShortArray) {
        // Dropped on purpose.
    }

    override fun playEncodedAudio(audioData: ByteArray, length: Int) {
        // Dropped on purpose.
    }

    override fun cleanup() = inner.cleanup()
}
