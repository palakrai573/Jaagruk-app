package org.jaagruk.safety.input

import android.content.Context
import android.content.res.Resources
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecordedNarrationTest {
    private val context = mockk<Context>()
    private val resources = mockk<Resources>()
    private val media = mockk<MediaPlayer>(relaxed = true)
    private val onError = slot<MediaPlayer.OnErrorListener>()
    private val onDone = slot<MediaPlayer.OnCompletionListener>()
    private lateinit var player: NarrationPlayer
    private var completions = 0

    @Before fun setup() {
        every { context.resources } returns resources
        every { context.packageName } returns "org.jaagruk.safety"
        every { resources.getIdentifier(any(), "raw", any()) } returns 42
        mockkStatic(MediaPlayer::class)
        every { MediaPlayer.create(context, 42, any<AudioAttributes>(), 0) } returns media
        every { media.setOnErrorListener(capture(onError)) } returns Unit
        every { media.setOnCompletionListener(capture(onDone)) } returns Unit
        player = NarrationPlayer(context)
        player.prepare("sat")
    }

    @After fun cleanup() {
        player.release()
        unmockkStatic(MediaPlayer::class)
    }

    private fun speak() = player.speak("prompt", onComplete = { completions++ })

    @Test fun playbackFailureReleasesAndCompletesExactlyOnce() {
        speak()
        assertThat(player.state.value.speaking).isTrue()
        assertThat(onError.captured.onError(media, 1, 0)).isTrue()
        onDone.captured.onCompletion(media)
        assertThat(completions).isEqualTo(1)
        assertThat(player.state.value.source).isEqualTo(NarrationPlayer.Source.UNAVAILABLE)
        assertThat(player.state.value.speaking).isFalse()
        verify(exactly = 1) { media.release() }
    }

    @Test fun startFailureReleasesTheCreatedPlayer() {
        every { media.start() } throws IllegalStateException("broken recording")
        speak()
        assertThat(completions).isEqualTo(1)
        assertThat(player.state.value.speaking).isFalse()
        verify(exactly = 1) { media.release() }
    }

    @Test fun stopIgnoresLateRecordingCallbacks() {
        speak()
        player.stop()
        onDone.captured.onCompletion(media)
        onError.captured.onError(media, 1, 0)
        assertThat(completions).isEqualTo(0)
        verify(exactly = 1) { media.release() }
    }

    @Test fun completionReleasesTheRecordingBeforeAdvancing() {
        speak()
        onDone.captured.onCompletion(media)
        assertThat(completions).isEqualTo(1)
        assertThat(player.state.value.speaking).isFalse()
        verify(exactly = 1) { media.release() }
        verify(exactly = 0) { media.setAudioAttributes(any()) }
    }

    @Test fun replacedRecordingCannotCompleteTheNewClip() {
        speak()
        val oldDone = onDone.captured
        val newer = mockk<MediaPlayer>(relaxed = true)
        every { MediaPlayer.create(context, 42, any<AudioAttributes>(), 0) } returns newer
        speak()
        oldDone.onCompletion(media)
        assertThat(completions).isEqualTo(0)
        assertThat(player.state.value.speaking).isTrue()
        verify(exactly = 0) { newer.release() }
    }
}
