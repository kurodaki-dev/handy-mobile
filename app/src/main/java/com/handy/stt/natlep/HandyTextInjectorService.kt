package com.handy.stt.natlep

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Service d'Accessibilité strict :
 * - Vérifie en permanence si l'utilisateur est dans une zone de saisie de texte (EditText / Chat).
 * - N'autorise la bulle et la dictée QUE lorsqu'une zone de saisie de texte est active.
 * - Écrit directement le texte dans la zone de saisie sans jamais copier dans le presse-papier.
 */
class HandyTextInjectorService : AccessibilityService() {

    companion object {
        var instance: HandyTextInjectorService? = null

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected

        private val _hasEditableFieldFocused = MutableStateFlow(false)
        val hasEditableFieldFocused: StateFlow<Boolean> = _hasEditableFieldFocused

        private val _focusedAppPackage = MutableStateFlow("")
        val focusedAppPackage: StateFlow<String> = _focusedAppPackage
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        refreshEditableFocusState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val isEditable = refreshEditableFocusState()
                if (isEditable && Settings.canDrawOverlays(this) && !FloatingSttService.isRunning.value) {
                    val intent = Intent(this, FloatingSttService::class.java).apply {
                        action = "ACTION_SHOW_STANDBY_PILL"
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
            _isServiceConnected.value = false
            _hasEditableFieldFocused.value = false
        }
    }

    fun refreshEditableFocusState(): Boolean {
        return try {
            val activeRoot = rootInActiveWindow
            val inputNode = activeRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFocusedEditableNodeRecursive(activeRoot)

            if (inputNode != null && inputNode.isEditable) {
                _hasEditableFieldFocused.value = true
                _focusedAppPackage.value = inputNode.packageName?.toString() ?: ""
                true
            } else {
                var foundInWindows = false
                for (win in windows) {
                    val r = win.root ?: continue
                    val n = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFocusedEditableNodeRecursive(r)
                    if (n != null && n.isEditable) {
                        foundInWindows = true
                        _focusedAppPackage.value = n.packageName?.toString() ?: ""
                        break
                    }
                }
                _hasEditableFieldFocused.value = foundInWindows
                if (!foundInWindows) _focusedAppPackage.value = ""
                foundInWindows
            }
        } catch (_: Exception) {
            _hasEditableFieldFocused.value = false
            false
        }
    }

    fun injectTextDirectlyIntoActiveField(dictatedText: String): Boolean {
        val clean = dictatedText.trim()
        if (clean.isEmpty()) return false

        val targetNode = findCurrentEditableNode() ?: return false
        return try {
            val existingText = targetNode.text?.toString() ?: ""
            val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                targetNode.hintText?.toString() ?: ""
            } else ""

            val actualExisting = if (existingText == hintText) "" else existingText
            val selStart = targetNode.textSelectionStart
            val selEnd = targetNode.textSelectionEnd

            val newText = if (selStart >= 0 && selEnd >= selStart && selEnd <= actualExisting.length) {
                val before = actualExisting.substring(0, selStart)
                val after = actualExisting.substring(selEnd)
                val prefixSpace = if (before.isNotEmpty() && !before.endsWith(" ") && !before.endsWith("\n")) " " else ""
                before + prefixSpace + clean + after
            } else {
                if (actualExisting.isBlank()) clean else "$actualExisting $clean"
            }

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            if (success) {
                val cursorArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
                }
                targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)
            }
            success
        } catch (_: Exception) {
            false
        }
    }

    private fun findCurrentEditableNode(): AccessibilityNodeInfo? {
        try {
            val root = rootInActiveWindow
            val direct = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (direct != null && direct.isEditable) return direct

            val rec = findFocusedEditableNodeRecursive(root)
            if (rec != null) return rec

            for (win in windows) {
                val r = win.root ?: continue
                val n = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFocusedEditableNodeRecursive(r)
                if (n != null && n.isEditable) return n
            }
        } catch (_: Exception) {}
        return null
    }

    private fun findFocusedEditableNodeRecursive(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditableNodeRecursive(child)
            if (found != null) return found
        }
        return null
    }
}
