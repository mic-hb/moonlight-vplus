package com.limelight.binding.audio

import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.assertEquals
import org.junit.Test

class MutedAudioRendererTest {

    private class Recorder : AudioRenderer {
        val calls = mutableListOf<String>()
        override fun setup(
            audioConfiguration: MoonBridge.AudioConfiguration,
            sampleRate: Int,
            samplesPerFrame: Int,
            codec: Int,
            bitrate: Int,
        ): Int {
            calls.add("setup")
            return 0
        }
        override fun start() { calls.add("start") }
        override fun stop() { calls.add("stop") }
        override fun playDecodedAudio(audioData: ShortArray) { calls.add("decoded") }
        override fun playEncodedAudio(audioData: ByteArray, length: Int) { calls.add("encoded") }
        override fun cleanup() { calls.add("cleanup") }
    }

    @Test
    fun audioDataIsDroppedAndLifecycleIsForwarded() {
        val inner = Recorder()
        val muted = MutedAudioRenderer(inner)
        muted.start()
        muted.playDecodedAudio(ShortArray(480))
        muted.playEncodedAudio(ByteArray(64), 64)
        muted.stop()
        muted.cleanup()
        assertEquals(listOf("start", "stop", "cleanup"), inner.calls)
    }
}
