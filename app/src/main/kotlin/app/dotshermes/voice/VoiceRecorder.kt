package app.dotshermes.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

/** Vocal terminé : fichier AAC/MP4, durée et enveloppe réduite à [WAVEFORM_BARS] barres (0..1). */
class RecordedVoice(val file: File, val durationMs: Long, val waveform: List<Float>)

/** État observable pendant l'enregistrement : chrono et niveaux récents pour l'onde en direct. */
data class RecordingState(val elapsedMs: Long = 0, val levels: List<Float> = emptyList())

const val WAVEFORM_BARS = 40
const val MAX_VOICE_MS = 5 * 60_000L
private const val MIN_VOICE_MS = 600L
private const val SAMPLE_EVERY_MS = 60L

/**
 * Enregistrement au micro. AAC mono 16 kHz à 32 kb/s : assez pour la voix et pour Whisper, ~4 Ko/s,
 * soit un vocal d'une minute bien en dessous de la limite de trame du gateway.
 */
class VoiceRecorder(private val context: Context, private val scope: CoroutineScope) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var sampler: Job? = null
    private val samples = ArrayList<Float>()

    private val _state = MutableStateFlow<RecordingState?>(null)
    /** null quand rien n'est en cours. */
    val state: StateFlow<RecordingState?> = _state.asStateFlow()

    fun start(): Boolean {
        if (recorder != null) return true
        val dir = File(context.filesDir, "voice").apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.m4a")
        val media = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            media.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioChannels(1)
            media.setAudioSamplingRate(16_000)
            media.setAudioEncodingBitRate(32_000)
            media.setOutputFile(target.path)
            media.prepare()
            media.start()
            recorder = media
            file = target
            startedAt = SystemClock.elapsedRealtime()
            samples.clear()
            _state.value = RecordingState()
            sampler = scope.launch { sample() }
            true
        } catch (e: Exception) {
            media.release()
            target.delete()
            false
        }
    }

    private suspend fun sample() {
        while (scope.isActive && recorder != null) {
            // maxAmplitude : crête depuis le dernier appel ; la racine rend les voix calmes visibles.
            val peak = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            samples += sqrt((peak / 32_767f).coerceIn(0f, 1f))
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            _state.value = RecordingState(elapsed, samples.takeLast(48))
            if (elapsed >= MAX_VOICE_MS) return
            delay(SAMPLE_EVERY_MS)
        }
    }

    /** Termine et renvoie le vocal ; null s'il est trop court (appui accidentel) ou en échec. */
    fun stop(): RecordedVoice? {
        val media = recorder ?: return null
        val target = file
        val duration = SystemClock.elapsedRealtime() - startedAt
        val ok = runCatching { media.stop() }.isSuccess
        release()
        if (!ok || target == null || duration < MIN_VOICE_MS) {
            target?.delete()
            return null
        }
        return RecordedVoice(target, duration.coerceAtMost(MAX_VOICE_MS), downsample(samples, WAVEFORM_BARS))
    }

    fun cancel() {
        runCatching { recorder?.stop() }
        val target = file
        release()
        target?.delete()
    }

    private fun release() {
        sampler?.cancel()
        sampler = null
        recorder?.release()
        recorder = null
        file = null
        _state.value = null
    }
}

/** Moyenne par paquets puis normalisation sur la crête : l'onde garde sa forme quelle que soit la durée. */
internal fun downsample(values: List<Float>, bars: Int): List<Float> {
    if (values.isEmpty()) return List(bars) { 0.08f }
    val out = List(bars) { i ->
        val from = i * values.size / bars
        val to = maxOf(from + 1, (i + 1) * values.size / bars)
        values.subList(from, minOf(to, values.size)).average().toFloat()
    }
    val peak = out.maxOrNull()?.takeIf { it > 0f } ?: 1f
    return out.map { (it / peak).coerceIn(0.08f, 1f) }
}
