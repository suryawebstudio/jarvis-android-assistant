package com.surya.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class JarvisFileManager(private val context: Context) {
    companion object {
        const val ACTION_CHOOSE_FOLDER = "com.surya.jarvis.action.CHOOSE_STORAGE_FOLDER"
        private const val PREFS = "jarvis_storage"
        private const val KEY_TREE_URI = "tree_uri"
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
            true to "Enable Allow access to manage all files for JARVIS, then return here."
        } catch (_: Exception) {
            false to "Android could not open the full storage access settings."
        }
    }

    fun openFolderPicker(): Pair<Boolean, String> {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_CHOOSE_FOLDER
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return try {
            context.startActivity(intent)
            true to "Choose the folder where JARVIS may manage files, then return to JARVIS."
        } catch (_: Exception) {
            false to "Android could not open the folder picker."
        }
    }

    fun saveFolder(uri: Uri): Pair<Boolean, String> {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TREE_URI, uri.toString())
                .apply()
            true to "JARVIS can now manage files inside the selected folder."
        } catch (_: SecurityException) {
            false to "Android did not grant persistent access to that folder."
        }
    }

    fun listFiles(): Pair<Boolean, String> {
        if (isFullAccessEnabled()) {
            val entries = sharedStorageRoot()?.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }
                ?: return false to "Shared storage is not available."
            if (entries.isEmpty()) return true to "Shared storage is empty."
            val description = entries.take(100).joinToString(" | ") { file ->
                "${file.name} (${if (file.isDirectory) "folder" else "file"})"
            }
            val suffix = if (entries.size > 100) " | More files are present." else ""
            return true to "Top-level shared-storage entries: $description$suffix"
        }

        val root = getDocumentRoot() ?: return false to "Choose a storage folder first."
        val entries = root.listFiles().sortedBy { it.name.orEmpty().lowercase(Locale.ROOT) }
        if (entries.isEmpty()) return true to "The selected folder is empty."
        val description = entries.take(100).joinToString(" | ") { file ->
            val kind = if (file.isDirectory) "folder" else "file"
            "${file.name.orEmpty()} ($kind)"
        }
        val suffix = if (entries.size > 100) " | More files are present." else ""
        return true to "Files in the selected folder: $description$suffix"
    }

    fun shareableFilesJson(): String {
        val files = JSONArray()
        if (isFullAccessEnabled()) {
            sharedStorageRoot()?.listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                ?.take(100)
                ?.forEach { files.put(JSONObject().put("name", it.name)) }
            return files.toString()
        }

        getDocumentRoot()?.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name.orEmpty().lowercase(Locale.ROOT) }
            ?.take(100)
            ?.forEach { file -> files.put(JSONObject().put("name", file.name.orEmpty())) }
        return files.toString()
    }

    fun shareFile(name: String): Pair<Boolean, String> {
        val safeName = validateName(name) ?: return false to "That file name is not allowed."
        return try {
            val uri = if (isFullAccessEnabled()) {
                val file = File(sharedStorageRoot() ?: return false to "Shared storage is not available.", safeName)
                if (!file.isFile) return false to "I could not find $safeName in shared storage."
                val shareDir = File(context.cacheDir, "shared-files").apply { mkdirs() }
                val cached = File(shareDir, safeName)
                file.inputStream().use { input -> cached.outputStream().use { output -> input.copyTo(output) } }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cached)
            } else {
                val document = getDocumentRoot()?.findFile(safeName)
                    ?: return false to "I could not find $safeName in the selected folder."
                if (!document.isFile) return false to "$safeName is not a regular file."
                document.uri
            }

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
        if (isFullAccessEnabled()) {
            val root = sharedStorageRoot() ?: return false to "Shared storage is not available."
            val file = File(root, safeName)
            if (file.exists()) return false to "A file or folder named $safeName already exists."
            if (!writeFile(file, content)) return false to "Android could not create $safeName."
            return true to "Created $safeName in shared storage."
        }

        val root = getDocumentRoot() ?: return false to "Choose a storage folder first."
        if (root.findFile(safeName) != null) return false to "A file or folder named $safeName already exists."
        val file = root.createFile("text/plain", safeName)
            ?: return false to "Android could not create $safeName."
        return writeText(file, content, "Created $safeName.")
    }

    fun editTextFile(name: String, content: String): Pair<Boolean, String> {
        val safeName = validateName(name) ?: return false to "Use a simple file name without path separators."
        if (isFullAccessEnabled()) {
            val root = sharedStorageRoot() ?: return false to "Shared storage is not available."
            val file = File(root, safeName)
            if (!file.isFile) return false to "$safeName is not a regular file."
            if (!isTextFile(file)) return false to "$safeName does not look like a text file."
            if (!writeFile(file, content)) return false to "Android could not write $safeName."
            return true to "Updated $safeName in shared storage."
        }

        val root = getDocumentRoot() ?: return false to "Choose a storage folder first."
        val file = root.findFile(safeName)
            ?: return false to "I could not find $safeName in the selected folder."
        if (!file.isFile) return false to "$safeName is not a file."
        if (!isTextDocument(file)) return false to "$safeName does not look like a text file."
        return writeText(file, content, "Updated $safeName.")
    }

    fun deleteFile(name: String): Pair<Boolean, String> {
        val safeName = validateName(name) ?: return false to "Use a file name without path separators."
        if (isFullAccessEnabled()) {
            val root = sharedStorageRoot() ?: return false to "Shared storage is not available."
            val file = File(root, safeName)
            if (!file.exists()) return false to "I could not find $safeName in shared storage."
            return if (file.delete()) true to "Deleted $safeName from shared storage."
            else false to "Android could not delete $safeName. Empty folders and files can be deleted."
        }

        val root = getDocumentRoot() ?: return false to "Choose a storage folder first."
        val file = root.findFile(safeName)
            ?: return false to "I could not find $safeName in the selected folder."
        val deleted = file.delete()
        return if (deleted) true to "Deleted $safeName." else false to "Android could not delete $safeName."
    }

    private fun sharedStorageRoot(): File? =
        if (fullAccessGranted()) Environment.getExternalStorageDirectory() else null

    private fun getDocumentRoot(): DocumentFile? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null) ?: return null
        val uri = Uri.parse(value)
        val hasPermission = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!hasPermission) return null
        return DocumentFile.fromTreeUri(context, uri)
    }

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

    private fun writeText(file: DocumentFile, content: String, success: String): Pair<Boolean, String> {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_READ_BYTES) {
            return false to "That text is larger than the 512 KB voice-file limit."
        }
        return try {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: return false to "Android could not open ${file.name} for writing."
            true to success
        } catch (_: IOException) {
            false to "Android could not write ${file.name}."
        }
    }

    private fun isTextFile(file: File): Boolean =
        file.extension.lowercase(Locale.ROOT) in textExtensions

    private fun isTextDocument(file: DocumentFile): Boolean =
        file.type?.startsWith("text/") == true ||
            file.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in textExtensions

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
