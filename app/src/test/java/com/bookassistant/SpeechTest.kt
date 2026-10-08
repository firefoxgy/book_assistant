package com.bookassistant

import android.os.Looper
import android.speech.tts.Voice
import com.bookassistant.services.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechTest {
    @get:Rule val folder = TemporaryFolder()
    private class Engine : SpeechEngine {
        lateinit var ready: (Boolean) -> Unit
        lateinit var error: (String) -> Unit
        lateinit var started: (String) -> Unit
        val spoken = mutableListOf<Triple<String, Boolean, String>>()
        var accepted = true
        override fun initialize(onReady: (Boolean) -> Unit, onError: (String) -> Unit, onDone: (String) -> Unit, onStart: (String) -> Unit) { ready = onReady; error = onError; started = onStart }
        override fun speak(text: String, british: Boolean, id: String): Boolean { spoken.add(Triple(text, british, id)); return accepted }
        override fun stop() = Unit
        override fun close() = Unit
    }
    private class Player : SpeechPlayback {
        val files = mutableListOf<File>()
        lateinit var error: () -> Unit
        override fun play(file: File, onDone: () -> Unit, onError: () -> Unit) { files.add(file); error = onError }
        override fun stop() = Unit
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun offlineVoiceSelectionDoesNotMixAccentsOrSelectNetworkVoices() {
        val us = Voice("us", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())
        val uk = Voice("uk", Locale.UK, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())
        val network = Voice("network", Locale.US, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, true, emptySet())
        assertEquals(us, offlineVoice(setOf(us, uk, network), false))
        assertEquals(uk, offlineVoice(setOf(us, uk, network), true))
        assertNull(offlineVoice(setOf(us, network), true))
    }

    @Test fun tapDuringInitializationQueuesTheLatestWordAndAccent() {
        val engine = Engine()
        val speech = Speech(engine, { _, _ -> error("Should use offline TTS") }, Player(), MainScope())
        try {
            speech.speak("first", false)
            speech.speak("opinion", true)
            assertTrue(engine.spoken.isEmpty())
            engine.ready(true); idle()
            assertEquals(1, engine.spoken.size)
            assertEquals("opinion", engine.spoken.single().first)
            assertTrue(engine.spoken.single().second)
        } finally { speech.close() }
    }

    @Test fun unavailableEngineUsesAudioWithRequestedAccent() {
        val engine = Engine()
        val player = Player()
        val requests = mutableListOf<Pair<String, Boolean>>()
        val file = folder.newFile("opinion.mp3")
        val speech = Speech(engine, { text, british -> requests.add(text to british); file }, player, MainScope())
        try {
            engine.ready(false); idle()
            speech.speak("opinion", false); idle()
            speech.speak("opinion", true); idle()
            assertEquals(listOf("opinion" to false, "opinion" to true), requests)
            assertEquals(2, player.files.size)
            assertNull(speech.state.value.error)
        } finally { speech.close() }
    }

    @Test fun asynchronousSynthesisErrorFallsBackOnce() {
        val engine = Engine()
        val player = Player()
        val file = folder.newFile()
        val speech = Speech(engine, { _, _ -> file }, player, MainScope())
        try {
            engine.ready(true); idle()
            speech.speak("opinion", false)
            val id = engine.spoken.single().third
            engine.error(id); idle()
            engine.error(id); idle()
            assertEquals(listOf(file), player.files)
            player.error()
            assertTrue(speech.state.value.error!!.contains("音频播放失败"))
        } finally { speech.close() }
    }

    @Test fun initializationTimeoutUsesAudioAndLateInitializationDoesNotDoublePlay() {
        val engine = Engine()
        val player = Player()
        val file = folder.newFile()
        val speech = Speech(engine, { _, _ -> file }, player, MainScope())
        try {
            speech.speak("opinion", false)
            idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertEquals(listOf(file), player.files)
            engine.ready(true); idle()
            assertTrue(engine.spoken.isEmpty())
        } finally { speech.close() }
    }

    @Test fun newTapCancelsOldDownloadAndDoesNotPlayOldWord() {
        val engine = Engine()
        val player = Player()
        val wait = CompletableDeferred<Unit>()
        val file = folder.newFile()
        val speech = Speech(engine, { text, _ -> if (text == "old") wait.await(); file }, player, MainScope())
        try {
            engine.ready(false); idle()
            speech.speak("old", false); idle()
            speech.speak("new", true); idle()
            wait.complete(Unit); idle()
            assertEquals(listOf(file), player.files)
        } finally { speech.close() }
    }

    @Test fun acceptedButNeverStartedSynthesisFallsBackInsteadOfStayingSilent() {
        val engine = Engine()
        val player = Player()
        val file = folder.newFile()
        val speech = Speech(engine, { _, _ -> file }, player, MainScope())
        try {
            engine.ready(true); idle()
            speech.speak("opinion", false); idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            assertEquals(listOf(file), player.files)
        } finally { speech.close() }
    }

    @Test fun startedSynthesisIsNotInterruptedByTheStartTimeout() {
        val engine = Engine()
        val player = Player()
        val speech = Speech(engine, { _, _ -> error("Should keep playing local speech") }, player, MainScope())
        try {
            engine.ready(true); idle()
            speech.speak("A longer English example sentence.", false); idle()
            engine.started(engine.spoken.single().third); idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            assertTrue(player.files.isEmpty())
            assertNull(speech.state.value.error)
        } finally { speech.close() }
    }

    @Test fun networkFailureAndMutedVolumeHaveVisibleErrorStates() {
        val engine = Engine()
        val speech = Speech(engine, { _, _ -> throw java.io.IOException("offline") }, Player(), MainScope())
        try {
            engine.ready(false); idle()
            speech.speak("opinion", false); idle()
            assertTrue(speech.state.value.error!!.contains("联网重试"))
            speech.dismissError()
            assertNull(speech.state.value.error)
        } finally { speech.close() }
        val muted = Speech(Engine(), { _, _ -> error("Should not download") }, Player(), MainScope(), muted = { true })
        try {
            muted.speak("opinion", false)
            assertTrue(muted.state.value.error!!.contains("媒体音量为 0"))
        } finally { muted.close() }
    }
}
