package com.marqueelens

import android.app.Activity
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService

/**
 * A silent text-to-speech engine. Apps that draw their own screen (Netflix on TV) don't share their titles with
 * accessibility apps, but they do say them aloud through the TV's speech engine. Chosen as that engine, Marquee Lens
 * receives the words ("Stranger Things, Season 4, …"), shows the rating, and stays quiet.
 */
class SpeechCatcher : TextToSpeechService() {

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onStop() {}

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = (request.charSequenceText ?: request.text)?.toString().orEmpty()
        val caller = if (Build.VERSION.SDK_INT >= 24) packageManager.getNameForUid(request.callerUid).orEmpty().substringBefore(':') else ""
        if (text.isNotBlank()) OverlayService.instance?.onSpoken(text, caller)
        // a moment of silence, so the app knows the "speech" finished
        callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1)
        val silence = ByteArray(1600)
        callback.audioAvailable(silence, 0, minOf(silence.size, callback.maxBufferSize))
        callback.done()
    }
}

/** Android's speech settings ask an engine which voices it has: "all of them" (it never really speaks). */
class SpeechCatcherCheck : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val voices = arrayListOf("eng-USA", "eng-GBR", "heb-ISR")
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, Intent()
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, voices)
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf()))
        finish()
    }
}
