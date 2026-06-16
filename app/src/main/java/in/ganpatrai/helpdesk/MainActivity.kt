package `in`.ganpatrai.helpdesk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var conversationText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var callButton: Button
    private lateinit var serverUrlInput: EditText
    private lateinit var languageSpinner: Spinner

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var api: ApiClient? = null
    private var sessionId: String? = null
    private var callActive = false

    // Listening languages offered to the caller.
    private val languages = listOf(
        "English (India)" to "en-IN",
        "हिंदी (Hindi)" to "hi-IN",
        "मराठी (Marathi)" to "mr-IN",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        conversationText = findViewById(R.id.conversationText)
        scrollView = findViewById(R.id.scrollView)
        callButton = findViewById(R.id.callButton)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        languageSpinner = findViewById(R.id.languageSpinner)

        languageSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            languages.map { it.first })

        val prefs = getSharedPreferences("helpdesk", MODE_PRIVATE)
        serverUrlInput.setText(
            prefs.getString("server_url", "https://web-production-729c6.up.railway.app"))

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) setStatus(getString(R.string.tts_unavailable))
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) { listenIfActive() }
            override fun onDone(utteranceId: String?) { listenIfActive() }
        })

        callButton.setOnClickListener {
            if (callActive) endCall() else startCallWithPermission()
        }
    }

    // ------------------------------------------------------------ call flow
    private fun startCallWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        startCall()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCall()
        } else {
            setStatus(getString(R.string.mic_permission_needed))
        }
    }

    private fun startCall() {
        val url = serverUrlInput.text.toString().trim()
        if (url.isEmpty()) { setStatus(getString(R.string.enter_server_url)); return }
        getSharedPreferences("helpdesk", MODE_PRIVATE)
            .edit().putString("server_url", url).apply()

        api = ApiClient(url)
        callActive = true
        callButton.text = getString(R.string.end_call)
        setStatus(getString(R.string.connecting))

        thread {
            try {
                val start = api!!.startSession()
                sessionId = start.sessionId
                runOnUiThread {
                    appendLine("Reception: ${start.greeting}")
                    speak(start.greeting)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus(getString(R.string.connection_failed, e.message ?: ""))
                    resetCallButton()
                }
            }
        }
    }

    private fun endCall() {
        callActive = false
        recognizer?.destroy(); recognizer = null
        tts?.stop()
        val sid = sessionId
        if (sid != null) thread { api?.endSession(sid) }
        sessionId = null
        resetCallButton()
        setStatus(getString(R.string.call_ended))
    }

    private fun resetCallButton() {
        callActive = false
        callButton.text = getString(R.string.start_call)
    }

    // ------------------------------------------------------------ listening
    private fun listenIfActive() {
        if (callActive) runOnUiThread { startListening() }
    }

    private fun startListening() {
        if (!callActive) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus(getString(R.string.recognition_unavailable))
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)

        val langTag = languages[languageSpinner.selectedItemPosition].second
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                setStatus(getString(R.string.listening))
            }
            override fun onResults(results: Bundle?) {
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (heard.isNullOrBlank()) { listenIfActive(); return }
                handleUserSpeech(heard)
            }
            override fun onError(error: Int) {
                // No speech / timeout: simply listen again while call is active.
                if (callActive && (error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    listenIfActive()
                } else if (callActive) {
                    setStatus(getString(R.string.recognition_error, error))
                    listenIfActive()
                }
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                setStatus(getString(R.string.processing))
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer?.startListening(intent)
    }

    // --------------------------------------------------------- conversation
    private fun handleUserSpeech(heard: String) {
        appendLine("You: $heard")

        val lower = heard.lowercase(Locale.ROOT)
        if (listOf("hang up", "goodbye", "bye").any { it in lower }) {
            val farewell = getString(R.string.farewell)
            appendLine("Reception: $farewell")
            speakThenEnd(farewell)
            return
        }

        setStatus(getString(R.string.thinking))
        val sid = sessionId ?: return
        thread {
            try {
                val result = api!!.chat(sid, heard)
                sessionId = result.sessionId
                runOnUiThread {
                    appendLine("Reception: ${result.reply}")
                    speak(result.reply)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val msg = getString(R.string.server_trouble)
                    appendLine("Reception: $msg")
                    speak(msg)
                }
            }
        }
    }

    // ------------------------------------------------------- text to speech
    /**
     * Same language-detection idea as the desktop voice_caller.py:
     * Devanagari characters plus a Marathi keyword list decide between
     * Marathi and Hindi; otherwise Indian English is used.
     */
    private fun pickLocale(text: String): Locale {
        val hasDevanagari = text.any { it in '\u0900'..'\u097F' }
        if (!hasDevanagari) return Locale("en", "IN")
        val marathiKeywords = listOf(
            "आहे", "मध्ये", "साठी", "रुग्णालय", "येथे", "होय",
            "आपले", "स्वागत", "काय", "कसे", "मला")
        return if (marathiKeywords.any { it in text }) Locale("mr", "IN")
        else Locale("hi", "IN")
    }

    private fun speak(text: String) {
        if (!ttsReady) { listenIfActive(); return }
        setStatus(getString(R.string.speaking))
        tts?.language = pickLocale(text)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    private fun speakThenEnd(text: String) {
        callActive = false   // stop the listen loop after this utterance
        if (ttsReady) {
            tts?.language = pickLocale(text)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "farewell")
        }
        endCall()
    }

    // -------------------------------------------------------------- helpers
    private fun setStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    private fun appendLine(line: String) {
        conversationText.append(line + "\n\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
