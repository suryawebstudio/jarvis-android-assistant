package com.surya.jarvis
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.surya.jarvis.databinding.ActivityMainBinding
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var voiceManager: LiveVoiceManager

    private val executor = Executors.newSingleThreadExecutor()

    private var voiceMode = false

    private val workerUrl =
        "https://jarvis-ai-backend.suryawebstudio.workers.dev"

    private val micPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                beginVoiceConversation()
            } else {
                binding.statusText.text = "MICROPHONE PERMISSION DENIED"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        voiceManager = LiveVoiceManager(
            this,
            onUserSpeech = { spokenText ->
                runOnUiThread {
                    binding.chatHistory.append(
                        "\n\nYOU:\n$spokenText"
                    )
                }
            },
            onStatus = { status ->
                runOnUiThread {
                    binding.statusText.text = status
                }
            }
        )

        binding.statusText.text = "SYSTEM ONLINE"

        binding.chatButton.setOnClickListener {
            val visible =
                binding.chatPanel.visibility == View.VISIBLE

            binding.chatPanel.visibility =
                if (visible) View.GONE else View.VISIBLE

            binding.chatButton.text =
                if (visible) "OPEN CHAT" else "CLOSE CHAT"
        }

        binding.sendButton.setOnClickListener {
            sendMessage()
        }

        binding.messageInput.setOnEditorActionListener {
            _, _, _ ->
            sendMessage()
            true
        }

        binding.micButton.setOnClickListener {
            if (voiceMode) {
                voiceMode = false
                voiceManager.stop()

                binding.micButton.text = "MIC - TAP TO SPEAK"
                binding.statusText.text = "VOICE MODE OFF"
            } else {
                if (
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    beginVoiceConversation()
                } else {
                    micPermissionLauncher.launch(
                        Manifest.permission.RECORD_AUDIO
                    )
                }
            }
        }

        binding.settingsButton.setOnClickListener {
            binding.responseText.text =
                "JARVIS settings will be developed next."
        }
    }

    private fun beginVoiceConversation() {
        voiceMode = true

        binding.micButton.text = "STOP VOICE"
        binding.statusText.text = "CONNECTING TO JARVIS LIVE..."

        voiceManager.startListening()
    }

    private fun sendMessage() {
        val message =
            binding.messageInput.text.toString().trim()

        if (message.isEmpty()) return

        binding.chatHistory.append(
            "\n\nYOU:\n$message"
        )

        binding.messageInput.text.clear()

        binding.sendButton.isEnabled = false
        binding.sendButton.text = "THINKING..."
        binding.statusText.text = "JARVIS IS THINKING..."

        executor.execute {
            var connection: HttpURLConnection? = null

            try {
                connection =
                    URL(workerUrl).openConnection()
                        as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 90000
                connection.doOutput = true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                val request = JSONObject()
                request.put("message", message)

                connection.outputStream.use { output ->
                    output.write(
                        request.toString().toByteArray(Charsets.UTF_8)
                    )
                }

                val responseCode = connection.responseCode

                val responseStream =
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val responseText =
                    responseStream?.bufferedReader()
                        ?.use { it.readText() } ?: ""

                if (responseCode !in 200..299) {
                    throw Exception("HTTP $responseCode: $responseText")
                }

                val json = JSONObject(responseText)

                if (!json.optBoolean("success", false)) {
                    throw Exception(
                        json.optString("error", "AI request failed")
                    )
                }

                val reply = json.optString("reply", "")

                runOnUiThread {
                    binding.chatHistory.append(
                        "\n\nJARVIS:\n$reply"
                    )

                    binding.responseText.text = reply

                    binding.sendButton.isEnabled = true
                    binding.sendButton.text = "SEND"

                    if (!voiceMode) {
                        binding.statusText.text = "SYSTEM ONLINE"
                    }

                    binding.mainScroll.post {
                        binding.mainScroll.fullScroll(
                            View.FOCUS_DOWN
                        )
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    binding.chatHistory.append(
                        "\n\nJARVIS ERROR:\n${e.message}"
                    )

                    binding.statusText.text = "CONNECTION ERROR"
                    binding.sendButton.isEnabled = true
                    binding.sendButton.text = "SEND"
                }

            } finally {
                connection?.disconnect()
            }
        }
    }

    override fun onDestroy() {
        voiceMode = false
        voiceManager.release()
        executor.shutdownNow()
        super.onDestroy()
    }
}
