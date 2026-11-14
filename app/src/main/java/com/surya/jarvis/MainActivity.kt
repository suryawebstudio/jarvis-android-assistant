package com.surya.jarvis

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
    private var pendingWakeWordStart = false
    private var receiverRegistered = false
    private val workerUrl = "https://jarvis-ai-backend.suryawebstudio.workers.dev"
    private val memoryStore by lazy { JarvisMemoryStore(applicationContext) }
    private val offlineCommandRouter by lazy { OfflineCommandRouter(applicationContext) }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            pendingWakeWordStart = false
            runJs("document.getElementById('wakeWord').checked=false;showToast('MICROPHONE PERMISSION DENIED')")
        } else if (pendingWakeWordStart) {
            pendingWakeWordStart = false
            requestNotificationThenStartWakeWord()
        } else {
            requestNotificationThenStartVoice()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingWakeWordStart) {
            pendingWakeWordStart = false
            startWakeWordListening()
        } else if (granted) {
            beginVoiceConversation()
        } else {
            runJs("showToast('NOTIFICATION PERMISSION REQUIRED FOR BACKGROUND VOICE')")
        }
    }

    private val defaultAssistantLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        runJs("updateDefaultAssistantLabel();showToast(${JSONObject.quote(if (isDefaultAssistant()) "MARK IS DEFAULT ASSISTANT" else "DEFAULT ASSISTANT NOT CHANGED")})")
    }

    private var pendingAssistantPermission = ""
    private val assistantPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val key = pendingAssistantPermission
        pendingAssistantPermission = ""
        runJs("updateAssistantPermissionState();showToast(${JSONObject.quote(if (isAssistantPermissionGranted(key)) "PERMISSION ENABLED" else "PERMISSION NOT FULLY ENABLED")})")
    }

    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                JarvisVoiceForegroundService.ACTION_STATUS -> {
                    val status = intent.getStringExtra(JarvisVoiceForegroundService.EXTRA_STATUS).orEmpty()
                    when {
                        status == "MARK LIVE — LISTENING" -> runJs("setMode('listening')")
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
        fun setGestureWake(enabled: Boolean) = runOnUiThread { setGestureWakeEnabled(enabled) }

        @JavascriptInterface
        fun setWakeWord(enabled: Boolean) = runOnUiThread { setWakeWordEnabled(enabled) }

        @JavascriptInterface
        fun getUiState(): String {
            val preferences = getSharedPreferences("jarvis_ui", MODE_PRIVATE)
            return JSONObject()
                .put("voiceResponses", preferences.getBoolean("voice_responses", true))
                .put("soundEffects", preferences.getBoolean("sound_effects", true))
                .put("particleEffects", preferences.getBoolean("particle_effects", true))
                .put("autoListening", preferences.getBoolean("auto_listening", false))
                .put("gestureWake", preferences.getBoolean("gesture_wake", false))
                .put("wakeWord", preferences.getBoolean("wake_word", false))
                .put("fullStorage", JarvisFileManager(this@MainActivity).isFullAccessEnabled())
                .put("memoryCount", memoryStore.all().size)
                .put("defaultAssistant", isDefaultAssistant())
                .put("assistantPermissions", assistantPermissionState())
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

        @JavascriptInterface
        fun requestDefaultAssistant() = runOnUiThread { requestDefaultAssistantRole() }

        @JavascriptInterface
        fun getAssistantPermissionState(): String = assistantPermissionState().toString()

        @JavascriptInterface
        fun requestAssistantPermission(key: String) = runOnUiThread { requestAssistantPermissionInternal(key) }
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

    private fun setGestureWakeEnabled(enabled: Boolean) {
        saveUiPreference("gesture_wake", enabled)
        if (enabled) {
            runCatching { JarvisGestureWakeService.start(this) }
                .onFailure {
                    saveUiPreference("gesture_wake", false)
                    runJs("document.getElementById('gestureWake').checked=false;showToast('SHAKE WAKE COULD NOT START')")
                }
            runJs("showToast('SHAKE WAKE ENABLED — KEEP MARK READY')")
        } else {
            JarvisGestureWakeService.stop(this)
            runJs("showToast('SHAKE WAKE DISABLED')")
        }
    }

    private fun setWakeWordEnabled(enabled: Boolean) {
        if (enabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            saveUiPreference("wake_word", false)
            pendingWakeWordStart = true
            runJs("showToast('ALLOW MICROPHONE ACCESS TO ENABLE MARK WAKE WORD')")
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (enabled) {
            requestNotificationThenStartWakeWord()
        } else {
            saveUiPreference("wake_word", false)
            pendingWakeWordStart = false
            JarvisWakeWordService.stop(this)
            runJs("showToast('WAKE WORD DISABLED')")
        }
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

    private fun requestNotificationThenStartWakeWord() {
        pendingWakeWordStart = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            pendingWakeWordStart = false
            startWakeWordListening()
        }
    }

    private fun startWakeWordListening() {
        saveUiPreference("wake_word", true)
        runCatching { JarvisWakeWordService.start(this) }
            .onFailure {
                saveUiPreference("wake_word", false)
                runJs("document.getElementById('wakeWord').checked=false;showToast('WAKE WORD COULD NOT START')")
            }
        runJs("showToast('WAKE WORD ENABLED — SAY MARK')")
    }

    private fun isDefaultAssistant(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true

    private fun requestDefaultAssistantRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                .onFailure { runJs("showToast('OPEN DEFAULT ASSISTANT SETTINGS MANUALLY')") }
            return
        }
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
            runJs("showToast('THIS PHONE DOES NOT EXPOSE THE ASSISTANT ROLE')")
            return
        }
        if (roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            runJs("showToast('MARK IS ALREADY THE DEFAULT ASSISTANT')")
            return
        }
        defaultAssistantLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
    }

    private fun permissionGroups(): Map<String, Array<String>> = mapOf(
        "contacts" to arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
        "calls" to arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE),
        "sms" to arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS),
        "calendar" to arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        "bluetooth" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE
        ) else emptyArray(),
        "callLog" to arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG),
        "media" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    )

    private fun isAssistantPermissionGranted(key: String): Boolean {
        if (key == "notifications") {
            val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
            val component = ComponentName(this, JarvisNotificationListenerService::class.java).flattenToString()
            return enabled.split(':').contains(component)
        }
        if (key == "alarms") {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() == true
        }
        val permissions = permissionGroups()[key] ?: return false
        return permissions.isNotEmpty() && permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun assistantPermissionState(): JSONObject = JSONObject().apply {
        listOf("contacts", "calls", "sms", "calendar", "bluetooth", "notifications", "callLog", "media", "alarms").forEach {
            put(it, isAssistantPermissionGranted(it))
        }
    }

    private fun requestAssistantPermissionInternal(key: String) {
        when (key) {
            "notifications" -> runCatching {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }.onFailure { runJs("showToast('OPEN NOTIFICATION ACCESS SETTINGS MANUALLY')") }
            "alarms" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:$packageName")))
                }.onFailure { runJs("showToast('OPEN ALARM ACCESS SETTINGS MANUALLY')") }
            } else runJs("showToast('EXACT ALARMS ARE AVAILABLE ON THIS ANDROID VERSION')")
            else -> {
                val permissions = permissionGroups()[key]
                if (permissions == null) {
                    runJs("showToast('UNKNOWN ASSISTANT PERMISSION')")
                    return
                }
                val missing = permissions.filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }.toTypedArray()
                if (missing.isEmpty()) {
                    runJs("showToast('PERMISSION ALREADY ENABLED')")
                } else {
                    pendingAssistantPermission = key
                    assistantPermissionLauncher.launch(missing)
                }
            }
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
        val offlineCommand = offlineCommandRouter.route(cleanMessage)
        if (offlineCommand != null) {
            runOnUiThread {
                offlineCommand.action?.invoke()
                runJs("jarvisSpeak(${JSONObject.quote(offlineCommand.reply)});setMode('idle')")
            }
            return
        }
        val memoryContext = memoryStore.all().joinToString("\n") { "- $it" }
        val prompt = if (memoryContext.isBlank()) {
            cleanMessage
        } else {
            "[PRIVATE MARK MEMORY]\n$memoryContext\n" +
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
            .setMessage("This lets MARK list, create, edit, and delete shared-storage files. Android will ask you to approve this in Settings.")
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
        runJs("applyNativeSettings();updateAssistantPermissionState()")
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
