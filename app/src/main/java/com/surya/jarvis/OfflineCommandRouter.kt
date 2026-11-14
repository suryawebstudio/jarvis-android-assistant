package com.surya.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Small deterministic intent parser for commands that do not need Gemini.
 * Unknown messages return null and continue through the normal online path.
 */
class OfflineCommandRouter(private val context: Context) {
    data class Result(val reply: String, val action: (() -> Unit)? = null)

    fun route(rawMessage: String): Result? {
        val message = rawMessage.trim()
        if (message.isBlank()) return null
        val normalized = message.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

        return when {
            normalized in setOf("help", "what can you do", "what can you do offline") -> Result(
                "Offline mode can handle help, time, date, status, flashlight, Wi-Fi, Bluetooth, Android Settings, Accessibility, and opening installed apps. I will use Gemini for other requests."
            )
            normalized.contains("what time") || normalized == "time" || normalized.contains("current time") -> Result(
                "It is ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}."
            )
            normalized.contains("what date") || normalized == "date" || normalized.contains("today's date") -> Result(
                "Today is ${DateFormat.getDateInstance(DateFormat.LONG).format(Date())}."
            )
            normalized in setOf("who are you", "what is your name", "identify yourself") -> Result(
                "I am MARK, your Android assistant."
            )
            normalized in setOf("status", "system status", "are you online") -> Result(
                "Offline command mode is ready. Gemini remains available for advanced requests."
            )
            normalized.matches(Regex("(turn|switch|toggle) (the )?(flashlight|torch) (on|off)")) -> {
                val enabled = normalized.endsWith(" on")
                Result(flashlightReply(enabled)) { setFlashlight(enabled) }
            }
            normalized.matches(Regex("(open|show|go to) (wi[- ]?fi|wifi)")) ->
                Result("Opening Wi-Fi controls.") { openSettingsPage("wifi") }
            normalized.matches(Regex("(open|show|go to) bluetooth")) ->
                Result("Opening Bluetooth settings.") { openSettingsPage("bluetooth") }
            normalized.matches(Regex("(open|show|go to) (android )?settings")) ->
                Result("Opening Android Settings.") { openSettingsPage("general") }
            normalized.matches(Regex("(open|show|go to) accessibility( settings)?")) ->
                Result("Opening Accessibility settings.") { openSettingsPage("accessibility") }
            normalized.startsWith("open app ") || normalized.startsWith("launch app ") -> {
                val name = message.substringAfter(' ', "").substringAfter(' ', "").trim()
                openAppResult(name)
            }
            normalized.startsWith("open ") || normalized.startsWith("launch ") -> {
                val name = message.substringAfter(' ').trim()
                if (name in setOf("wifi", "wi-fi", "bluetooth", "settings", "accessibility")) null else openAppResult(name)
            }
            else -> null
        }
    }

    private fun flashlightReply(enabled: Boolean): String = if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
    ) {
        "Camera permission is required before I can control the flashlight. Enable it in MARK permissions, then try again."
    } else if (findFlashCamera() == null) {
        "This phone does not expose a controllable flashlight."
    } else {
        if (enabled) "Turning the flashlight on." else "Turning the flashlight off."
    }

    private fun setFlashlight(enabled: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        runCatching { findFlashCamera()?.let { cameraId ->
            (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(cameraId, enabled)
        } }
    }

    private fun findFlashCamera(): String? = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    private fun openSettingsPage(page: String) {
        val action = when (page) {
            "wifi" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openAppResult(name: String): Result {
        val target = normalize(name)
        if (target.isBlank()) return Result("Tell me which app to open.")
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(launcherIntent, 0).firstOrNull {
            normalize(it.loadLabel(packageManager).toString()) == target ||
                normalize(it.activityInfo.packageName) == target
        }
        if (match == null) return Result("I could not find an installed app named $name.")
        val launchIntent = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return Result("That app does not provide a launch screen.")
        val label = match.loadLabel(packageManager).toString()
        return Result("Opening $label.") { context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
