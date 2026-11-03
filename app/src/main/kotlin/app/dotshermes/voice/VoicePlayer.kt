package app.dotshermes.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Ce que joue le lecteur : un seul vocal à la fois, comme dans une messagerie. */
data class Playback(
    val messageId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Téléchargement de l'audio en cours (vocal rejoué depuis l'historique du gateway). */
    val loading: Boolean = false,
) {
    fun progressOf(id: String): Float =
        if (id == messageId && durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

class VoicePlayer(private val scope: CoroutineScope) {
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private val _state = MutableStateFlow(Playback())
    val state: StateFlow<Playback> = _state.asStateFlow()

    fun markLoading(messageId: String) {
        stop()
        _state.value = Playback(messageId = messageId, loading = true)
    }

    /** Lecture, pause ou reprise du vocal [messageId] ; un autre vocal en cours est arrêté. */
    fun toggle(messageId: String, file: File) {
        val current = player
        if (current != null && _state.value.messageId == messageId) {
            if (current.isPlaying) pause() else resume()
            return
        }
        stop()
        val media = MediaPlayer()
        try {
            media.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            media.setDataSource(file.path)
            media.prepare()
        } catch (e: Exception) {
            media.release()
            _state.value = Playback()
            return
        }
        media.setOnCompletionListener { stop() }
        player = media
        _state.value = Playback(messageId, playing = true, durationMs = media.duration.toLong())
        media.start()
        startTicker()
    }

    /** Toucher l'onde déplace la lecture du vocal en cours. */
    fun seek(messageId: String, fraction: Float) {
        val media = player ?: return
        if (_state.value.messageId != messageId) return
        val target = (fraction.coerceIn(0f, 1f) * media.duration).toInt()
        media.seekTo(target)
        _state.value = _state.value.copy(positionMs = target.toLong())
    }

    fun stop() {
        ticker?.cancel()
        player?.release()
        player = null
        _state.value = Playback()
    }

    private fun pause() {
        player?.pause()
        ticker?.cancel()
        _state.value = _state.value.copy(playing = false)
    }

    private fun resume() {
        player?.start()
        _state.value = _state.value.copy(playing = true)
        startTicker()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                player?.let { _state.value = _state.value.copy(positionMs = it.currentPosition.toLong()) }
                delay(50)
            }
        }
    }
}
