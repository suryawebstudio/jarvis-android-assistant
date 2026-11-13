package com.surya.jarvis
import android.Manifest
import android.content.Context
import android.content.Intent
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
import android.provider.Settings
import android.speech.tts.TextToSpeech
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
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale

class LiveVoiceManager(
    private val context: Context,
    private val onUserSpeech: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {

    private data class PlaybackChunk(
        val generation: Int,
        val bytes: ByteArray
    )

    companion object {
        private const val WORKER_URL =
            "https://jarvis-ai-backend.suryawebstudio.workers.dev"

        private const val LIVE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"

        private const val MODEL =
            "models/gemini-3.1-flash-live-preview"

        private const val INPUT_RATE = 16000
        private const val OUTPUT_RATE = 24000
        private const val MAX_TASK_STEPS = 24
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private val playbackGeneration = AtomicInteger(0)
    private val playbackQueue = LinkedBlockingDeque<PlaybackChunk>(16)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var taskActive = false
    private var taskStepCount = 0
    private var awaitingTaskConfirmation = false

    private var textToSpeech: TextToSpeech? = null
    @Volatile
    private var speechReady = false

    private val fileManager = JarvisFileManager(context.applicationContext)

    init {
        textToSpeech = TextToSpeech(context.applicationContext) { result ->
            speechReady = result == TextToSpeech.SUCCESS
            if (speechReady) {
                textToSpeech?.language = Locale.getDefault()
            }
        }
    }

    @Volatile
    private var setupComplete = false

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var audioTrack: AudioTrack? = null
    private var recorderThread: Thread? = null
    @Volatile
    private var playbackThread: Thread? = null

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

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
                                            "You are speaking directly with the user in a live voice conversation. " +
                                            "You may open an installed app or a listed Android Settings page only when the user directly asks. " +
                                            "When JARVIS Accessibility is enabled, you may inspect and control only visible UI elements using the provided tools. " +
                                            "For a multi-step request, first call start_task, then execute it as a task: perform one action at a time, inspect the screen after opening or changing screens, then continue with the next step. Do not stop after the first successful action unless the task is complete or a confirmation is required. " +
                                            "Use inspect_visible_screen before guessing a target. Never claim an action succeeded unless its function response says it did. " +
                                            "For sending, deleting, purchasing, calling, sharing, posting, installing, or other consequential actions, ask the user for confirmation first and then repeat the blocked action with confirmed=true only after the user explicitly agrees. " +
                                            "For file work, use choose_storage_folder before accessing files if no approved folder exists, then use list_files, create_text_file, or edit_text_file. File names must be simple names in the approved folder; do not invent paths or claim access to all phone storage. Before delete_file, say exactly which file or folder will be permanently deleted and wait for an explicit yes, then call delete_file with confirmed=true. " +
                                            "When a tool response says confirmation is required, the Android app has already spoken the confirmation prompt; do not repeat a long confirmation speech, and wait for the user's answer. " +
                                            "If the user says stop, cancel, or abort, call cancel_task immediately. " +
                                            "Never bypass passwords, biometrics, CAPTCHAs, Android permission dialogs, or security screens."
                                        )
                                    )
                                )
                            }
                        )

                        put("sessionResumption", JSONObject())
                        put("tools", phoneControlTools())
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
                    startPlayback()
                    startAudioCapture()
                    return
                }

                val toolCall = json.optJSONObject("toolCall")
                if (toolCall != null) {
                    handlePhoneToolCall(webSocket, toolCall)
                    return
                }

                val serverContent =
                    json.optJSONObject("serverContent") ?: return

                if (serverContent.optBoolean("interrupted")) {
                    interruptPlayback()
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

    private fun phoneControlTools(): JSONArray {
        val launchApp = JSONObject().apply {
            put("name", "launch_app")
            put(
                "description",
                "Open an installed Android app by its visible app name. Only launch the app; do not perform actions inside it."
            )
            put(
                "parameters",
                JSONObject().apply {
                    put("type", "OBJECT")
                    put(
                        "properties",
                        JSONObject().put(
                            "app_name",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "The installed app's visible name, such as Chrome or Maps.")
                        )
                    )
                    put("required", JSONArray().put("app_name"))
                }
            )
        }

        val openSettings = JSONObject().apply {
            put("name", "open_settings_page")
            put(
                "description",
                "Open one of the supported Android Settings screens. This only opens the screen; it does not change settings."
            )
            put(
                "parameters",
                JSONObject().apply {
                    put("type", "OBJECT")
                    put(
                        "properties",
                        JSONObject().put(
                            "page",
                            JSONObject()
                                .put("type", "STRING")
                                .put("enum", JSONArray().put("general").put("wifi").put("bluetooth").put("display").put("sound").put("apps").put("location"))
                                .put("description", "Choose one supported page: general, wifi, bluetooth, display, sound, apps, or location.")
                        )
                    )
                    put("required", JSONArray().put("page"))
                }
            )
        }

        val inspectScreen = JSONObject().apply {
            put("name", "inspect_visible_screen")
            put(
                "description",
                "Read accessible labels and text currently visible on the foreground screen. Use this before interacting when the target is unclear."
            )
            put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        }

        val controlScreen = JSONObject().apply {
            put("name", "control_visible_screen")
            put(
                "description",
                "Perform one narrow action on the current visible screen: tap a visible control, type into a visible text field, scroll, or use Back, Home, or recent apps. Accessibility must be enabled by the user."
            )
            put(
                "parameters",
                JSONObject().apply {
                    put("type", "OBJECT")
                    put(
                        "properties",
                        JSONObject()
                            .put("action", JSONObject().put("type", "STRING").put("enum", JSONArray().put("tap").put("type").put("scroll").put("back").put("home").put("overview")))
                            .put("target", JSONObject().put("type", "STRING").put("description", "Exact or partial visible button, link, label, or text-field label. Leave blank for navigation actions or the focused field."))
                            .put("text", JSONObject().put("type", "STRING").put("description", "Text to type when action is type."))
                            .put("direction", JSONObject().put("type", "STRING").put("enum", JSONArray().put("up").put("down")))
                            .put("confirmed", JSONObject().put("type", "BOOLEAN").put("description", "Set true only after the user explicitly confirms a consequential action."))
                    )
                    put("required", JSONArray().put("action"))
                }
            )
        }

        val cancelTask = JSONObject().apply {
            put("name", "cancel_task")
            put("description", "Stop the current multi-step JARVIS task and clear its task state. Use when the user says stop, cancel, or abort.")
            put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        }

        val startTask = JSONObject().apply {
            put("name", "start_task")
            put("description", "Start or reset a new multi-step JARVIS task before executing a complex user request.")
            put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        }

        val chooseStorageFolder = JSONObject().apply {
            put("name", "choose_storage_folder")
            put("description", "Open Android's folder picker so the user can choose the folder where JARVIS may manage files. The user must choose the folder manually.")
            put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        }

        val listFiles = JSONObject().apply {
            put("name", "list_files")
            put("description", "List files and folders in the user-approved JARVIS storage folder.")
            put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        }

        val createTextFile = JSONObject().apply {
            put("name", "create_text_file")
            put("description", "Create a new UTF-8 text file in the user-approved JARVIS storage folder.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject()
                    .put("name", JSONObject().put("type", "STRING").put("description", "File name only, without folders."))
                    .put("content", JSONObject().put("type", "STRING").put("description", "Complete text content.")))
                put("required", JSONArray().put("name").put("content"))
            })
        }

        val editTextFile = JSONObject().apply {
            put("name", "edit_text_file")
            put("description", "Replace the complete UTF-8 text content of an existing file in the user-approved JARVIS storage folder.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject()
                    .put("name", JSONObject().put("type", "STRING").put("description", "Existing file name only, without folders."))
                    .put("content", JSONObject().put("type", "STRING").put("description", "New complete text content.")))
                put("required", JSONArray().put("name").put("content"))
            })
        }

        val deleteFile = JSONObject().apply {
            put("name", "delete_file")
            put("description", "Permanently delete one file or folder from the user-approved JARVIS storage folder. Always ask the user to confirm aloud before calling this with confirmed=true.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject()
                    .put("name", JSONObject().put("type", "STRING").put("description", "Exact file or folder name only, without folders."))
                    .put("confirmed", JSONObject().put("type", "BOOLEAN").put("description", "True only after the user explicitly confirms the exact deletion.")))
                put("required", JSONArray().put("name").put("confirmed"))
            })
        }

        return JSONArray().put(
            JSONObject().put(
                "functionDeclarations",
                JSONArray().put(launchApp).put(openSettings).put(inspectScreen).put(controlScreen).put(startTask).put(cancelTask).put(chooseStorageFolder).put(listFiles).put(createTextFile).put(editTextFile).put(deleteFile)
            )
        )
    }

    private fun handlePhoneToolCall(webSocket: WebSocket, toolCall: JSONObject) {
        val calls = toolCall.optJSONArray("functionCalls") ?: return
        if (calls.length() == 0) return

        mainHandler.post {
            val functionResponses = JSONArray()

            for (index in 0 until calls.length()) {
                val call = calls.optJSONObject(index) ?: continue
                val name = call.optString("name")
                val id = call.optString("id")
                val args = call.optJSONObject("args") ?: JSONObject()
                val outcome = executeTaskTool(name, args)

                val functionResponse = JSONObject().apply {
                    if (id.isNotBlank()) put("id", id)
                    put("name", name)
                    put(
                        "response",
                        JSONObject()
                            .put("success", outcome.first)
                            .put("message", outcome.second)
                    )
                }
                functionResponses.put(functionResponse)
            }

            if (functionResponses.length() > 0) {
                val responseMessage = JSONObject().put(
                    "toolResponse",
                    JSONObject().put("functionResponses", functionResponses)
                )
                if (!webSocket.send(responseMessage.toString())) {
                    status("PHONE ACTION RESPONSE COULD NOT BE SENT")
                }
            }
        }
    }

    private fun executeTaskTool(name: String, args: JSONObject): Pair<Boolean, String> {
        if (name == "start_task") {
            clearTaskState()
            taskActive = true
            status("TASK STARTED")
            return true to "A new multi-step task has started."
        }

        if (name == "cancel_task") {
            clearTaskState()
            status("TASK CANCELLED")
            return true to "The current JARVIS task was cancelled."
        }

        if (name == "inspect_visible_screen") {
            return executePhoneTool(name, args)
        }

        if (!isTaskAction(name)) {
            return executePhoneTool(name, args)
        }

        if (!taskActive) {
            taskStepCount = 0
            awaitingTaskConfirmation = false
            taskActive = true
        }
        if (taskStepCount >= MAX_TASK_STEPS) {
            clearTaskState()
            status("TASK STOPPED — TOO MANY STEPS")
            return false to "The task was stopped after $MAX_TASK_STEPS steps to prevent an automation loop."
        }

        val confirmed = args.optBoolean("confirmed", false)
        if (confirmed && !awaitingTaskConfirmation) {
            return false to "Confirmation was not requested for a pending sensitive action. Ask the user before retrying."
        }

        if (isPotentiallySensitive(name, args) && !confirmed) {
            taskStepCount += 1
            awaitingTaskConfirmation = true
            status("CONFIRMATION REQUIRED")
            if (name == "delete_file") {
                speakConfirmationRequest("This will permanently delete ${args.optString("name")}. Say yes to confirm, or say stop to cancel.")
            } else {
                speakConfirmationRequest()
            }
            return false to "Confirmation required before this action. Ask the user to confirm, then repeat the same tool call with confirmed=true."
        }

        taskStepCount += 1
        status("TASK STEP $taskStepCount/$MAX_TASK_STEPS")
        val outcome = executePhoneTool(name, args)

        if (outcome.first && confirmed) {
            awaitingTaskConfirmation = false
        }

        if (!outcome.first && !isPotentiallySensitive(name, args)) {
            status("TASK STEP FAILED")
        }
        return outcome
    }

    private fun isTaskAction(name: String): Boolean =
        name == "launch_app" || name == "open_settings_page" || name == "control_visible_screen" ||
            name == "choose_storage_folder" || name == "list_files" || name == "create_text_file" ||
            name == "edit_text_file" || name == "delete_file"

    private fun isPotentiallySensitive(name: String, args: JSONObject): Boolean {
        if (name == "delete_file") return true
        if (name != "control_visible_screen") return false
        val value = listOf(
            args.optString("action"),
            args.optString("target"),
            args.optString("text")
        ).joinToString(" ").lowercase(Locale.ROOT)
        return listOf(
            "send", "submit", "purchase", "buy", "pay", "delete", "remove",
            "call", "share", "post", "publish", "transfer", "confirm", "install"
        ).any(value::contains)
    }

    private fun clearTaskState() {
        taskActive = false
        taskStepCount = 0
        awaitingTaskConfirmation = false
    }

    private fun speakConfirmationRequest(
        prompt: String = "This action may send, delete, purchase, call, or share something. Please say yes to confirm, or say stop to cancel."
    ) {
        mainHandler.post {
            if (!speechReady) return@post
            textToSpeech?.speak(
                prompt,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "jarvis-confirmation"
            )
        }
    }

    private fun executePhoneTool(name: String, args: JSONObject): Pair<Boolean, String> {
        return try {
            when (name) {
                "launch_app" -> launchInstalledApp(args.optString("app_name"))
                "open_settings_page" -> openSettingsPage(args.optString("page"))
                "choose_storage_folder" -> fileManager.openFolderPicker()
                "list_files" -> fileManager.listFiles()
                "create_text_file" -> fileManager.createTextFile(
                    args.optString("name"),
                    args.optString("content")
                )
                "edit_text_file" -> fileManager.editTextFile(
                    args.optString("name"),
                    args.optString("content")
                )
                "delete_file" -> fileManager.deleteFile(args.optString("name"))
                "inspect_visible_screen" -> if (JarvisAccessibilityService.isEnabled()) {
                    true to JarvisAccessibilityService.inspectScreen()
                } else {
                    false to "Accessibility control is not enabled. Enable JARVIS in Android Accessibility settings first."
                }
                "control_visible_screen" -> JarvisAccessibilityService.control(
                    action = args.optString("action"),
                    target = args.optString("target"),
                    text = args.optString("text"),
                    direction = args.optString("direction"),
                    confirmed = args.optBoolean("confirmed", false)
                )
                else -> false to "This phone action is not supported."
            }
        } catch (_: Exception) {
            false to "Android could not open that app or Settings page."
        }
    }

    private fun launchInstalledApp(requestedName: String): Pair<Boolean, String> {
        val target = normalizeAppName(requestedName)
        if (target.isBlank()) return false to "Please specify an app name."

        val packageManager = context.packageManager
        val launcherQuery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(launcherQuery, 0)
            .firstOrNull { resolveInfo ->
                val visibleName = resolveInfo.loadLabel(packageManager).toString()
                normalizeAppName(visibleName) == target ||
                    normalizeAppName(resolveInfo.activityInfo.packageName) == target
            }
            ?: return false to "I couldn't find an installed app named $requestedName."

        val packageName = match.activityInfo.packageName
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return false to "That app does not provide a launch screen."

        context.startActivity(launchIntent)
        status("OPENING ${match.loadLabel(packageManager).toString().uppercase(Locale.ROOT)}")
        return true to "Opened ${match.loadLabel(packageManager)}."
    }

    private fun openSettingsPage(page: String): Pair<Boolean, String> {
        val action = when (page.lowercase(Locale.ROOT)) {
            "general" -> Settings.ACTION_SETTINGS
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> return false to "That Settings page is not supported."
        }

        val intent = Intent(action)
        if (intent.resolveActivity(context.packageManager) == null) {
            return false to "That Settings page is not available on this device."
        }

        context.startActivity(intent)
        status("OPENING SETTINGS")
        return true to "Opened the $page Settings page."
    }

    private fun normalizeAppName(name: String): String =
        name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

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

            // Keep output on Android's normal media route so volume buttons
            // control JARVIS playback with the media/music volume stream.
            audioManager.mode = AudioManager.MODE_NORMAL

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

    private fun startPlayback() {
        if (playbackThread?.isAlive == true) return

        playbackThread = Thread({
            var track: AudioTrack? = null
            try {
                val minBuffer = AudioTrack.getMinBufferSize(
                    OUTPUT_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (minBuffer <= 0) {
                    throw IOException("Invalid speaker buffer size")
                }

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(OUTPUT_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer, 9600))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    throw IOException("Speaker initialization failed")
                }

                audioTrack = track
                track.play()

                while (running.get() && setupComplete) {
                    val chunk = playbackQueue.take()
                    if (chunk.generation != playbackGeneration.get()) continue

                    var offset = 0
                    while (
                        offset < chunk.bytes.size &&
                        running.get() &&
                        setupComplete &&
                        chunk.generation == playbackGeneration.get()
                    ) {
                        val writeSize = minOf(1024, chunk.bytes.size - offset)
                        val written = track.write(
                            chunk.bytes,
                            offset,
                            writeSize,
                            AudioTrack.WRITE_NON_BLOCKING
                        )

                        if (written > 0) {
                            offset += written
                        } else if (written == 0) {
                            Thread.sleep(4)
                        } else {
                            throw IOException("Speaker playback failed")
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                if (running.get() && setupComplete) {
                    status("SPEAKER PLAYBACK ERROR")
                }
            } finally {
                if (audioTrack === track) audioTrack = null
                try {
                    track?.release()
                } catch (_: Exception) {
                }
            }
        }, "Jarvis-Audio-Playback").apply {
            isDaemon = true
            start()
        }
    }

    private fun playAudio(bytes: ByteArray) {
        if (bytes.isEmpty() || !setupComplete) return

        val chunk = PlaybackChunk(playbackGeneration.get(), bytes)
        if (!playbackQueue.offerLast(chunk)) {
            // Keep latency bounded if output arrives faster than the speaker drains it.
            playbackQueue.pollFirst()
            playbackQueue.offerLast(chunk)
        }
    }

    private fun interruptPlayback() {
        playbackGeneration.incrementAndGet()
        playbackQueue.clear()

        try {
            audioTrack?.let { track ->
                track.pause()
                track.flush()
                if (running.get() && setupComplete) track.play()
            }
        } catch (_: Exception) {
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
        clearTaskState()
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
        clearTaskState()

        try {
            recorderThread?.interrupt()
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord = null
        recorderThread = null

        playbackGeneration.incrementAndGet()
        playbackQueue.clear()
        playbackThread?.interrupt()
        playbackThread = null

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null

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
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {
        }
        textToSpeech = null
        speechReady = false
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
