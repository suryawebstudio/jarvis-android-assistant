package com.surya.jarvis

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Provides narrow, visible-screen automation for MARK.
 *
 * Android requires the user to enable this service manually in Accessibility
 * settings. It never searches hidden app content and never bypasses security
 * screens, passwords, CAPTCHAs, or Android permission dialogs.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var current: JarvisAccessibilityService? = null

        fun isEnabled(): Boolean = current != null

        fun inspectScreen(): String = current?.describeVisibleScreen()
            ?: "Accessibility control is not enabled. Open Android Accessibility settings and enable MARK."

        fun control(
            action: String,
            target: String,
            text: String,
            direction: String,
            confirmed: Boolean
        ): Pair<Boolean, String> = current?.performControl(
            action,
            target,
            text,
            direction,
            confirmed
        ) ?: (false to "Accessibility control is not enabled. Enable MARK in Android Accessibility settings first.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The current screen is queried only when MARK receives an explicit
        // inspect/control command. No screen contents are stored or uploaded.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    private fun performControl(
        rawAction: String,
        rawTarget: String,
        text: String,
        rawDirection: String,
        confirmed: Boolean
    ): Pair<Boolean, String> {
        val action = rawAction.trim().lowercase(Locale.ROOT)
        val target = rawTarget.trim()
        val direction = rawDirection.trim().lowercase(Locale.ROOT)

        if (action.isBlank()) return false to "Specify a visible-screen action."

        if (isSensitive(action, target, text) && !confirmed) {
            return false to "This action may send, delete, purchase, call, or share something. Ask the user for confirmation, then repeat it with confirmed=true."
        }

        return when (action) {
            "tap", "click", "press" -> tapVisibleTarget(target)
            "type", "input", "write" -> typeIntoVisibleTarget(target, text)
            "scroll" -> scrollVisibleScreen(direction)
            "back" -> globalAction(GLOBAL_ACTION_BACK, "Went back.")
            "home" -> globalAction(GLOBAL_ACTION_HOME, "Went to the Home screen.")
            "overview", "recent_apps" -> globalAction(GLOBAL_ACTION_RECENTS, "Opened recent apps.")
            else -> false to "Unsupported visible-screen action. Use tap, type, scroll, back, home, or overview."
        }
    }

    private fun tapVisibleTarget(target: String): Pair<Boolean, String> {
        if (target.isBlank()) return false to "Specify the visible button, link, or text to tap."
        val matches = findVisibleNodes(target)
        if (matches.isEmpty()) {
            return false to "I could not find a visible control named '$target'. Inspect the screen and try the exact visible label."
        }
        val bestScore = matches.maxOf { it.first }
        val bestMatches = matches.filter { it.first == bestScore }
        if (bestMatches.size > 1) {
            val choices = bestMatches.take(5).joinToString(", ") { describeNodeLabel(it.second) }
            return false to "The target '$target' is ambiguous. Matching visible controls: $choices. Ask the user to choose one."
        }
        val node = bestMatches.first().second

        val clickable = if (node.isClickable) node else clickableAncestor(node)
        val success = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        return if (success) true to "Tapped the visible control '$target'."
        else false to "The visible control '$target' is not clickable."
    }

    private fun typeIntoVisibleTarget(target: String, text: String): Pair<Boolean, String> {
        if (text.isBlank()) return false to "Specify the text to enter."
        val node = if (target.isBlank()) {
            findFocusedEditable(rootInActiveWindow)
        } else {
            val matches = findVisibleNodes(target)
            if (matches.isEmpty()) return false to "I could not find a visible text field named '$target'."
            val bestScore = matches.maxOf { it.first }
            val bestMatches = matches.filter { it.first == bestScore }
            if (bestMatches.size > 1) {
                val choices = bestMatches.take(5).joinToString(", ") { describeNodeLabel(it.second) }
                return false to "The text field '$target' is ambiguous. Matching visible controls: $choices. Ask the user to choose one."
            }
            bestMatches.first().second
        }
        val editable = node?.let { if (it.isEditable) it else editableDescendant(it) }
            ?: return false to "I could not find a visible text field named '$target'."

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        return if (success) true to "Entered text into the visible field."
        else false to "The visible text field could not accept text."
    }

    private fun scrollVisibleScreen(direction: String): Pair<Boolean, String> {
        val action = when (direction) {
            "up", "backward", "previous" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "down", "forward", "next", "" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> return false to "Use scroll direction up or down."
        }

        val node = findScrollableNode(rootInActiveWindow)
            ?: return false to "I could not find a visible scrollable area."
        return if (node.performAction(action)) true to "Scrolled ${if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) "down" else "up"}."
        else false to "The visible area could not be scrolled."
    }

    private fun globalAction(action: Int, successMessage: String): Pair<Boolean, String> =
        if (performGlobalAction(action)) true to successMessage
        else false to "Android rejected that navigation action."

    private fun describeVisibleScreen(): String {
        val root = rootInActiveWindow
            ?: return "No accessible screen is currently visible."
        val elements = ArrayList<String>()
        collectElements(root, elements, 0)
        val packageName = root.packageName?.toString().orEmpty().ifBlank { "unknown" }
        val className = root.className?.toString().orEmpty().ifBlank { "unknown" }
        return if (elements.isEmpty()) {
            "The current screen has no readable accessibility labels."
        } else {
            "SCREEN package=$packageName class=$className\n" +
                elements.take(80).joinToString("\n")
        }
    }

    private fun collectElements(
        node: AccessibilityNodeInfo,
        elements: MutableList<String>,
        depth: Int
    ) {
        if (depth > 20 || elements.size >= 100) return
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotBlank() || description.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable) {
            val role = node.className?.toString()?.substringAfterLast('.') ?: "node"
            elements.add(
                "ELEMENT ${elements.size + 1} role=$role " +
                    "text=${quoteForOutput(text)} " +
                    "description=${quoteForOutput(description)} " +
                    "clickable=${node.isClickable} editable=${node.isEditable} " +
                    "scrollable=${node.isScrollable} enabled=${node.isEnabled}"
            )
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectElements(it, elements, depth + 1) }
        }
    }

    private fun findVisibleNode(target: String): AccessibilityNodeInfo? =
        findVisibleNodes(target).maxByOrNull { it.first }?.second

    private fun findVisibleNodes(target: String): List<Pair<Int, AccessibilityNodeInfo>> {
        val root = rootInActiveWindow ?: return emptyList()
        val normalizedTarget = normalize(target)
        if (normalizedTarget.isBlank()) return emptyList()
        val matches = ArrayList<Pair<Int, AccessibilityNodeInfo>>()
        collectMatchingNodes(root, normalizedTarget, matches, 0)
        return matches.sortedByDescending { it.first }
    }

    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo,
        normalizedTarget: String,
        matches: MutableList<Pair<Int, AccessibilityNodeInfo>>,
        depth: Int
    ) {
        if (depth > 20 || matches.size >= 30) return
        val text = normalize(node.text?.toString().orEmpty())
        val description = normalize(node.contentDescription?.toString().orEmpty())
        val score = when {
            text == normalizedTarget || description == normalizedTarget -> 400
            text.startsWith(normalizedTarget) || description.startsWith(normalizedTarget) -> 300
            text.contains(normalizedTarget) || description.contains(normalizedTarget) -> 200
            else -> 0
        }
        if (score > 0 && (node.isVisibleToUser || node.isClickable || node.isEditable)) {
            matches.add(score to node)
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let {
                collectMatchingNodes(it, normalizedTarget, matches, depth + 1)
            }
        }
    }

    private fun describeNodeLabel(node: AccessibilityNodeInfo): String =
        node.text?.toString()?.trim().orEmpty().ifBlank {
            node.contentDescription?.toString()?.trim().orEmpty().ifBlank { "unlabeled control" }
        }

    private fun quoteForOutput(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        node?.let {
            if (it.isFocused && it.isEditable) it
            else (0 until it.childCount).asSequence().mapNotNull { index -> findFocusedEditable(it.getChild(index)) }.firstOrNull()
        }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        node?.let {
            if (it.isScrollable) it
            else (0 until it.childCount).asSequence().mapNotNull { index -> findScrollableNode(it.getChild(index)) }.firstOrNull()
        }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) return parent
            parent = parent.parent
        }
        return null
    }

    private fun editableDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        if (node.isEditable) node
        else (0 until node.childCount).asSequence().mapNotNull { index -> editableDescendant(node.getChild(index) ?: return@mapNotNull null) }.firstOrNull()

    private fun findNode(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        node?.let {
            if (predicate(it)) it
            else (0 until it.childCount).asSequence().mapNotNull { index -> findNode(it.getChild(index), predicate) }.firstOrNull()
        }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun isSensitive(action: String, target: String, text: String): Boolean {
        val value = "$action $target $text".lowercase(Locale.ROOT)
        return listOf(
            "send", "submit", "purchase", "buy", "pay", "delete", "remove",
            "call", "share", "post", "publish", "transfer", "confirm", "install"
        ).any(value::contains)
    }
}
