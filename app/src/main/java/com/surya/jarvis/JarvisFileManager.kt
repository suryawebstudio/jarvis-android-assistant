package com.surya.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

class JarvisFileManager(private val context: Context) {

    companion object {
        const val ACTION_CHOOSE_FOLDER = "com.surya.jarvis.action.CHOOSE_STORAGE_FOLDER"
        private const val PREFS = "jarvis_storage"
        private const val KEY_TREE_URI = "tree_uri"
        private const val MAX_READ_BYTES = 512 * 1024
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
        val root = getRoot() ?: return false to "Choose a storage folder first."
        val entries = root.listFiles().sortedBy { it.name.orEmpty().lowercase() }
        if (entries.isEmpty()) return true to "The selected folder is empty."
        val description = entries.take(100).joinToString(" | ") { file ->
            val kind = if (file.isDirectory) "folder" else "file"
            "${file.name.orEmpty()} ($kind)"
        }
        val suffix = if (entries.size > 100) " | More files are present." else ""
        return true to "Files in the selected folder: $description$suffix"
    }

    fun createTextFile(name: String, content: String): Pair<Boolean, String> {
        val root = getRoot() ?: return false to "Choose a storage folder first."
        val safeName = validateName(name) ?: return false to "Use a simple file name without path separators."
        if (root.findFile(safeName) != null) return false to "A file or folder named $safeName already exists."
        val file = root.createFile("text/plain", safeName)
            ?: return false to "Android could not create $safeName."
        return writeText(file, content, "Created $safeName.")
    }

    fun editTextFile(name: String, content: String): Pair<Boolean, String> {
        val root = getRoot() ?: return false to "Choose a storage folder first."
        val safeName = validateName(name) ?: return false to "Use a simple file name without path separators."
        val file = root.findFile(safeName)
            ?: return false to "I could not find $safeName in the selected folder."
        if (!file.isFile) return false to "$safeName is not a file."
        return writeText(file, content, "Updated $safeName.")
    }

    fun deleteFile(name: String): Pair<Boolean, String> {
        val root = getRoot() ?: return false to "Choose a storage folder first."
        val safeName = validateName(name) ?: return false to "Use a file name without path separators."
        val file = root.findFile(safeName)
            ?: return false to "I could not find $safeName in the selected folder."
        val deleted = file.delete()
        return if (deleted) true to "Deleted $safeName." else false to "Android could not delete $safeName."
    }

    private fun getRoot(): DocumentFile? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null) ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(value))
    }

    private fun writeText(file: DocumentFile, content: String, success: String): Pair<Boolean, String> {
        if (content.toByteArray(Charsets.UTF_8).size > MAX_READ_BYTES) {
            return false to "That text is larger than the 512 KB voice-file limit."
        }
        return try {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: return false to "Android could not open ${file.name} for writing."
            true to success
        } catch (_: IOException) {
            false to "Android could not write ${file.name}."
        }
    }

    private fun validateName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isBlank() || trimmed == "." || trimmed == "..") return null
        if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.length > 180) return null
        return trimmed
    }
}
