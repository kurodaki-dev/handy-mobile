package com.handy.stt.natlep

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.cos
import kotlin.math.sin

val PillLavenderBg = Color(0xFFF5EAFF)
val CheckPurpleBg = Color(0xFF67298A)
val WaveDarkInk = Color(0xFF1D1B20)
val HandyInkOutline = Color(0xFF2B1E23)
val HandyPink = Color(0xFFFF9CE3)

class FloatingSttService : android.app.Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private lateinit var modelManager: WhisperModelManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var focusWatcherJob: Job? = null
    private var equalizerDecayJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private var isRecordingState by mutableStateOf(false)
    private var isProcessingState by mutableStateOf(false)
    private var isMinimizedBubble by mutableStateOf(true)
    private var liveTranscriptState by mutableStateOf("")

    private val waveAmplitudes = mutableStateListOf(
        0.08f, 0.08f, 0.08f, 0.08f, 0.08f, 0.08f, 0.08f, 0.08f, 0.08f, 0.08f, 0.08f
    )
    private var currentVoiceEnergy by mutableFloatStateOf(0f)
    private var recordingStartTime = 0L

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        modelManager = WhisperModelManager(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        startForegroundNotification()
        showFloatingOverlay()

        focusWatcherJob = serviceScope.launch {
            while (isActive) {
                if (!modelManager.isUserMasterEnabled()) {
                    stopSelf()
                    break
                }

                val focused = HandyTextInjectorService.instance?.refreshEditableFocusState() ?: false
                val hideOutside = modelManager.isHideBubbleWhenNoInput()

                if (!focused) {
                    if (isRecordingState) {
                        cancelVoiceCapture()
                        isMinimizedBubble = true
                    }
                    if (hideOutside) {
                        composeView?.visibility = View.GONE
                    } else {
                        composeView?.visibility = View.VISIBLE
                    }
                } else {
                    composeView?.visibility = View.VISIBLE
                }
                delay(200)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_STOP_SERVICE" -> {
                modelManager.setUserMasterEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
            "ACTION_SHOW_STANDBY_PILL" -> {
                if (!modelManager.isUserMasterEnabled()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                isMinimizedBubble = true
            }
            "ACTION_START_RECORDING" -> {
                tryStartDictationStrict()
            }
        }
        return START_STICKY
    }

    private fun tryStartDictationStrict() {
        val injector = HandyTextInjectorService.instance
        if (injector == null) {
            Toast.makeText(applicationContext, "Activez d'abord l'Accessibilité Handy !", Toast.LENGTH_LONG).show()
            return
        }

        val isEditableField = injector.refreshEditableFocusState()
        if (!isEditableField) {
            vibrateTick()
            Toast.makeText(applicationContext, "Touchez d'abord une zone de saisie de texte !", Toast.LENGTH_SHORT).show()
            return
        }

        isMinimizedBubble = false
        startVoiceCaptureSynced()
    }

    private fun startForegroundNotification() {
        val channelId = "handy_floating_stt_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Handy STT", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("handy actif")
                .setContentText("Actif dans les zones de texte")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("handy actif")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        }

        startForeground(1042, notification)
    }

    private fun showFloatingOverlay() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 140
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingSttService)
            setViewTreeViewModelStoreOwner(this@FloatingSttService)
            setViewTreeSavedStateRegistryOwner(this@FloatingSttService)

            setContent {
                MaterialTheme(
                    colorScheme = lightColorScheme(
                        primary = HandyPink,
                        secondary = CheckPurpleBg,
                        background = Color(0xFFFFFDF0),
                        surface = Color(0xFFFFFFFF),
                        onSurface = HandyInkOutline
                    )
                ) {
                    val isFieldFocused by HandyTextInjectorService.hasEditableFieldFocused.collectAsState()
                    val hideWhenNoInput = modelManager.isHideBubbleWhenNoInput()

                    FloatingHandyOverlayContent(
                        isRecording = isRecordingState,
                        isProcessing = isProcessingState,
                        isMinimized = isMinimizedBubble,
                        isEditableFieldActive = isFieldFocused,
                        hideWhenNoInput = hideWhenNoInput,
                        amplitudes = waveAmplitudes,
                        livePreview = liveTranscriptState,
                        onDrag = { dx, dy ->
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                            try { windowManager.updateViewLayout(this, params) } catch (_: Exception) {}
                        },
                        onCancelClick = {
                            vibrateTick()
                            cancelVoiceCapture()
                            isMinimizedBubble = true
                        },
                        onConfirmClick = {
                            vibrateTick()
                            finishAndWriteDirectlyToField()
                        },
                        onExpandBubble = {
                            vibrateTick()
                            tryStartDictationStrict()
                        }
                    )
                }
            }
        }

        composeView = view
        windowManager.addView(view, params)
    }

    private fun startVoiceCaptureSynced() {
        cancelVoiceCapture()
        isRecordingState = true
        isProcessingState = false
        liveTranscriptState = ""
        currentVoiceEnergy = 0f
        recordingStartTime = System.currentTimeMillis()

        equalizerDecayJob = serviceScope.launch {
            val profile = floatArrayOf(0.18f, 0.32f, 0.55f, 0.78f, 0.94f, 1.0f, 0.94f, 0.78f, 0.55f, 0.32f, 0.18f)
            var phase = 0.0
            while (isActive && isRecordingState) {
                phase += 0.45
                currentVoiceEnergy = (currentVoiceEnergy * 0.82f).coerceAtLeast(0f)

                for (i in 0 until 11) {
                    if (currentVoiceEnergy < 0.05f) {
                        waveAmplitudes[i] = 0.08f
                    } else {
                        val harmonic = 0.75f + 0.25f * sin(phase + i * 0.65).toFloat() * cos(phase * 0.5 - i * 0.3).toFloat()
                        val target = (currentVoiceEnergy * profile[i] * harmonic).coerceIn(0.08f, 1.0f)
                        waveAmplitudes[i] = target
                    }
                }
                delay(28)
            }
            for (i in 0 until 11) waveAmplitudes[i] = 0.08f
        }

        Handler(Looper.getMainLooper()).post {
            try {
                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                        setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {}
                            override fun onBeginningOfSpeech() { currentVoiceEnergy = 0.45f }
                            override fun onRmsChanged(rmsdB: Float) {
                                val normalized = ((rmsdB - 1.2f) / 8.5f).coerceIn(0f, 1f)
                                if (normalized > currentVoiceEnergy) {
                                    currentVoiceEnergy = normalized
                                } else {
                                    currentVoiceEnergy = currentVoiceEnergy * 0.6f + normalized * 0.4f
                                }
                            }
                            override fun onBufferReceived(buffer: ByteArray?) {}
                            override fun onEndOfSpeech() { currentVoiceEnergy = 0f }
                            override fun onError(error: Int) { currentVoiceEnergy = 0f }
                            override fun onResults(results: Bundle?) {
                                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                if (!list.isNullOrEmpty()) { liveTranscriptState = list[0] }
                            }
                            override fun onPartialResults(partialResults: Bundle?) {
                                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                if (!list.isNullOrEmpty()) {
                                    liveTranscriptState = list[0]
                                    if (currentVoiceEnergy < 0.55f) currentVoiceEnergy = 0.65f
                                }
                            }
                            override fun onEvent(eventType: Int, params: Bundle?) {}
                        })
                        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, modelManager.getLanguageCode())
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        }
                        startListening(recognizerIntent)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun cancelVoiceCapture() {
        isRecordingState = false
        currentVoiceEnergy = 0f
        equalizerDecayJob?.cancel()
        for (i in 0 until 11) waveAmplitudes[i] = 0.08f
        Handler(Looper.getMainLooper()).post {
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (_: Exception) {}
        }
    }

    private fun finishAndWriteDirectlyToField() {
        isRecordingState = false
        isProcessingState = true
        currentVoiceEnergy = 0f
        equalizerDecayJob?.cancel()

        Handler(Looper.getMainLooper()).post {
            try { speechRecognizer?.stopListening() } catch (_: Exception) {}
        }

        serviceScope.launch {
            delay(280)
            val durationMs = (System.currentTimeMillis() - recordingStartTime).coerceAtLeast(100L)
            val activeModel = modelManager.getActiveModelId()
            val finalTranscript = liveTranscriptState.trim()

            if (finalTranscript.isNotBlank()) {
                val injector = HandyTextInjectorService.instance
                val targetPkg = HandyTextInjectorService.focusedAppPackage.value.ifBlank { "TextField" }
                val written = injector?.injectTextDirectlyIntoActiveField(finalTranscript) ?: false
                if (written) {
                    modelManager.addHistoryEntry(finalTranscript, targetPkg, activeModel, durationMs)
                }
            }

            Handler(Looper.getMainLooper()).post {
                try {
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                } catch (_: Exception) {}
            }

            isProcessingState = false
            isMinimizedBubble = true
        }
    }

    private fun vibrateTick() {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(20)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        _isRunning.value = false
        focusWatcherJob?.cancel()
        equalizerDecayJob?.cancel()
        cancelVoiceCapture()
        serviceScope.cancel()
        composeView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

@Composable
fun FloatingHandyOverlayContent(
    isRecording: Boolean,
    isProcessing: Boolean,
    isMinimized: Boolean,
    isEditableFieldActive: Boolean,
    hideWhenNoInput: Boolean,
    amplitudes: List<Float>,
    livePreview: String,
    onDrag: (Float, Float) -> Unit,
    onCancelClick: () -> Unit,
    onConfirmClick: () -> Unit,
    onExpandBubble: () -> Unit
) {
    if (!isEditableFieldActive && hideWhenNoInput) {
        Box(modifier = Modifier.size(0.dp))
        return
    }

    val themePink = MaterialTheme.colorScheme.primary
    val themePurple = MaterialTheme.colorScheme.secondary
    val inkOutline = HandyInkOutline

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(8.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        if (isMinimized) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(themePink)
                    .border(2.5.dp, inkOutline, CircleShape)
                    .clickable { onExpandBubble() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Microphone Handy",
                    tint = inkOutline,
                    modifier = Modifier.size(26.dp)
                )
            }
            return
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(themePink)
                    .border(2.5.dp, inkOutline, CircleShape)
                    .clickable { onCancelClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Annuler",
                    tint = inkOutline,
                    modifier = Modifier.size(26.dp)
                )
            }

            Box(
                modifier = Modifier
                    .width(148.dp)
                    .height(56.dp)
                    .shadow(6.dp, RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .background(themePink)
                    .border(2.5.dp, inkOutline, RoundedCornerShape(28.dp))
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        color = themePurple,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        amplitudes.forEachIndexed { index, rawAmp ->
                            val animAmp by animateFloatAsState(
                                targetValue = if (isRecording) rawAmp else 0.08f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessHigh
                                ),
                                label = "eq_$index"
                            )
                            val barHeight = (4.5f + animAmp * 20f).dp
                            Box(
                                modifier = Modifier
                                    .width(4.5.dp)
                                    .height(barHeight)
                                    .clip(RoundedCornerShape(50))
                                    .background(inkOutline)
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(themePurple)
                    .border(2.5.dp, inkOutline, CircleShape)
                    .clickable { onConfirmClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Valider",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        if (livePreview.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Surface(
                color = themePink,
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 4.dp,
                modifier = Modifier.border(2.5.dp, inkOutline, RoundedCornerShape(12.dp))
            ) {
                Text(
                    text = livePreview,
                    color = inkOutline,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 260.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}
