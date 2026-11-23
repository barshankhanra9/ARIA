package app.nocturne.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.tanh

@OptIn(UnstableApi::class)
class BalanceAndReplayGainAudioProcessor(
    private val onFormatObserved: (sampleRateHz: Int, channelCount: Int, encodingName: String) -> Unit
) : BaseAudioProcessor() {

    @Volatile
    var channelBalance: Float = 0f // -1.0 (Left) to +1.0 (Right)

    @Volatile
    var replayGainDb: Float = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encodingName = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> "PCM 16-bit"
            C.ENCODING_PCM_FLOAT -> "PCM 32-bit Float"
            C.ENCODING_PCM_24BIT -> "PCM 24-bit"
            C.ENCODING_PCM_32BIT -> "PCM 32-bit"
            else -> "PCM"
        }
        onFormatObserved(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, encodingName)

        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        return if (supported) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val bal = channelBalance.coerceIn(-1f, 1f)
        val rgLinear = if (replayGainDb == 0f) 1f else 10.0.pow((replayGainDb / 20.0)).toFloat().coerceIn(0.05f, 4.0f)

        // Passthrough when unity gain and centered balance
        if (bal == 0f && rgLinear == 1f) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }

        val leftGain = (if (bal > 0f) 1f - bal else 1f) * rgLinear
        val rightGain = (if (bal < 0f) 1f + bal else 1f) * rgLinear
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)

        val out = replaceOutputBuffer(remaining)

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                var frameChannel = 0
                while (inputBuffer.remaining() >= 2) {
                    val sample = inputBuffer.short.toFloat() / 32768f
                    val gain = when {
                        channels == 1 -> rgLinear
                        frameChannel == 0 -> leftGain
                        frameChannel == 1 -> rightGain
                        else -> rgLinear
                    }
                    val scaled = softLimit(sample * gain)
                    out.putShort((scaled * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                    frameChannel = (frameChannel + 1) % channels
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                var frameChannel = 0
                while (inputBuffer.remaining() >= 4) {
                    val sample = inputBuffer.float
                    val gain = when {
                        channels == 1 -> rgLinear
                        frameChannel == 0 -> leftGain
                        frameChannel == 1 -> rightGain
                        else -> rgLinear
                    }
                    out.putFloat(softLimit(sample * gain))
                    frameChannel = (frameChannel + 1) % channels
                }
            }
            else -> {
                out.put(inputBuffer)
            }
        }
        out.flip()
    }

    private fun softLimit(value: Float): Float {
        return if (value > 0.95f || value < -0.95f) tanh(value.toDouble()).toFloat() else value
    }
}
