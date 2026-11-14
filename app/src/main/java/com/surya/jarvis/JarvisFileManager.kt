package com.surya.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class JarvisFileManager(private val context: Context) {
    companion object {
        private const val PREFS = "jarvis_storage"
        private const val KEY_FULL_ACCESS_ENABLED = "full_access_enabled"
        private const val MAX_READ_BYTES = 512 * 1024
    }

    fun isFullAccessEnabled(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_FULL_ACCESS_ENABLED, false) &&
            Environment.isExternalStorageManager()

    fun setFullAccessEnabled(enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FULL_ACCESS_ENABLED, enabled)
            .apply()
    }

    fun fullAccessGranted(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun openFullAccessSettings(): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false to "Full storage access requires Android 11 or newer."
        }
        return try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true to "Enable Allow access to manage all files for MARK, then return here."
        } catch (_: Exception) {
            false to "Android could not open the full storage access settings."
        }
    }

    fun listFiles(): Pair<Boolean, String> {
        if (!isFullAccessEnabled()) return false to "Enable Full storage access for MARK in Android Settings first."
        val entries = sharedStorageRoot()?.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?: return false to "Shared storage is not available."
        if (entries.isEmpty()) return true to "Shared storage is empty."
        val description = entries.take(100).joinToString(" | ") { file ->
            "${file.name} (${if (file.isDirectory) "folder" else "file"})"
        }
        val suffix = if (entries.size > 100) " | More files are present." else ""
        return true to "Top-level shared-storage entries: $description$suffix"
    }

    fun shareableFilesJson(): String {
        val files = JSONArray()
        if (!isFullAccessEnabled()) return files.toString()
        sharedStorageRoot()?.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?.take(100)
            ?.forEach { files.put(JSONObject().put("name", it.name)) }
        return files.toString()
    }

    fun shareFile(name: String): Pair<Boolean, String> {
        val safeName = validateName(name) ?: return false to "That file name is not allowed."
        if (!isFullAccessEnabled()) return false to "Enable Full storage access for MARK in Android Settings first."
        return try {
            val file = File(sharedStorageRoot() ?: return false to "Shared storage is not available.", safeName)
            if (!file.isFile) return false to "I could not find $safeName in shared storage."
            val shareDir = File(context.cacheDir, "shared-files").apply { mkdirs() }
            val cached = File(shareDir, safeName)
            file.inputStream().use { input -> cached.outputStream().use { output -> input.copyTo(output) } }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cached)

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType(safeName)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Send $safeName").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true to "Choose an app to send $safeName."
        } catch (_: Exception) {
            false to "Android could not prepare $safeName for sharing."
        }
    }

    fun createTextFile(name: String, content: String): Pair<Boolean, String> {
        val safeName = validateName(name) ?: return false to "Use a simple file name without path separators."
        if (!isFullAccessEnabled()) return false to "Enable Full storage access for MARK in Android Settings first."
        val root = sharedStorageRoot() ?: return false to "Shared storage is not available."
        val file = File(root, safeName)
        if (file.exists()) return false to "A file or folder named $safeName already exists."
        if (!writeFile(file, content)) return false to "Android could not create $safeName."
        return true to "Created $safeName in shared storage."
    }

    fun editTextFile(name: String, content: String): Pair<Boolean, String> {
        val safeName = validateName(name) ?: return false to "Use a simple file name without path separators."
        if (!isFullAccessEnabled()) return false to "Enable Full storage access for MARK in Android Settings first."
        val root = sharedStorageRoot() ?: return false to "Shared storage is not available."
        val file = File(root, safeName)
        if (!file.isFile) return false to "$safeName is not a regular file."
        if (!isTextFile(file)) return false to "$safeName does not look like a text file."
        if (!writeFile(file, content)) return false to "Android could not write $safeName."
        return true to "Updated $safeName in shared storage."
    }

    fun deleteFile(name: String): Pair<Boolean, String> {
        val safeName = validateName(name) ?: return false to "Use a file name without path separators."
        if (!isFullAccessEnabled()) return false to "Enable Full storage access for MARK in Android Settings first."
        val root = sharedStorageRoot() ?: return false to "Shared storage is not available."
        val file = File(root, safeName)
        if (!file.exists()) return false to "I could not find $safeName in shared storage."
        return if (file.delete()) true to "Deleted $safeName from shared storage."
        else false to "Android could not delete $safeName. Empty folders and files can be deleted."
    }

    private fun sharedStorageRoot(): File? =
        if (fullAccessGranted()) Environment.getExternalStorageDirectory() else null

    private fun writeFile(file: File, content: String): Boolean {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_READ_BYTES) return false
        return try {
            file.outputStream().use { it.write(bytes) }
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun isTextFile(file: File): Boolean =
        file.extension.lowercase(Locale.ROOT) in textExtensions

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "txt", "md", "log", "kt", "java", "html", "css", "js" -> "text/plain"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "xml" -> "application/xml"
        else -> "application/octet-stream"
    }

    private fun validateName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isBlank() || trimmed == "." || trimmed == "..") return null
        if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.length > 180) return null
        if (trimmed.any { it.isISOControl() }) return null
        return trimmed
    }

    private val textExtensions = setOf(
        "txt", "md", "json", "csv", "xml", "kt", "java", "log", "html", "css", "js"
    )
}
