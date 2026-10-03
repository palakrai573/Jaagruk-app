package org.jaagruk.safety.input

import android.content.res.Configuration
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class NarrationPlayerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val player = NarrationPlayer(context)
    private val key = "step_fire_detect_alarm_prompt"

    @After fun tearDown() = player.release()

    private fun initialise(language: String): ShadowTextToSpeech {
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag(language))
        player.prepare(language)
        val engine = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        engine.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        return engine
    }

    @Test fun keyOnlyPromptUsesPreparedHindiNotApplicationLocale() {
        val engine = initialise("hi")
        player.speak(key)
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale("hi")) }
        val localised = context.createConfigurationContext(configuration)
        val id = localised.resources.getIdentifier(key, "string", context.packageName)
        assertThat(engine.lastSpokenText).isEqualTo(localised.getString(id))
        assertThat(engine.lastSpokenText).isNotEmpty()
        assertThat(player.state.value.source).isEqualTo(NarrationPlayer.Source.SYNTHESISED)
    }

    @Test fun initialisationSpeaksOnlyLatestPendingPrompt() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.ENGLISH)
        player.prepare("en")
        player.speak(key, "Old prompt")
        player.speak(key, "Latest prompt")
        val engine = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        engine.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(engine.spokenTextList).containsExactly("Latest prompt")
    }

    @Test fun stopCancelsPromptWaitingForInitialisation() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.ENGLISH)
        player.prepare("en")
        player.speak(key)
        player.stop()
        val engine = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        engine.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(engine.spokenTextList).isEmpty()
    }

    @Test fun switchingToSantaliNeverUsesEnglishTts() {
        val engine = initialise("en")
        player.prepare("sat")
        player.speak(key, "Do not speak English for Santali")
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(engine.spokenTextList).isEmpty()
        assertThat(player.state.value.source).isEqualTo(NarrationPlayer.Source.UNAVAILABLE)
        assertThat(player.state.value.speaking).isFalse()
    }

    @Test fun releaseIgnoresLateInitialisation() {
        player.prepare("en")
        player.speak(key)
        val engine = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        player.release()
        engine.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(engine.spokenTextList).isEmpty()
        assertThat(engine.isShutdown).isTrue()
    }

    @Test fun unavailableNarrationCompletesWithoutHoldingTheAssessment() {
        player.prepare("sat")
        var completions = 0
        player.speak(key, onComplete = { completions++ })
        assertThat(completions).isEqualTo(1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertThat(completions).isEqualTo(1)
    }

    @Test fun stalledInitialisationEventuallyReleasesTheAssessment() {
        player.prepare("en")
        var completions = 0
        player.speak(key, onComplete = { completions++ })
        assertThat(completions).isEqualTo(0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertThat(completions).isEqualTo(1)
        assertThat(player.state.value.source).isEqualTo(NarrationPlayer.Source.UNAVAILABLE)
        player.speak(key, "Next option", onComplete = { completions++ })
        assertThat(completions).isEqualTo(2)
    }

    @Test fun stoppedNarrationNeverCompletesAnAbandonedQuestion() {
        player.prepare("en")
        var completions = 0
        player.speak(key, onComplete = { completions++ })
        player.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertThat(completions).isEqualTo(0)
    }

    @Test fun successfulSpeechCompletesExactlyOnce() {
        initialise("en")
        var completions = 0
        player.speak(key, "Test narration", onComplete = { completions++ })
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(completions).isEqualTo(1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertThat(completions).isEqualTo(1)
    }
}
