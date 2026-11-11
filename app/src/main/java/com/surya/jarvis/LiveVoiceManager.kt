package com.surya.jarvis
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LiveVoiceManager(
    private val context: Context,
    private val onUserSpeech: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {

    companion object {
        private const val WORKER_URL =
            "https://jarvis-ai-backend.suryawebstudio.workers.dev"

        private const val LIVE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"

        private const val MODEL =
            "models/gemini-3.1-flash-live-preview"

        private const val INPUT_RATE = 16000
        private const val OUTPUT_RATE = 24000
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var setupComplete = false

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recorderThread: Thread? = null

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private val playbackLock = Any()

    fun startListening() {
        if (!running.compareAndSet(false, true)) return

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            running.set(false)
            status("MICROPHONE PERMISSION REQUIRED")
            return
        }

        setupComplete = false
        status("REQUESTING TEMPORARY TOKEN...")

        Thread {
            try {
                val tokenRequest = Request.Builder()
                    .url("$WORKER_URL/live-token")
                    .post("{}".toRequestBody())
                    .build()

                val response = client.newCall(tokenRequest).execute()

                val responseBody =
                    response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    throw IOException(
                        "Token HTTP ${response.code}: $responseBody"
                    )
                }

                val json = JSONObject(responseBody)

                val token = json.optString("token")
                    .ifBlank { json.optString("name") }

                if (token.isBlank()) {
                    throw IOException(
                        "Temporary token missing: $responseBody"
                    )
                }

                if (!running.get()) return@Thread

                val request = Request.Builder()
                    .url(LIVE_URL)
                    .header("Authorization", "Token $token")
                    .build()

                webSocket = client.newWebSocket(
                    request,
                    liveListener
                )

            } catch (e: Exception) {
                fail("TOKEN ERROR: ${e.message}")
            }
        }.start()
    }

    private val liveListener = object : WebSocketListener() {

        override fun onOpen(
            webSocket: WebSocket,
            response: Response
        ) {
            status("CONNECTED — SENDING SESSION SETUP...")

            val setup = JSONObject().apply {
                put(
                    "setup",
                    JSONObject().apply {
                        put("model", MODEL)

                        put(
                            "generationConfig",
                            JSONObject().apply {
                                put(
                                    "responseModalities",
                                    JSONArray().put("AUDIO")
                                )
                            }
                        )

                        put(
                            "systemInstruction",
                            JSONObject().apply {
                                put(
                                    "parts",
                                    JSONArray().put(
                                        JSONObject().put(
                                            "text",
                                            "You are JARVIS, an intelligent personal AI assistant. " +
                                            "Speak naturally, clearly and conversationally. " +
                                            "Be concise unless more detail is needed. " +
                                            "You are speaking directly with the user in a live voice conversation."
                                        )
                                    )
                                )
                            }
                        )

                        put("sessionResumption", JSONObject())
                    }
                )
            }

            webSocket.send(setup.toString())
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String
        ) {
            handleServerMessage(webSocket, text)
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString
        ) {
            handleServerMessage(webSocket, bytes.utf8())
        }

        private fun handleServerMessage(
            webSocket: WebSocket,
            text: String
        ) {
            try {
                val json = JSONObject(text)

                if (json.has("error")) {
                    fail("LIVE SERVICE ERROR — PLEASE TRY AGAIN")
                    return
                }

                if (json.has("setupComplete")) {
                    setupComplete = true
                    status("JARVIS LIVE — LISTENING")
                    startAudioCapture()
                    return
                }

                val serverContent =
                    json.optJSONObject("serverContent") ?: return

                if (serverContent.optBoolean("interrupted")) {
                    synchronized(playbackLock) {
                        try {
                            audioTrack?.pause()
                            audioTrack?.flush()
                            audioTrack?.play()
                        } catch (_: Exception) {
                        }
                    }
                }

                val modelTurn =
                    serverContent.optJSONObject("modelTurn") ?: return

                val parts =
                    modelTurn.optJSONArray("parts") ?: return

                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue

                    val inlineData =
                        part.optJSONObject("inlineData") ?: continue

                    val encoded = inlineData.optString("data")

                    if (encoded.isNotBlank()) {
                        val audio = Base64.decode(
                            encoded,
                            Base64.DEFAULT
                        )

                        playAudio(audio)
                    }
                }

            } catch (e: Exception) {
                status("LIVE RESPONSE COULD NOT BE PROCESSED")
            }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?
        ) {
            val details = response?.let {
                "HTTP ${it.code}: ${it.message}"
            } ?: t.message

            fail("WEBSOCKET ERROR: $details")
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String
        ) {
            setupComplete = false

            if (running.get()) {
                fail("SESSION CLOSED: $code $reason")
            }
        }
    }

    private fun startAudioCapture() {
        if (!running.get() || !setupComplete) return

        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBuffer <= 0) {
                throw IOException("Invalid microphone buffer size")
            }

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, 6400)
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw IOException("Microphone initialization failed")
            }

            audioRecord = recorder

            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler =
                    AcousticEchoCanceler.create(recorder.audioSessionId)

                echoCanceler?.enabled = true
            }

            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor =
                    NoiseSuppressor.create(recorder.audioSessionId)

                noiseSuppressor?.enabled = true
            }

            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE)
                    as AudioManager

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            recorder.startRecording()

            recorderThread = Thread {
                val buffer = ByteArray(3200)

                while (running.get() && setupComplete) {
                    val count = recorder.read(
                        buffer,
                        0,
                        buffer.size
                    )

                    if (
                        count > 0 &&
                        running.get() &&
                        setupComplete
                    ) {
                        val chunk = ByteArray(count)

                        System.arraycopy(
                            buffer,
                            0,
                            chunk,
                            0,
                            count
                        )

                        val encoded = Base64.encodeToString(
                            chunk,
                            Base64.NO_WRAP
                        )

                        val message = JSONObject().apply {
                            put(
                                "realtimeInput",
                                JSONObject().apply {
                                    put(
                                        "audio",
                                        JSONObject().apply {
                                            put(
                                                "mimeType",
                                                "audio/pcm;rate=16000"
                                            )
                                            put("data", encoded)
                                        }
                                    )
                                }
                            )
                        }

                        if (webSocket?.send(message.toString()) != true) {
                            fail("AUDIO SEND FAILED")
                            break
                        }
                    } else if (count < 0) {
                        fail("MICROPHONE READ ERROR: $count")
                        break
                    }
                }
            }

            recorderThread?.start()

        } catch (e: Exception) {
            fail("MICROPHONE ERROR: ${e.message}")
        }
    }

    private fun playAudio(bytes: ByteArray) {
        try {
            synchronized(playbackLock) {
                if (audioTrack == null) {
                    val minBuffer = AudioTrack.getMinBufferSize(
                        OUTPUT_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )

                    if (minBuffer <= 0) {
                        throw IOException("Invalid speaker buffer size")
                    }

                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(
                                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                                )
                                .setContentType(
                                    AudioAttributes.CONTENT_TYPE_SPEECH
                                )
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(
                                    AudioFormat.ENCODING_PCM_16BIT
                                )
                                .setSampleRate(OUTPUT_RATE)
                                .setChannelMask(
                                    AudioFormat.CHANNEL_OUT_MONO
                                )
                                .build()
                        )
                        .setBufferSizeInBytes(
                            maxOf(minBuffer, 9600)
                        )
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    audioTrack = track
                    track.play()
                }

                audioTrack?.write(
                    bytes,
                    0,
                    bytes.size,
                    AudioTrack.WRITE_BLOCKING
                )
            }

        } catch (e: Exception) {
            status("SPEAKER ERROR: ${e.message}")
        }
    }

    private fun status(message: String) {
        mainHandler.post {
            onStatus(message)
        }
    }

    private fun fail(message: String) {
        running.set(false)
        setupComplete = false
        status(message)

        try {
            webSocket?.close(1000, "Connection error")
        } catch (_: Exception) {
        }
    }

    fun stopListening() {
        stop()
    }

    fun stop() {
        running.set(false)
        setupComplete = false

        try {
            recorderThread?.interrupt()
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord = null
        recorderThread = null

        synchronized(playbackLock) {
            try {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.release()
            } catch (_: Exception) {
            }

            audioTrack = null
        }

        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }

        echoCanceler = null
        noiseSuppressor = null

        try {
            webSocket?.close(1000, "User stopped")
        } catch (_: Exception) {
        }

        webSocket = null

        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE)
                as AudioManager

        audioManager.mode = AudioManager.MODE_NORMAL

        status("VOICE MODE OFF")
    }

    fun release() {
        stop()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
