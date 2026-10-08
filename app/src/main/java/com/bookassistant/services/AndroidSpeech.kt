package com.bookassistant.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale

private fun speechAttributes() = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

internal fun offlineVoice(voices: Set<Voice>?, british: Boolean): Voice? {
    val locale = if (british) Locale.UK else Locale.US
    return voices?.filter {
        it.locale.language == locale.language && it.locale.country == locale.country && !it.isNetworkConnectionRequired
    }?.maxByOrNull { it.quality }
}

internal class AndroidSpeechEngine(private val context: Context) : SpeechEngine {
    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false

    override fun initialize(onReady: (Boolean) -> Unit, onError: (String) -> Unit, onDone: (String) -> Unit, onStart: (String) -> Unit) {
        tts = TextToSpeech(context) { status -> handler.post {
            if (closed) return@post
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                engine.setAudioAttributes(speechAttributes())
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) { handler.post { onStart(id) } }
                    override fun onDone(id: String) { handler.post { onDone(id) } }
                    @Deprecated("Required legacy callback")
                    override fun onError(id: String) { handler.post { onError(id) } }
                    override fun onError(id: String, errorCode: Int) { handler.post { onError(id) } }
                })
                onReady(true)
            } else onReady(false)
        } }
    }

    override fun speak(text: String, british: Boolean, id: String): Boolean {
        val engine = tts ?: return false
        val locale = if (british) Locale.UK else Locale.US
        val voice = offlineVoice(engine.voices, british)
        if (voice != null) {
            if (engine.setVoice(voice) != TextToSpeech.SUCCESS) return false
        } else {
            // LANG_AVAILABLE means only "English", not a confirmed UK/US accent.
            if (engine.setLanguage(locale) < TextToSpeech.LANG_COUNTRY_AVAILABLE || engine.voice?.isNetworkConnectionRequired == true) return false
        }
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
        }
        return engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.SUCCESS
    }

    override fun stop() { tts?.stop() }
    override fun close() { closed = true; tts?.shutdown(); tts = null }
}

internal class AndroidSpeechPlayback(context: Context) : SpeechPlayback {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(speechAttributes()).setOnAudioFocusChangeListener { }.build()
    private var player: MediaPlayer? = null

    override fun play(file: File, onDone: () -> Unit, onError: () -> Unit) {
        stop()
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { onError(); return }
        val media = MediaPlayer()
        player = media
        try {
            media.setAudioAttributes(speechAttributes())
            media.setDataSource(file.absolutePath)
            media.setOnPreparedListener {
                if (player === media) runCatching { media.start() }.onFailure { stop(); onError() }
            }
            media.setOnCompletionListener { if (player === media) { stop(); onDone() } }
            media.setOnErrorListener { _, _, extra ->
                if (player === media) {
                    if (extra == MediaPlayer.MEDIA_ERROR_MALFORMED || extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED) file.delete()
                    stop(); onError()
                }
                true
            }
            media.prepareAsync()
        } catch (_: Exception) { stop(); onError() }
    }

    override fun stop() {
        player?.let { media ->
            media.setOnPreparedListener(null)
            media.setOnCompletionListener(null)
            media.setOnErrorListener(null)
            media.release()
        }
        player = null
        manager.abandonAudioFocusRequest(focus)
    }
}
