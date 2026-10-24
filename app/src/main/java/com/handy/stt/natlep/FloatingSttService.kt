package com.handy.stt.natlep

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sqrt

// Couleurs de la pilule vocale
val PillLavenderBg = Color(0xFFF5EAFF)
val CheckPurpleBg = Color(0xFF67298A)
val WaveDarkInk = Color(0xFF1D1B20)
val HandyPinkBubble = Color(0xFFFF9CE3)
val HandyCartoonOutline = Color(0xFF2B1E23)

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
    private var audioJob: Job? = null
    private var focusWatcherJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private var isRecordingState by mutableStateOf(false)
    private var isProcessingState by mutableStateOf(false)
    private var isMinimizedBubble by mutableStateOf(true)
    private var liveTranscriptState by mutableStateOf("")

    private val waveAmplitudes = mutableStateListOf(
        0.15f, 0.18f, 0.35f, 0.55f, 0.85f, 1.0f, 0.85f, 0.55f, 0.35f, 0.18f, 0.15f
    )

    private val recordedPcmStream = ByteArrayOutputStream()
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

        // Surveillance périodique toutes les 450ms pour vérifier si une zone de saisie de texte est active
        focusWatcherJob = serviceScope.launch {
            while (isActive) {
                val focused = HandyTextInjectorService.instance?.refreshEditableFocusState() ?: false
                if (!focused && isRecordingState) {
                    // Dès qu'on sort d'une zone de saisie de texte, on coupe immédiatement l'enregistrement
                    cancelVoiceCapture()
                    isMinimizedBubble = true
                }
                delay(450)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_STOP_SERVICE" -> {
                stopSelf()
                return START_NOT_STICKY
            }
            "ACTION_SHOW_STANDBY_PILL" -> {
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
            Toast.makeText(
                applicationContext,
                "Activez l'Accessibilité Handy pour détecter les zones de saisie de texte.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val isEditableField = injector.refreshEditableFocusState()
        if (!isEditableField) {
            vibrateTick()
            Toast.makeText(
                applicationContext,
                "Touchez d'abord une zone de saisie de texte pour parler !",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        isMinimizedBubble = false
        startVoiceCapture()
    }

    private fun startForegroundNotification() {
        val channelId = "handy_floating_stt_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Handy Direct Text Input STT",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("handy est prêt")
                .setContentText("S'active uniquement dans les zones de saisie de texte")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("handy est prêt")
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 150
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingSttService)
            setViewTreeViewModelStoreOwner(this@FloatingSttService)
            setViewTreeSavedStateRegistryOwner(this@FloatingSttService)

            setContent {
                val isFieldFocused by HandyTextInjectorService.hasEditableFieldFocused.collectAsState()
                val hideWhenNoInput = remember { modelManager.isHideBubbleWhenNoInput() }

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
                        try {
                            windowManager.updateViewLayout(this, params)
                        } catch (_: Exception) {}
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

        composeView = view
        windowManager.addView(view, params)
    }

    private fun startVoiceCapture() {
        cancelVoiceCapture()
        isRecordingState = true
        isProcessingState = false
        liveTranscriptState = ""
        recordedPcmStream.reset()
        recordingStartTime = System.currentTimeMillis()

        Handler(Looper.getMainLooper()).post {
            try {
                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                        setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {}
                            override fun onBeginningOfSpeech() {}
                            override fun onRmsChanged(rmsdB: Float) {}
                            override fun onBufferReceived(buffer: ByteArray?) {}
                            override fun onEndOfSpeech() {}
                            override fun onError(error: Int) {}
                            override fun onResults(results: Bundle?) {
                                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                if (!list.isNullOrEmpty()) {
                                    liveTranscriptState = list[0]
                                }
                            }
                            override fun onPartialResults(partialResults: Bundle?) {
                                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                if (!list.isNullOrEmpty()) {
                                    liveTranscriptState = list[0]
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

        audioJob = serviceScope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    recorder.startRecording()
                    val shortBuf = ShortArray(512)
                    val byteBuf = ByteArray(1024)

                    while (isActive && isRecordingState) {
                        val read = recorder.read(shortBuf, 0, shortBuf.size)
                        if (read > 0) {
                            var sumSq = 0.0
                            for (i in 0 until read) {
                                val sample = shortBuf[i].toInt()
                                sumSq += sample * sample
                                byteBuf[i * 2] = (sample and 0xFF).toByte()
                                byteBuf[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                            }
                            recordedPcmStream.write(byteBuf, 0, read * 2)

                            val rms = sqrt(sumSq / read) / 6500.0
                            val level = rms.toFloat().coerceIn(0.12f, 1.0f)

                            val envelope = floatArrayOf(0.16f, 0.25f, 0.50f, 0.75f, 0.95f, 1.0f, 0.95f, 0.75f, 0.50f, 0.25f, 0.16f)
                            withContext(Dispatchers.Main) {
                                for (idx in 0 until 11) {
                                    val jitter = ((System.nanoTime() shr (idx + 2)) and 0x7) * 0.03f
                                    waveAmplitudes[idx] = (level * envelope[idx] + jitter).coerceIn(0.14f, 1.0f)
                                }
                            }
                        }
                        delay(35)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (_: Exception) {}
            }
        }
    }

    private fun cancelVoiceCapture() {
        isRecordingState = false
        audioJob?.cancel()
        if (modelManager.isLowRamEcoMode()) {
            recordedPcmStream.reset()
        }
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
        audioJob?.cancel()

        Handler(Looper.getMainLooper()).post {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
        }

        serviceScope.launch {
            delay(300)
            val durationMs = (System.currentTimeMillis() - recordingStartTime).coerceAtLeast(100L)
            val pcmBytes = recordedPcmStream.toByteArray()
            if (modelManager.isLowRamEcoMode()) {
                recordedPcmStream.reset()
            } else if (pcmBytes.isNotEmpty()) {
                val wavFile = File(modelManager.recordingsDir, "last_dictation.wav")
                withContext(Dispatchers.IO) {
                    modelManager.writeWavFile(pcmBytes, wavFile)
                }
            }

            val activeModel = modelManager.getActiveModelId()
            val finalTranscript = liveTranscriptState.trim()

            if (finalTranscript.isNotBlank()) {
                val injector = HandyTextInjectorService.instance
                val targetPkg = HandyTextInjectorService.focusedAppPackage.value.ifBlank { "TextField" }
                val written = injector?.injectTextDirectlyIntoActiveField(finalTranscript) ?: false
                if (written) {
                    modelManager.addHistoryEntry(
                        text = finalTranscript,
                        targetApp = targetPkg,
                        modelUsed = activeModel,
                        durationMs = durationMs
                    )
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
        cancelVoiceCapture()
        serviceScope.cancel()
        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
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
    // RÈGLE STRICTE : Si aucune zone de saisie de texte n'est active et que l'option est activée,
    // la bulle ne s'affiche même pas en dehors d'une zone de texte !
    if (!isEditableFieldActive && hideWhenNoInput && isMinimized) {
        Box(modifier = Modifier.size(0.dp))
        return
    }

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
            // Bulle inactive = TOUJOURS un Microphone (Icons.Default.Mic) avec style Handy Rose/Violet
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(if (isEditableFieldActive) HandyPinkBubble else PillLavenderBg)
                    .border(2.5.dp, HandyCartoonOutline, CircleShape)
                    .clickable { onExpandBubble() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Microphone Handy",
                    tint = HandyCartoonOutline,
                    modifier = Modifier.size(26.dp)
                )
            }
            return
        }

        // Barre active lors de la parole : [ ( X ) ]   [ • • | | | | | • • ]   [ ( ✓ ) ]
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Bouton Rond Gauche Lavande (#F5EAFF) + Croix Noire
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(PillLavenderBg)
                    .clickable { onCancelClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Annuler",
                    tint = WaveDarkInk,
                    modifier = Modifier.size(26.dp)
                )
            }

            // Pilule Centrale Lavande (#F5EAFF) + Onde vocale noire à 11 éléments
            Box(
                modifier = Modifier
                    .width(146.dp)
                    .height(56.dp)
                    .shadow(6.dp, RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .background(PillLavenderBg)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        color = CheckPurpleBg,
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
                                targetValue = if (isRecording) rawAmp else 0.15f,
                                animationSpec = tween(durationMillis = 65),
                                label = "bar_$index"
                            )
                            val barHeight = (5f + animAmp * 14f).dp
                            Box(
                                modifier = Modifier
                                    .width(4.5.dp)
                                    .height(barHeight)
                                    .clip(RoundedCornerShape(50))
                                    .background(WaveDarkInk)
                            )
                        }
                    }
                }
            }

            // Bouton Rond Droit Violet Foncé (#67298A) + Coche Blanche
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(CheckPurpleBg)
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
                color = Color(0xFFFDFBF0),
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 4.dp,
                modifier = Modifier.border(2.dp, HandyCartoonOutline, RoundedCornerShape(12.dp))
            ) {
                Text(
                    text = livePreview,
                    color = HandyCartoonOutline,
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
