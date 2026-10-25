package com.handy.stt.natlep

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
        val isEditable = refreshEditableFocusState()
        val prefs = getSharedPreferences("handy_stt_prefs", MODE_PRIVATE)
        val masterEnabled = prefs.getBoolean("user_master_enabled", false)
        if (!masterEnabled) return

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
            val winList = windows
            var imeVisible = false
            var editableNode: AccessibilityNodeInfo? = null

            if (winList != null && winList.isNotEmpty()) {
                for (w in winList) {
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        imeVisible = true
                    }
                    val r = w.root ?: continue
                    val focused = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focused != null && focused.isEditable && focused.isVisibleToUser) {
                        editableNode = focused
                    }
                }
            }

            if (editableNode == null) {
                val activeRoot = rootInActiveWindow
                val focused = activeRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && focused.isEditable && focused.isVisibleToUser) {
                    editableNode = focused
                }
            }

            val finalActive = if (winList != null && winList.isNotEmpty()) {
                editableNode != null && imeVisible
            } else {
                editableNode != null && editableNode.isFocused
            }

            _hasEditableFieldFocused.value = finalActive
            _focusedAppPackage.value = if (finalActive) (editableNode?.packageName?.toString() ?: "") else ""
            finalActive
        } catch (_: Exception) {
            _hasEditableFieldFocused.value = false
            _focusedAppPackage.value = ""
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

            for (win in windows) {
                val r = win.root ?: continue
                val n = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (n != null && n.isEditable) return n
            }
        } catch (_: Exception) {}
        return null
    }
}
