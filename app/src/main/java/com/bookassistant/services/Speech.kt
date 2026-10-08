package com.bookassistant.services

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

internal interface SpeechEngine {
    fun initialize(onReady: (Boolean) -> Unit, onError: (String) -> Unit, onDone: (String) -> Unit, onStart: (String) -> Unit)
    fun speak(text: String, british: Boolean, id: String): Boolean
    fun stop()
    fun close()
}

internal interface SpeechPlayback {
    fun play(file: File, onDone: () -> Unit, onError: () -> Unit)
    fun stop()
}

data class SpeechState(val message: String = "", val error: String? = null)

/** A queued tap survives TTS initialization. Unavailable/failed TTS falls back to cached audio. */
class Speech internal constructor(
    private val engine: SpeechEngine,
    private val audio: suspend (String, Boolean) -> File,
    private val player: SpeechPlayback,
    private val scope: CoroutineScope,
    private val muted: () -> Boolean = { false },
    private val initializationTimeout: Long = 2000
) {
    constructor(context: Context) : this(
        AndroidSpeechEngine(context.applicationContext),
        PronunciationAudio(File(context.filesDir, "pronunciation")).let { source -> { text, british -> source.get(text, british) } },
        AndroidSpeechPlayback(context.applicationContext), MainScope(),
        { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC) == 0 }
    )

    private data class Request(val text: String, val british: Boolean, val id: String = UUID.randomUUID().toString(), var audioOnly: Boolean = false, var started: Boolean = false)
    private val mutableState = MutableStateFlow(SpeechState())
    val state = mutableState.asStateFlow()
    private var ready: Boolean? = null
    private var current: Request? = null
    private var deadline: Job? = null
    private var download: Job? = null
    private var closed = false

    init {
        engine.initialize({ success -> scope.launch {
            if (!closed) {
                ready = success
                deadline?.cancel()
                current?.takeUnless { it.audioOnly }?.let { play(it) }
            }
        } }, { id -> scope.launch {
            current?.takeIf { it.id == id && !it.audioOnly }?.let { fallback(it) }
        } }, { id -> scope.launch {
            if (current?.id == id && current?.audioOnly == false) {
                current?.started = true
                deadline?.cancel()
                mutableState.value = SpeechState()
            }
        } }, { id -> scope.launch {
            if (current?.id == id && current?.audioOnly == false) {
                current?.started = true
                deadline?.cancel()
            }
        } })
    }

    fun speak(text: String, british: Boolean) {
        if (closed) return
        deadline?.cancel()
        download?.cancel()
        current = null
        engine.stop()
        player.stop()
        if (muted()) { mutableState.value = SpeechState(error = "媒体音量为 0，请调高媒体音量后重试；如连接了蓝牙耳机，请检查声音输出设备。"); return }
        if (text.isBlank()) { mutableState.value = SpeechState(error = "没有可朗读的文字"); return }
        val request = Request(text.trim(), british)
        current = request
        mutableState.value = SpeechState("正在准备发音…")
        if (ready != null) play(request)
        else deadline = scope.launch { delay(initializationTimeout); if (current === request) fallback(request) }
    }

    private fun play(request: Request) {
        if (current !== request || closed) return
        mutableState.value = SpeechState("正在朗读…")
        if (ready != true || !runCatching { engine.speak(request.text, request.british, request.id) }.getOrDefault(false)) fallback(request)
        else if (!request.audioOnly && !request.started) {
            // SUCCESS only means queued; a broken engine can accept a request without ever starting it.
            deadline = scope.launch { delay(3000); if (current === request && !request.started) fallback(request) }
        }
    }

    private fun fallback(request: Request) {
        if (current !== request || request.audioOnly || closed) return
        request.audioOnly = true
        deadline?.cancel()
        engine.stop()
        mutableState.value = SpeechState("正在加载发音…")
        download = scope.launch {
            try {
                val file = audio(request.text, request.british)
                if (current !== request || closed) return@launch
                mutableState.value = SpeechState("正在播放发音…")
                player.play(file, {
                    if (current === request) mutableState.value = SpeechState()
                }, {
                    if (current === request) mutableState.value = SpeechState(error = "音频播放失败，请检查媒体音量和声音输出设备后重试。")
                })
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (current === request) mutableState.value = SpeechState(error = "发音暂不可用。本机英语语音不可用，且未能读取或下载发音音频。请联网重试，或在系统语音设置中安装英语语音。")
            }
        }
    }

    fun dismissError() { mutableState.value = mutableState.value.copy(error = null) }
    fun close() { closed = true; current = null; scope.cancel(); player.stop(); engine.close() }
}
