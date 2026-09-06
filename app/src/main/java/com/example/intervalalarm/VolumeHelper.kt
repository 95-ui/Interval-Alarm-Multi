package com.example.intervalalarm

import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log

/**
 * Wendet eine Lautstärke von 0–200 % auf einen MediaPlayer an.
 *
 * - Werte bis 100 % laufen über die normale Lautstärke-Regelung
 *   (MediaPlayer.setVolume, 0.0–1.0).
 * - Werte über 100 % setzen die normale Lautstärke auf Maximum (1.0) und
 *   verstärken zusätzlich mit einem LoudnessEnhancer-Effekt – nützlich für
 *   leise aufgenommene MP3s. 200 % entspricht ca. +20 dB Verstärkung.
 *
 * Gibt den LoudnessEnhancer zurück (oder null, wenn keiner nötig/verfügbar
 * war), damit er später mit release() wieder freigegeben werden kann.
 */
object VolumeHelper {
    private const val TAG = "VolumeHelper"

    fun apply(player: MediaPlayer, volumePercent: Int): LoudnessEnhancer? {
        val clamped = volumePercent.coerceIn(0, 200)

        if (clamped <= 100) {
            val v = clamped / 100f
            player.setVolume(v, v)
            return null
        }

        // Über 100%: normale Lautstärke auf Maximum, Rest über Verstärkung
        player.setVolume(1f, 1f)
        return try {
            val enhancer = LoudnessEnhancer(player.audioSessionId)
            val extraPercent = clamped - 100 // 0..100
            val gainMillibels = (extraPercent * 20).coerceAtMost(2000) // bis zu +20 dB
            enhancer.setTargetGain(gainMillibels)
            enhancer.enabled = true
            enhancer
        } catch (e: Exception) {
            Log.e(TAG, "LoudnessEnhancer auf diesem Gerät nicht verfügbar: ${e.message}")
            null
        }
    }
}
