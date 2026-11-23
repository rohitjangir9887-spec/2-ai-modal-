package com.phoneuse.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * "소리 내어 읽기": replies read with the system text-to-speech, one at a time (a new one replaces the last).
 * Requests transient ducking audio focus, honors configured speech speed and voice gender preferences,
 * and stops immediately on barge-in.
 */
object ReadAloud {
    private const val TAG = "ReadAloud"
    private const val UTTERANCE = "phoneuse-reply"

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: PendingSpeech? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var onCompleteCallback: (() -> Unit)? = null

    /** Each reading's chunks share a number; only the last chunk's end clears [speaking]. */
    private var reading = 0

    @Volatile
    private var lastChunk: String? = null

    private val _speaking = MutableStateFlow(false)

    /** True while a reply is being read; the chat's menu offers "읽기 멈추기" then. */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private data class PendingSpeech(
        val text: String,
        val locale: Locale,
        val speed: Float,
        val gender: NvidiaVoiceGender,
        val onDone: (() -> Unit)?,
    )

    /** Reads [text] (Markdown marks dropped) in [language] (a BCP-47 tag; null = the device language). */
    @Synchronized
    fun speak(
        context: Context,
        text: String,
        language: String? = null,
        speed: Float = 1.0f,
        gender: NvidiaVoiceGender = NvidiaVoiceGender.NEUTRAL,
        onDone: (() -> Unit)? = null,
    ) {
        val plain = plain(text)
        if (plain.isBlank()) {
            onDone?.invoke()
            return
        }
        val appCtx = context.applicationContext
        if (audioManager == null) {
            audioManager = appCtx.getSystemService(AudioManager::class.java)
        }
        val locale = language?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
        val req = PendingSpeech(plain, locale, speed.coerceIn(0.6f, 1.6f), gender, onDone)
        val engine = tts
        if (engine == null) {
            pending = req
            tts = TextToSpeech(appCtx) { status -> onInit(status) }
            return
        }
        if (!ready) {
            pending = req
            return
        }
        say(engine, req)
    }

    @Synchronized
    fun stop() {
        pending = null
        lastChunk = null
        onCompleteCallback = null
        tts?.stop()
        abandonFocus()
        _speaking.value = false
    }

    @Synchronized
    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "text-to-speech unavailable ($status)")
            engine.shutdown()
            tts = null
            val cb = pending?.onDone
            pending = null
            cb?.invoke()
            return
        }
        ready = true
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == lastChunk) finishUtterance(invokeDone = true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == lastChunk) finishUtterance(invokeDone = false)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == lastChunk) finishUtterance(invokeDone = false)
            }
        })
        pending?.let { say(engine, it) }
        pending = null
    }

    @Synchronized
    private fun finishUtterance(invokeDone: Boolean) {
        _speaking.value = false
        abandonFocus()
        val cb = onCompleteCallback
        onCompleteCallback = null
        if (invokeDone) cb?.invoke()
    }

    private fun say(engine: TextToSpeech, req: PendingSpeech) {
        requestFocus()
        if (engine.isLanguageAvailable(req.locale) >= TextToSpeech.LANG_AVAILABLE) {
            engine.language = req.locale
        } else if (req.locale.language.isNotEmpty()) {
            val langOnly = Locale.forLanguageTag(req.locale.language)
            if (engine.isLanguageAvailable(langOnly) >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = langOnly
            }
        }
        engine.setSpeechRate(req.speed)
        engine.setPitch(
            when (req.gender) {
                NvidiaVoiceGender.FEMALE -> 1.06f
                NvidiaVoiceGender.MALE -> 0.92f
                NvidiaVoiceGender.NEUTRAL -> 1.0f
            },
        )
        runCatching {
            val voices = engine.voices
            if (voices != null && req.gender != NvidiaVoiceGender.NEUTRAL) {
                val wantFemale = req.gender == NvidiaVoiceGender.FEMALE
                val matched = voices.firstOrNull { v ->
                    v.locale.language == req.locale.language &&
                        ((wantFemale && (v.name.contains("female", true) || v.name.contains("-f-", true))) ||
                            (!wantFemale && (v.name.contains("male", true) || v.name.contains("-m-", true))))
                }
                if (matched != null) engine.voice = matched
            }
        }
        onCompleteCallback = req.onDone
        // Long answers go in pieces the engine accepts; the first flushes whatever was being read.
        val max = TextToSpeech.getMaxSpeechInputLength() - 1
        val parts = req.text.chunked(max)
        val id = ++reading
        lastChunk = "$UTTERANCE-$id-${parts.lastIndex}"
        parts.forEachIndexed { i, part ->
            engine.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$UTTERANCE-$id-$i")
        }
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    stop()
                }
            }
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        val req = focusRequest ?: return
        am.abandonAudioFocusRequest(req)
        focusRequest = null
    }

    /** The reply as it should sound: no Markdown marks, links read by their text. */
    fun plain(markdown: String): String =
        markdown
            .replace(Regex("""```[\s\S]*?```"""), " ")
            .replace(Regex("""!?\[([^\]]*)]\((?:[^()]|\([^()]*\))*\)"""), "$1")
            .replace(Regex("""(?m)^\s{0,3}(#{1,6}\s+|>\s?|[-*+]\s+|\d+\.\s+)"""), "")
            .replace(Regex("""[*_`~|]"""), "")
            .replace(Regex("""[ \t]+"""), " ")
            .trim()
}
