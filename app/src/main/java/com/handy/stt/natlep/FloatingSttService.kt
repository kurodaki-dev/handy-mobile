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
import androidx.compose.material.icons.filled.EditNote
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

// Palette exacte de la photo
val PillLavenderBg = Color(0xFFF5EAFF)
val CheckPurpleBg = Color(0xFF67298A)
val WaveDarkInk = Color(0xFF1D1B20)

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
    private var speechRecognizer: SpeechRecognizer? = null

    private var isRecordingState by mutableStateOf(false)
    private var isProcessingState by mutableStateOf(false)
    private var isMinimizedBubble by mutableStateOf(true)
    private var liveTranscriptState by mutableStateOf("")
    private var warningBannerState by mutableStateOf<String?>(null)

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_STOP_SERVICE" -> {
                stopSelf()
                return START_NOT_STICKY
            }
            "ACTION_SHOW_STANDBY_PILL" -> {
                // Appelé automatiquement quand l'utilisateur clique dans un champ de chat
                isMinimizedBubble = true
            }
            "ACTION_START_RECORDING" -> {
                tryStartDictationOnlyIfEditableFocused()
            }
        }
        return START_STICKY
    }

    /**
     * RÈGLE STRICTE DEMANDÉE PAR L'UTILISATEUR :
     * Le STT ne démarre QUE si l'on se trouve dans un endroit où l'on peut écrire (un chat, un message, un input).
     */
    private fun tryStartDictationOnlyIfEditableFocused() {
        val injector = HandyTextInjectorService.instance
        if (injector == null) {
            Toast.makeText(
                applicationContext,
                "⚠️ Activez d'abord le service d'Accessibilité Handy pour détecter les zones de texte !",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val canWriteHere = injector.refreshEditableFocusState()
        if (!canWriteHere) {
            vibrateTick()
            warningBannerState = "Cliquez d'abord dans une zone d'écriture (chat, message...)"
            Toast.makeText(
                applicationContext,
                "✏️ Cliquez d'abord dans une zone de texte (chat, message...) pour parler !",
                Toast.LENGTH_SHORT
            ).show()
            serviceScope.launch {
                delay(2600)
                warningBannerState = null
            }
            return
        }

        warningBannerState = null
        isMinimizedBubble = false
        startVoiceCapture()
    }

    private fun startForegroundNotification() {
        val channelId = "handy_floating_stt_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Handy Direct Chat Dictation",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Handy STT • Prêt pour vos chats")
                .setContentText("Touchez un champ de texte puis appuyez sur la bulle pour dicter")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Handy STT • Prêt pour vos chats")
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

                FloatingHandyOverlayContent(
                    isRecording = isRecordingState,
                    isProcessing = isProcessingState,
                    isMinimized = isMinimizedBubble,
                    isEditableFieldActive = isFieldFocused,
                    amplitudes = waveAmplitudes,
                    livePreview = liveTranscriptState,
                    warningMessage = warningBannerState,
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
                        finishAndWriteDirectlyToChat()
                    },
                    onExpandBubble = {
                        vibrateTick()
                        tryStartDictationOnlyIfEditableFocused()
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
                        // Si l'utilisateur quitte le champ de texte pendant qu'il parle, on arrête automatiquement
                        val stillEditable = HandyTextInjectorService.instance?.refreshEditableFocusState() ?: false
                        if (!stillEditable) {
                            withContext(Dispatchers.Main) {
                                cancelVoiceCapture()
                                isMinimizedBubble = true
                                Toast.makeText(
                                    applicationContext,
                                    "Zone de texte quittée : dictée annulée.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            break
                        }

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

    /**
     * Valide la dictée et écrit DIRECTEMENT dans la zone de texte active,
     * SANS JAMAIS copier dans le presse-papier !
     */
    private fun finishAndWriteDirectlyToChat() {
        isRecordingState = false
        isProcessingState = true
        audioJob?.cancel()

        Handler(Looper.getMainLooper()).post {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
        }

        serviceScope.launch {
            delay(320)
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

            if (finalTranscript.isBlank()) {
                Toast.makeText(
                    applicationContext,
                    "Aucune parole détectée.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                val injector = HandyTextInjectorService.instance
                val targetPkg = HandyTextInjectorService.focusedAppPackage.value.ifBlank { "Chat" }
                val written = injector?.injectTextDirectlyIntoActiveField(finalTranscript) ?: false

                if (written) {
                    modelManager.addHistoryEntry(
                        text = finalTranscript,
                        targetApp = targetPkg,
                        modelUsed = activeModel,
                        durationMs = durationMs
                    )
                } else {
                    Toast.makeText(
                        applicationContext,
                        "⚠️ Impossible d'écrire : cliquez d'abord dans une zone de texte !",
                        Toast.LENGTH_SHORT
                    ).show()
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
                vib.vibrate(VibrationEffect.createOneShot(22, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(22)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        _isRunning.value = false
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
    amplitudes: List<Float>,
    livePreview: String,
    warningMessage: String?,
    onDrag: (Float, Float) -> Unit,
    onCancelClick: () -> Unit,
    onConfirmClick: () -> Unit,
    onExpandBubble: () -> Unit
) {
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
            // Bulle compacte : Violet vif quand un champ d'écriture est actif, grisée sinon
            val bubbleBg = if (isEditableFieldActive) CheckPurpleBg else Color(0xFF2B2638)
            val borderCol = if (isEditableFieldActive) PillLavenderBg else Color(0xFF4A425C)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .shadow(8.dp, RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .background(bubbleBg)
                    .border(1.5.dp, borderCol, RoundedCornerShape(28.dp))
                    .clickable { onExpandBubble() }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = if (isEditableFieldActive) Icons.Default.Mic else Icons.Default.EditNote,
                    contentDescription = "Dictée",
                    tint = if (isEditableFieldActive) PillLavenderBg else Color(0xFFA39BB5),
                    modifier = Modifier.size(22.dp)
                )
                if (isEditableFieldActive) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4ADE80))
                    )
                }
            }

            warningMessage?.let { warn ->
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = Color(0xEE2B1620),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = warn,
                        color = Color(0xFFFFB4AB),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
            return
        }

        // Design exact de la photo : [ ( X ) ]   [ • • | | | | | • • ]   [ ( ✓ ) ]
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Cercle Gauche Lavande (#F5EAFF) + Croix Noire
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

            // Pilule Centrale Lavande (#F5EAFF) + 11 barres/points noirs animés
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

            // Cercle Droit Violet Foncé (#67298A) + Coche Blanche
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
                    contentDescription = "Écrire dans le chat",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        if (livePreview.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Surface(
                color = Color(0xEE181420),
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 4.dp
            ) {
                Text(
                    text = livePreview,
                    color = PillLavenderBg,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
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
