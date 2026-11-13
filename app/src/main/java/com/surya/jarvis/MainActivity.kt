package com.surya.jarvis

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
    private var receiverRegistered = false
    private val workerUrl = "https://jarvis-ai-backend.suryawebstudio.workers.dev"
    private val memoryStore by lazy { JarvisMemoryStore(applicationContext) }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestNotificationThenStartVoice()
        else runJs("showToast('MICROPHONE PERMISSION DENIED')")
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginVoiceConversation()
        else runJs("showToast('NOTIFICATION PERMISSION REQUIRED FOR BACKGROUND VOICE')")
    }

    private val storageFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val message = if (uri == null) {
            "STORAGE FOLDER NOT SELECTED"
        } else {
            JarvisFileManager(this).saveFolder(uri).second
        }
        runJs("showToast(${JSONObject.quote(message)})")
    }

    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                JarvisVoiceForegroundService.ACTION_STATUS -> {
                    val status = intent.getStringExtra(JarvisVoiceForegroundService.EXTRA_STATUS).orEmpty()
                    when {
                        status == "JARVIS LIVE — LISTENING" -> runJs("setMode('listening')")
                        status == "VOICE MODE OFF" -> {
                            voiceMode = false
                            runJs("setMode('idle');document.getElementById('micLabel').textContent='TAP TO SPEAK'")
                        }
                        status.contains("ERROR") || status.contains("FAILED") || status.contains("REQUIRED") -> {
                            voiceMode = false
                            runJs("setMode('idle');showToast(${JSONObject.quote(status)})")
                        }
                        status.isNotBlank() -> runJs("setMode('thinking')")
                    }
                }
                JarvisVoiceForegroundService.ACTION_USER_SPEECH -> {
                    val spokenText = intent.getStringExtra(JarvisVoiceForegroundService.EXTRA_SPEECH).orEmpty()
                    if (spokenText.isNotBlank()) {
                        runJs("addChatFromAndroid(${JSONObject.quote(spokenText)})")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureWebView()
        if (intent?.action == JarvisFileManager.ACTION_CHOOSE_FOLDER) {
            storageFolderLauncher.launch(null)
        }
    }

    private fun configureWebView() {
        binding.jarvisWebView.settings.javaScriptEnabled = true
        binding.jarvisWebView.settings.domStorageEnabled = true
        binding.jarvisWebView.settings.allowFileAccess = true
        binding.jarvisWebView.settings.allowContentAccess = false
        binding.jarvisWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "file"
        }
        binding.jarvisWebView.addJavascriptInterface(JarvisBridge(), "AndroidJarvis")
        binding.jarvisWebView.loadUrl("file:///android_asset/jarvis-interactive-ui.html")
    }

    private inner class JarvisBridge {
        @JavascriptInterface
        fun startVoice() = runOnUiThread {
            if (voiceMode) this@MainActivity.stopVoice() else requestVoiceStart()
        }

        @JavascriptInterface
        fun stopVoice() = runOnUiThread { this@MainActivity.stopVoice() }

        @JavascriptInterface
        fun sendMessage(message: String) {
            sendMessageToBackend(message)
        }

        @JavascriptInterface
        fun setVoiceResponses(enabled: Boolean) = saveUiPreference("voice_responses", enabled)

        @JavascriptInterface
        fun setSoundEffects(enabled: Boolean) = saveUiPreference("sound_effects", enabled)

        @JavascriptInterface
        fun setParticleEffects(enabled: Boolean) = saveUiPreference("particle_effects", enabled)

        @JavascriptInterface
        fun setAutoListening(enabled: Boolean) = runOnUiThread {
            saveUiPreference("auto_listening", enabled)
            if (enabled && !voiceMode) requestVoiceStart()
            if (!enabled && voiceMode) stopVoice()
        }

        @JavascriptInterface
        fun getUiState(): String {
            val preferences = getSharedPreferences("jarvis_ui", MODE_PRIVATE)
            return JSONObject()
                .put("voiceResponses", preferences.getBoolean("voice_responses", true))
                .put("soundEffects", preferences.getBoolean("sound_effects", true))
                .put("particleEffects", preferences.getBoolean("particle_effects", true))
                .put("autoListening", preferences.getBoolean("auto_listening", false))
                .put("fullStorage", JarvisFileManager(this@MainActivity).isFullAccessEnabled())
                .put("memoryCount", memoryStore.all().size)
                .toString()
        }

        @JavascriptInterface
        fun getMemories(): String = memoryStore.summary()

        @JavascriptInterface
        fun clearMemories() = runOnUiThread {
            memoryStore.clear()
            runJs("updateMemoryLabel(0);showToast('ALL MEMORIES CLEARED')")
        }

        @JavascriptInterface
        fun toggleFullStorage() = runOnUiThread { toggleFullStorageAccess() }

        @JavascriptInterface
        fun getShareableFiles(): String = JarvisFileManager(this@MainActivity).shareableFilesJson()

        @JavascriptInterface
        fun shareFile(name: String) = runOnUiThread {
            val result = JarvisFileManager(this@MainActivity).shareFile(name)
            if (!result.first) runJs("showToast(${JSONObject.quote(result.second)})")
        }

        @JavascriptInterface
        fun openAccessibility() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun requestVoiceStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            requestNotificationThenStartVoice()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun beginVoiceConversation() {
        voiceMode = true
        runJs("setMode('thinking');document.getElementById('micLabel').textContent='STOP LISTENING'")
        JarvisVoiceForegroundService.start(this)
    }

    private fun stopVoice() {
        voiceMode = false
        // stopService() is asynchronous, so this must remain safe when the
        // button is tapped repeatedly while the service is already stopping.
        runCatching { JarvisVoiceForegroundService.stop(this) }
        runJs("setMode('idle');document.getElementById('micLabel').textContent='TAP TO SPEAK'")
    }

    private fun requestNotificationThenStartVoice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            beginVoiceConversation()
        }
    }

    private fun sendMessageToBackend(message: String) {
        val cleanMessage = message.trim()
        if (cleanMessage.isEmpty()) return
        rememberAutomaticFact(cleanMessage)
        val memoryCommand = handleMemoryCommand(cleanMessage)
        if (memoryCommand != null) {
            runJs("jarvisSpeak(${JSONObject.quote(memoryCommand)})")
            return
        }
        val memoryContext = memoryStore.all().joinToString("\n") { "- $it" }
        val prompt = if (memoryContext.isBlank()) {
            cleanMessage
        } else {
            "[PRIVATE JARVIS MEMORY]\n$memoryContext\n" +
                "Use these memories only when relevant. Do not mention this memory block unless asked. " +
                "If a memory conflicts with the user's current message, follow the current message.\n" +
                "[END MEMORY]\nUser: $cleanMessage"
        }
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(workerUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 90_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }
                connection.outputStream.use { it.write(JSONObject().put("message", prompt).toString().toByteArray()) }
                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode")
                val json = JSONObject(responseText)
                if (!json.optBoolean("success", false)) throw IllegalStateException("AI request failed")
                val reply = json.optString("reply").ifBlank { "I did not receive a response." }
                runJs("jarvisSpeak(${JSONObject.quote(reply)});setMode('idle')")
            } catch (_: Exception) {
                runJs("jarvisSpeak('I could not reach the assistant right now.');setMode('idle')")
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun handleMemoryCommand(message: String): String? {
        val normalized = message.trim()
        val lower = normalized.lowercase()
        return when {
            lower == "what do you remember" || lower == "what do you remember about me" ||
                lower == "list my memories" -> memoryStore.summary()
            lower == "forget everything" || lower == "forget all memories" ||
                lower == "clear your memories" -> {
                memoryStore.clear()
                runJs("updateMemoryLabel(0)")
                "I cleared all of my saved memories."
            }
            lower.startsWith("forget ") -> {
                val query = normalized.substringAfter(' ', "").trim()
                val removed = memoryStore.forget(query)
                runJs("updateMemoryLabel(${memoryStore.all().size})")
                if (removed > 0) "I forgot that memory." else "I could not find a matching memory."
            }
            lower.startsWith("remember that ") || lower.startsWith("remember ") -> {
                val fact = normalized.substring(8).trimStart()
                    .let { if (it.startsWith("that ", ignoreCase = true)) it.substring(5) else it }
                val saved = memoryStore.remember(fact)
                runJs("updateMemoryLabel(${memoryStore.all().size})")
                if (saved) "I will remember that." else "I already remember that, or the memory was too long."
            }
            else -> null
        }
    }

    private fun rememberAutomaticFact(message: String) {
        val fact = when {
            message.matches(Regex("(?i)my name is .+")) -> message
            message.matches(Regex("(?i)i live in .+")) -> message
            message.matches(Regex("(?i)i work as .+")) -> message
            message.matches(Regex("(?i)my favorite .+ is .+")) -> message
            else -> null
        } ?: return
        if (memoryStore.remember(fact)) {
            runJs("updateMemoryLabel(${memoryStore.all().size})")
        }
    }

    private fun toggleFullStorageAccess() {
        val fileManager = JarvisFileManager(this)
        if (fileManager.isFullAccessEnabled()) {
            fileManager.setFullAccessEnabled(false)
            runJs("setFullStorageLabel(false);showToast('FULL STORAGE ACCESS OFF')")
            return
        }
        if (fileManager.fullAccessGranted()) {
            fileManager.setFullAccessEnabled(true)
            runJs("setFullStorageLabel(true);showToast('FULL STORAGE ACCESS ON')")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Enable full storage access?")
            .setMessage("This lets JARVIS list, create, edit, and delete shared-storage files. Android will ask you to approve this in Settings.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("OPEN SETTINGS") { _, _ ->
                runJs("showToast(${JSONObject.quote(fileManager.openFullAccessSettings().second)})")
            }
            .show()
    }

    private fun saveUiPreference(key: String, enabled: Boolean) {
        getSharedPreferences("jarvis_ui", MODE_PRIVATE).edit().putBoolean(key, enabled).apply()
    }

    private fun runJs(script: String) {
        if (!::binding.isInitialized) return
        runOnUiThread { binding.jarvisWebView.evaluateJavascript("(function(){try{$script}catch(e){}})();", null) }
    }

    override fun onResume() {
        super.onResume()
        runJs("applyNativeSettings()")
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(JarvisVoiceForegroundService.ACTION_STATUS)
            addAction(JarvisVoiceForegroundService.ACTION_USER_SPEECH)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(voiceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(voiceReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == JarvisFileManager.ACTION_CHOOSE_FOLDER) storageFolderLauncher.launch(null)
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(voiceReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        binding.jarvisWebView.removeJavascriptInterface("AndroidJarvis")
        binding.jarvisWebView.destroy()
        super.onDestroy()
    }
}
