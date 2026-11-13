package com.surya.jarvis
import android.Manifest
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
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

    private val executor = Executors.newSingleThreadExecutor()

    private var voiceMode = false

    private val workerUrl =
        "https://jarvis-ai-backend.suryawebstudio.workers.dev"

    private val micPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                requestNotificationThenStartVoice()
            } else {
                binding.statusText.text = "MICROPHONE PERMISSION DENIED"
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            beginVoiceConversation()
        }

    private val voiceReceiver = object : BroadcastReceiver {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                JarvisVoiceForegroundService.ACTION_STATUS -> {
                    val status = intent.getStringExtra(JarvisVoiceForegroundService.EXTRA_STATUS).orEmpty()
                    if (status.isNotBlank()) binding.statusText.text = status
                    if (status == "VOICE MODE OFF") {
                        voiceMode = false
                        binding.micButton.text = "MIC - TAP TO SPEAK"
                    }
                }

                JarvisVoiceForegroundService.ACTION_USER_SPEECH -> {
                    val spokenText = intent.getStringExtra(JarvisVoiceForegroundService.EXTRA_SPEECH).orEmpty()
                    if (spokenText.isNotBlank()) {
                        binding.chatHistory.append("\n\nYOU:\n$spokenText")
                    }
                }
            }
        }
    }

    private val storageFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        binding.statusText.text = if (uri == null) {
            "STORAGE FOLDER NOT SELECTED"
        } else {
            JarvisFileManager(this).saveFolder(uri).second
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        if (intent?.action == JarvisFileManager.ACTION_CHOOSE_FOLDER) {
            storageFolderLauncher.launch(null)
        }

        if (JarvisVoiceForegroundService.isRunning) {
            voiceMode = true
            binding.micButton.text = "STOP VOICE"
            binding.statusText.text = "JARVIS LISTENING IN BACKGROUND"
        } else {
            binding.statusText.text = "SYSTEM ONLINE"
        }

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
                JarvisVoiceForegroundService.stop(this)

                binding.micButton.text = "MIC - TAP TO SPEAK"
                binding.statusText.text = "VOICE MODE OFF"
            } else {
                if (
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    requestNotificationThenStartVoice()
                } else {
                    micPermissionLauncher.launch(
                        Manifest.permission.RECORD_AUDIO
                    )
                }
            }
        }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            binding.responseText.text =
                "Enable JARVIS under Downloaded apps in Android Accessibility settings, then return here."
        }
    }

    private fun beginVoiceConversation() {
        voiceMode = true

        binding.micButton.text = "STOP VOICE"
        binding.statusText.text = "CONNECTING TO JARVIS LIVE..."

        JarvisVoiceForegroundService.start(this)
    }

    private fun requestNotificationThenStartVoice() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            beginVoiceConversation()
        }
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

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(JarvisVoiceForegroundService.ACTION_STATUS)
            addAction(JarvisVoiceForegroundService.ACTION_USER_SPEECH)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(voiceReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(voiceReceiver, filter)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.action == JarvisFileManager.ACTION_CHOOSE_FOLDER) {
            storageFolderLauncher.launch(null)
        }
    }

    override fun onStop() {
        unregisterReceiver(voiceReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
