package com.bookassistant

import android.media.AudioAttributes
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bookassistant.services.AndroidSpeechPlayback
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSpeechPlaybackTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun preparedAudioStartsOnTheMediaOutputAndReleasesOnCompletion() {
        val file = folder.newFile("opinion.mp3")
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.path), ShadowMediaPlayer.MediaInfo(1500, 0))
        lateinit var media: ShadowMediaPlayer
        ShadowMediaPlayer.setCreateListener { _, shadow -> media = shadow }
        val playback = AndroidSpeechPlayback(ApplicationProvider.getApplicationContext())
        var completed = false
        var failed = false
        try {
            playback.play(file, { completed = true }, { failed = true })
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(media.isReallyPlaying)
            assertEquals(AudioAttributes.USAGE_MEDIA, media.audioAttributes.usage)
            assertFalse(failed)
            media.invokeCompletionListener()
            assertTrue(completed)
            assertEquals(ShadowMediaPlayer.State.END, media.state)
        } finally { playback.stop(); ShadowMediaPlayer.setCreateListener(null) }
    }

    @Test fun codecFailureIsReportedAndInvalidCachedAudioIsRemoved() {
        val file = folder.newFile("invalid.mp3")
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.path), ShadowMediaPlayer.MediaInfo(1500, 0))
        lateinit var media: ShadowMediaPlayer
        ShadowMediaPlayer.setCreateListener { _, shadow -> media = shadow }
        val playback = AndroidSpeechPlayback(ApplicationProvider.getApplicationContext())
        var failed = false
        try {
            playback.play(file, { }, { failed = true })
            shadowOf(Looper.getMainLooper()).idle()
            media.invokeErrorListener(android.media.MediaPlayer.MEDIA_ERROR_UNKNOWN, android.media.MediaPlayer.MEDIA_ERROR_MALFORMED)
            assertTrue(failed)
            assertFalse(file.exists())
        } finally { playback.stop(); ShadowMediaPlayer.setCreateListener(null) }
    }
}
