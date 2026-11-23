package app.nocturne.player.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import app.nocturne.player.data.model.AudioEngineTelemetry
import app.nocturne.player.data.model.UserSettings

class AudioEffectsManager {
    private var currentSessionId: Int = 0
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    fun attachToSession(sessionId: Int, settings: UserSettings): AudioEngineTelemetry {
        if (sessionId == 0) return AudioEngineTelemetry()
        if (sessionId != currentSessionId) {
            release()
            currentSessionId = sessionId
            initEffects(sessionId)
        }
        applySettings(settings)
        return inspectCapabilities(sessionId)
    }

    private fun initEffects(sessionId: Int) {
        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, sessionId) }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
        loudnessEnhancer = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
    }

    fun applySettings(settings: UserSettings) {
        equalizer?.let { eq ->
            runCatching {
                eq.enabled = settings.eqEnabled
                if (settings.eqEnabled) {
                    val bands = eq.numberOfBands.toInt()
                    val presetCount = eq.numberOfPresets.toInt()
                    if (settings.eqPresetIndex in 0 until presetCount) {
                        eq.usePreset(settings.eqPresetIndex.toShort())
                    } else if (settings.eqCustomBandLevelsMb.isNotEmpty()) {
                        val range = eq.bandLevelRange
                        val minLv = range[0].toInt()
                        val maxLv = range[1].toInt()
                        for (b in 0 until bands) {
                            val level = settings.eqCustomBandLevelsMb.getOrElse(b) { 0 }.coerceIn(minLv, maxLv)
                            eq.setBandLevel(b.toShort(), level.toShort())
                        }
                    }
                }
            }
        }

        bassBoost?.let { bb ->
            runCatching {
                val active = settings.eqEnabled && settings.bassBoostStrength > 0 && bb.strengthSupported
                bb.enabled = active
                if (active) {
                    bb.setStrength(settings.bassBoostStrength.coerceIn(0, 1000).toShort())
                }
            }
        }

        virtualizer?.let { virt ->
            runCatching {
                val active = settings.eqEnabled && settings.virtualizerStrength > 0 && virt.strengthSupported
                virt.enabled = active
                if (active) {
                    virt.setStrength(settings.virtualizerStrength.coerceIn(0, 1000).toShort())
                }
            }
        }

        loudnessEnhancer?.let { le ->
            runCatching {
                val active = settings.eqEnabled && settings.loudnessGainMb > 0
                le.enabled = active
                if (active) {
                    le.setTargetGain(settings.loudnessGainMb.coerceIn(0, 1500))
                }
            }
        }
    }

    fun inspectCapabilities(sessionId: Int): AudioEngineTelemetry {
        val eq = equalizer
        val bands = runCatching { eq?.numberOfBands?.toInt() ?: 0 }.getOrDefault(0)
        val range = runCatching { eq?.bandLevelRange }.getOrNull()
        val centers = (0 until bands).map { b ->
            runCatching { (eq?.getCenterFreq(b.toShort()) ?: 0) / 1000 }.getOrDefault(0)
        }
        val presetCount = runCatching { eq?.numberOfPresets?.toInt() ?: 0 }.getOrDefault(0)
        val presets = (0 until presetCount).map { p ->
            runCatching { eq?.getPresetName(p.toShort()) ?: "Preset ${p + 1}" }.getOrDefault("Preset ${p + 1}")
        }

        return AudioEngineTelemetry(
            audioSessionId = sessionId,
            equalizerSupported = eq != null && bands > 0,
            bassBoostSupported = runCatching { bassBoost?.strengthSupported == true }.getOrDefault(false),
            virtualizerSupported = runCatching { virtualizer?.strengthSupported == true }.getOrDefault(false),
            loudnessSupported = loudnessEnhancer != null,
            bandCount = bands,
            bandCenterHz = centers,
            minBandLevelMb = range?.getOrNull(0)?.toInt() ?: -1500,
            maxBandLevelMb = range?.getOrNull(1)?.toInt() ?: 1500,
            presetNames = presets
        )
    }

    fun getPresetBandLevels(presetIndex: Int): List<Int> {
        val eq = equalizer ?: return emptyList()
        return runCatching {
            eq.usePreset(presetIndex.toShort())
            val bands = eq.numberOfBands.toInt()
            (0 until bands).map { eq.getBandLevel(it.toShort()).toInt() }
        }.getOrDefault(emptyList())
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudnessEnhancer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        currentSessionId = 0
    }
}
