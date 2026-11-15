package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.data.GameSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

interface GameAudioController {
    fun applySettings(settings: GameSettings)
    fun onForeground()
    fun onBackground()
    fun playTap(comboCount: Int)
    fun playCrit()
    fun playUpgrade()
    fun playError()
    fun playOverdrive()
    fun playAchievement()
    fun playRestart()
    fun playDiamond()
    fun playJackpot()
    fun playSpinTick()
    fun playBossHit()
    fun release()
}

class NoOpAudioController : GameAudioController {
    override fun applySettings(settings: GameSettings) {}
    override fun onForeground() {}
    override fun onBackground() {}
    override fun playTap(comboCount: Int) {}
    override fun playCrit() {}
    override fun playUpgrade() {}
    override fun playError() {}
    override fun playOverdrive() {}
    override fun playAchievement() {}
    override fun playRestart() {}
    override fun playDiamond() {}
    override fun playJackpot() {}
    override fun playSpinTick() {}
    override fun playBossHit() {}
    override fun release() {}
}

class GameAudioManager(initialSettings: GameSettings) : GameAudioController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var currentSettings: GameSettings = initialSettings

    @Volatile
    private var isInForeground: Boolean = false

    private var bgmJob: Job? = null
    private var bgmTrack: AudioTrack? = null

    private val tapClips: List<ShortArray> by lazy {
        // Pentatonic scale frequencies rising with combo
        val freqs = listOf(523.25, 587.33, 659.25, 783.99, 880.00, 1046.50, 1174.66, 1318.51)
        freqs.map { f -> synthesizeBlip(freqHz = f, durationMs = 68, decayRate = 28.0) }
    }
    private val critClip: ShortArray by lazy { synthesizeCrit() }
    private val upgradeClip: ShortArray by lazy { synthesizeArpeggio(listOf(523.25, 659.25, 783.99, 1046.50), noteMs = 55) }
    private val errorClip: ShortArray by lazy { synthesizeArpeggio(listOf(220.0, 174.61), noteMs = 80) }
    private val overdriveClip: ShortArray by lazy { synthesizeSweep(startHz = 260.0, endHz = 1120.0, durationMs = 340) }
    private val achievementClip: ShortArray by lazy { synthesizeArpeggio(listOf(659.25, 783.99, 987.77, 1318.51), noteMs = 70) }
    private val restartClip: ShortArray by lazy { synthesizeArpeggio(listOf(392.0, 493.88, 587.33, 783.99, 1174.66), noteMs = 85) }
    private val diamondClip: ShortArray by lazy { synthesizeArpeggio(listOf(1046.50, 1318.51, 1567.98, 2093.00), noteMs = 60) }
    private val jackpotClip: ShortArray by lazy { synthesizeArpeggio(listOf(523.25, 659.25, 783.99, 1046.50, 1318.51, 1567.98, 2093.00), noteMs = 68) }
    private val spinTickClip: ShortArray by lazy { synthesizeBlip(freqHz = 920.0, durationMs = 34, decayRate = 48.0) }
    private val bossHitClip: ShortArray by lazy { synthesizeSweep(startHz = 180.0, endHz = 65.0, durationMs = 110) }
    private val bgmLoop: ShortArray by lazy { synthesizeAmbientLoop() }

    override fun applySettings(settings: GameSettings) {
        val prev = currentSettings
        currentSettings = settings
        try {
            bgmTrack?.setVolume(settings.bgmVolume.coerceIn(0f, 1f))
        } catch (_: Throwable) {
        }
        if (settings.bgmEnabled && isInForeground && (!prev.bgmEnabled || bgmJob == null)) {
            startBgm()
        } else if (!settings.bgmEnabled) {
            stopBgm()
        }
    }

    override fun onForeground() {
        isInForeground = true
        if (currentSettings.bgmEnabled) {
            startBgm()
        }
    }

    override fun onBackground() {
        isInForeground = false
        stopBgm()
    }

    override fun playTap(comboCount: Int) {
        if (!currentSettings.sfxEnabled || currentSettings.sfxVolume <= 0.01f) return
        val index = (comboCount - 1).coerceAtLeast(0) % tapClips.size
        playClipAsync(tapClips[index], currentSettings.sfxVolume * 0.65f)
    }

    override fun playCrit() {
        if (!currentSettings.sfxEnabled || currentSettings.sfxVolume <= 0.01f) return
        playClipAsync(critClip, currentSettings.sfxVolume * 0.85f)
    }

    override fun playUpgrade() {
        if (!currentSettings.sfxEnabled || currentSettings.sfxVolume <= 0.01f) return
        playClipAsync(upgradeClip, currentSettings.sfxVolume * 0.75f)
    }

    override fun playError() {
        if (!currentSettings.sfxEnabled || currentSettings.sfxVolume <= 0.01f) return
        playClipAsync(errorClip, currentSettings.sfxVolume * 0.65f)
    }

    override fun playOverdrive() {
        if (!currentSettings.sfxEnabled || currentSettings.sfxVolume <= 0.01f) return
        playClipAsync(overdriveClip, currentSettings.sfxVolume * 0.85f)
    }

    override fun playAchievement() {
        if (!currentSettings.sfxEnabled || currentSettings.sfxVolume <= 0.01f) return
        playClipAsync(achievementClip, currentSettings.sfxVolume * 0.8f)
    }

    override fun playRestart() {
        if (!currentSettings.sfxEnabled || currentSettings.sfxVolume <= 0.01f) return
        playClipAsync(restartClip, currentSettings.sfxVolume * 0.85f)
    }

    override fun playDiamond() {
        if (!currentSettings.sfxEnabled || currentSettings.sfxVolume <= 0.01f) return
        playClipAsync(diamondClip, currentSettings.sfxVolume * 0.9f)
    }

    override fun playJackpot() {
        if (!currentSettings.sfxEnabled || currentSettings.sfxVolume <= 0.01f) return
        playClipAsync(jackpotClip, currentSettings.sfxVolume * 0.95f)
    }

    override fun playSpinTick() {
        if (!currentSettings.sfxEnabled || currentSettings.sfxVolume <= 0.01f) return
        playClipAsync(spinTickClip, currentSettings.sfxVolume * 0.45f)
    }

    override fun playBossHit() {
        if (!currentSettings.sfxEnabled || currentSettings.sfxVolume <= 0.01f) return
        playClipAsync(bossHitClip, currentSettings.sfxVolume * 0.8f)
    }

    private fun startBgm() {
        if (bgmJob?.isActive == true) return
        bgmJob = scope.launch {
            try {
                val loopSamples = bgmLoop
                val byteSize = loopSamples.size * 2
                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf.coerceAtLeast(8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                bgmTrack = track
                track.setVolume(currentSettings.bgmVolume.coerceIn(0f, 1f))
                track.play()

                val chunkSize = 2048
                var offset = 0
                while (isActive && currentSettings.bgmEnabled && isInForeground) {
                    val toWrite = minOf(chunkSize, loopSamples.size - offset)
                    val written = track.write(loopSamples, offset, toWrite, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) break
                    offset = (offset + toWrite) % loopSamples.size
                }

                track.stop()
                track.release()
                if (bgmTrack === track) bgmTrack = null
                if (byteSize < 0) Unit
            } catch (_: Throwable) {
                bgmTrack = null
            }
        }
    }

    private fun stopBgm() {
        bgmJob?.cancel()
        bgmJob = null
        try {
            bgmTrack?.pause()
            bgmTrack?.flush()
            bgmTrack?.release()
        } catch (_: Throwable) {
        }
        bgmTrack = null
    }

    private fun playClipAsync(samples: ShortArray, volume: Float) {
        scope.launch {
            try {
                val byteLen = samples.size * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(byteLen)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(samples, 0, samples.size)
                track.setVolume(volume.coerceIn(0f, 1f))
                track.play()
                val durationMs = (samples.size * 1000L / SAMPLE_RATE) + 40L
                kotlinx.coroutines.delay(durationMs)
                track.stop()
                track.release()
            } catch (_: Throwable) {
            }
        }
    }

    override fun release() {
        stopBgm()
        scope.cancel()
    }

    private fun synthesizeBlip(freqHz: Double, durationMs: Int, decayRate: Double): ShortArray {
        val count = SAMPLE_RATE * durationMs / 1000
        return ShortArray(count) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val env = (1.0 - exp(-t * 300.0)) * exp(-t * decayRate)
            val wave = sin(2.0 * PI * freqHz * t) * 0.75 + sin(2.0 * PI * freqHz * 2.0 * t) * 0.25
            (wave * env * 22000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun synthesizeCrit(): ShortArray {
        val count = SAMPLE_RATE * 220 / 1000
        return ShortArray(count) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val env = (1.0 - exp(-t * 250.0)) * exp(-t * 12.0)
            val chord = (
                sin(2.0 * PI * 1046.50 * t) +
                    sin(2.0 * PI * 1318.51 * t) +
                    sin(2.0 * PI * 1567.98 * t) +
                    sin(2.0 * PI * 2093.00 * t) * 0.6
                ) / 3.6
            (chord * env * 25000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun synthesizeArpeggio(freqs: List<Double>, noteMs: Int): ShortArray {
        val samplesPerNote = SAMPLE_RATE * noteMs / 1000
        val total = samplesPerNote * freqs.size
        return ShortArray(total) { i ->
            val noteIndex = (i / samplesPerNote).coerceAtMost(freqs.lastIndex)
            val localSample = i % samplesPerNote
            val t = localSample.toDouble() / SAMPLE_RATE
            val env = (1.0 - exp(-t * 240.0)) * exp(-t * 18.0)
            val f = freqs[noteIndex]
            val wave = sin(2.0 * PI * f * t) * 0.8 + sin(2.0 * PI * f * 2.0 * t) * 0.2
            (wave * env * 21000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun synthesizeSweep(startHz: Double, endHz: Double, durationMs: Int): ShortArray {
        val count = SAMPLE_RATE * durationMs / 1000
        var phase = 0.0
        return ShortArray(count) { i ->
            val progress = i.toDouble() / count
            val t = i.toDouble() / SAMPLE_RATE
            val freq = startHz + (endHz - startHz) * (progress * progress)
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val env = sin(PI * progress) * (1.0 - exp(-t * 100.0))
            val wave = sin(phase) * 0.7 + sin(phase * 1.5) * 0.3
            (wave * env * 23000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun synthesizeAmbientLoop(): ShortArray {
        // 8-second chill synthwave loop (4 chords x 2s each: Am -> F -> C -> G)
        val totalSeconds = 8.0
        val count = (SAMPLE_RATE * totalSeconds).toInt()
        val roots = doubleArrayOf(110.0, 87.31, 130.81, 98.00) // A2, F2, C3, G2
        val arpNotes = arrayOf(
            doubleArrayOf(220.0, 261.63, 329.63, 440.0), // Am
            doubleArrayOf(174.61, 220.0, 261.63, 349.23), // F
            doubleArrayOf(261.63, 329.63, 392.0, 523.25), // C
            doubleArrayOf(196.0, 246.94, 293.66, 392.0) // G
        )
        val chordDuration = 2.0
        val stepDuration = 0.25 // 8th-note arpeggio at 120 BPM

        return ShortArray(count) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val chordIdx = (t / chordDuration).toInt().coerceIn(0, 3)
            val localChordT = t % chordDuration
            val stepIdx = (localChordT / stepDuration).toInt() % 4
            val localStepT = localChordT % stepDuration

            // Warm sub-bass pad with smooth chord crossfade
            val rootFreq = roots[chordIdx]
            val chordEnv = sin(PI * (localChordT / chordDuration)).coerceAtLeast(0.15)
            val pad = (sin(2.0 * PI * rootFreq * t) * 0.55 +
                sin(2.0 * PI * rootFreq * 1.5 * t) * 0.25) * chordEnv

            // Soft synth arpeggio pluck
            val arpFreq = arpNotes[chordIdx][stepIdx]
            val arpEnv = (1.0 - exp(-localStepT * 120.0)) * exp(-localStepT * 8.5)
            val arp = sin(2.0 * PI * arpFreq * t) * arpEnv * 0.45

            // Gentle soft pulse on beat (every 0.5s)
            val beatT = t % 0.5
            val kickEnv = exp(-beatT * 22.0)
            val kickFreq = 52.0 + 60.0 * exp(-beatT * 35.0)
            val kick = sin(2.0 * PI * kickFreq * beatT) * kickEnv * 0.35

            val mixed = (pad + arp + kick) * 0.48
            (mixed * 16000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 22050
    }
}
