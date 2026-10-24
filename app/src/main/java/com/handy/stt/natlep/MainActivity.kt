package com.handy.stt.natlep

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val BgDark = Color(0xFF131118)
val CardDark = Color(0xFF1F1B28)
val BorderSubtle = Color(0xFF332C42)
val HandyPurple = Color(0xFF67298A)
val HandyLavender = Color(0xFFF5EAFF)
val AccentMint = Color(0xFF4ADE80)
val TextLight = Color(0xFFF3EFFA)
val TextMuted = Color(0xFFA39BB5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val modelManager = WhisperModelManager(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = HandyPurple,
                    secondary = HandyLavender,
                    background = BgDark,
                    surface = CardDark
                )
            ) {
                HandyMainScreen(
                    context = this,
                    modelManager = modelManager
                )
            }
        }
    }
}

enum class HandyTab(val labelFr: String, val labelEn: String) {
    FLOATING("Dictée & Hors-App", "Floating & Dictation"),
    MODELS("Modèles Whisper", "Whisper Models"),
    SETTINGS("Appareil Limité", "Low-RAM Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandyMainScreen(
    context: Context,
    modelManager: WhisperModelManager
) {
    val scope = rememberCoroutineScope()
    var isFr by remember { mutableStateOf(true) }
    var currentTab by remember { mutableStateOf(HandyTab.FLOATING) }

    var activeModelId by remember { mutableStateOf(modelManager.getActiveModelId()) }
    var lowRamMode by remember { mutableStateOf(modelManager.isLowRamEcoMode()) }
    var cpuThreads by remember { mutableIntStateOf(modelManager.getThreadsCount()) }
    var sttLanguage by remember { mutableStateOf(modelManager.getLanguageCode()) }
    var historyList by remember { mutableStateOf(modelManager.getHistory()) }

    var downloadingModelId by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedMb by remember { mutableLongStateOf(0L) }
    var totalMb by remember { mutableLongStateOf(0L) }

    var hasAudioPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasAudioPerm = perms[Manifest.permission.RECORD_AUDIO] == true
    }

    fun launchFloatingPill() {
        if (!hasAudioPerm) {
            val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permLauncher.launch(list.toTypedArray())
            return
        }

        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(
                context,
                if (isFr) "Autorisez 'Afficher par-dessus les autres applis' pour la pilule vocale"
                else "Please allow 'Display over other apps' for the floating pill",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
            return
        }

        val serviceIntent = Intent(context, FloatingSttService::class.java).apply {
            action = "ACTION_START_RECORDING"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    fun stopFloatingPill() {
        val serviceIntent = Intent(context, FloatingSttService::class.java).apply {
            action = "ACTION_STOP_SERVICE"
        }
        context.startService(serviceIntent)
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardDark),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(HandyPurple),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = HandyLavender)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Handy Speech-to-Text",
                                color = TextLight,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isFr) "Modèle actif : $activeModelId (Optimisé Low-RAM)"
                                else "Active model: $activeModelId (Low-RAM Optimized)",
                                color = AccentMint,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BgDark)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                            .clickable { isFr = !isFr }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isFr) "FR" else "EN",
                            color = HandyLavender,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = CardDark) {
                HandyTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = {
                            currentTab = tab
                            historyList = modelManager.getHistory()
                        },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    HandyTab.FLOATING -> Icons.Default.MicExternalOn
                                    HandyTab.MODELS -> Icons.Default.Memory
                                    HandyTab.SETTINGS -> Icons.Default.Tune
                                },
                                contentDescription = null
                            )
                        },
                        label = {
                            Text(if (isFr) tab.labelFr else tab.labelEn, fontSize = 11.sp)
                        }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when (currentTab) {
                HandyTab.FLOATING -> {
                    // Carte d'aperçu fidèle à la photo + Lancement de la pilule hors-application
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(CardDark)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isFr) "Barre Vocale Flottante (Hors-App)"
                            else "Floating Voice Bar (Outside App)",
                            color = TextLight,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (isFr) "Identique à votre capture : apparaît par-dessus toutes vos applications pour dicter en 1 clic."
                            else "Identical to your screenshot: floats over all your apps for 1-tap dictation.",
                            color = TextMuted,
                            fontSize = 12.sp
                        )

                        Spacer(Modifier.height(16.dp))

                        // Aperçu visuel exact de la barre de la photo
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(PillLavenderBg)
                                    .clickable { stopFloatingPill() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = WaveDarkInk)
                            }

                            Box(
                                modifier = Modifier
                                    .width(130.dp)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(PillLavenderBg)
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val demoHeights = listOf(4.dp, 5.dp, 9.dp, 13.dp, 16.dp, 18.dp, 16.dp, 13.dp, 9.dp, 5.dp, 4.dp)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    demoHeights.forEach { h ->
                                        Box(
                                            modifier = Modifier
                                                .width(4.dp)
                                                .height(h)
                                                .clip(RoundedCornerShape(50))
                                                .background(WaveDarkInk)
                                        )
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(CheckPurpleBg)
                                    .clickable { launchFloatingPill() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { launchFloatingPill() },
                                colors = ButtonDefaults.buttonColors(containerColor = HandyPurple),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PictureInPictureAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (isFr) "Activer Hors-App" else "Launch Overlay",
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = { stopFloatingPill() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isFr) "Fermer la barre" else "Stop Overlay", color = TextLight)
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Bouton pour activer l'injection directe dans les champs de texte externes
                        TextButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        ) {
                            Icon(Icons.Default.KeyboardVoice, contentDescription = null, tint = HandyLavender, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isFr) "Activer l'auto-écriture directe dans les autres applis (Accessibilité)"
                                else "Enable direct text auto-paste in external apps (Accessibility)",
                                color = HandyLavender,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Historique des transcriptions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isFr) "Historique des dictées" else "Dictation History",
                            color = TextLight,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row {
                            IconButton(onClick = { historyList = modelManager.getHistory() }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = TextMuted)
                            }
                            if (historyList.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        modelManager.clearHistory()
                                        historyList = emptyList()
                                    }
                                ) {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = TextMuted)
                                }
                            }
                        }
                    }

                    if (historyList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isFr) "Aucune dictée pour l'instant. Lancez la barre flottante et parlez !"
                                else "No dictations yet. Launch the floating bar and speak!",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(historyList) { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(CardDark)
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                                        .clickable {
                                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cb.setPrimaryClip(ClipData.newPlainText("Handy STT", item.text))
                                            Toast.makeText(context, "Copié !", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.text,
                                            color = TextLight,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "${sdf.format(Date(item.timestamp))}  •  ${item.modelUsed}  •  ${item.durationMs / 1000.0}s",
                                            color = TextMuted,
                                            fontSize = 10.sp
                                        )
                                    }
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copier", tint = HandyLavender, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                HandyTab.MODELS -> {
                    Text(
                        text = if (isFr) "Tous les Modèles Whisper (GGML Quantifiés)"
                        else "All Whisper Models (Quantized GGML)",
                        color = TextLight,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (isFr) "Les modèles Q5_1 utilisent 60% moins de RAM pour tourner sur les appareils limités."
                        else "Q5_1 quantized models use 60% less RAM to run smoothly on limited devices.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(modelManager.catalog, key = { it.id }) { spec ->
                            var isInstalled by remember(spec.id, downloadingModelId) {
                                mutableStateOf(modelManager.isModelDownloaded(spec.id))
                            }
                            val isSelected = activeModelId == spec.id
                            val isDownloadingThis = downloadingModelId == spec.id

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(CardDark)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) AccentMint else BorderSubtle,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = spec.title,
                                                color = TextLight,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (spec.recommendedForLowRam) {
                                                Spacer(Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(AccentMint.copy(alpha = 0.2f))
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text("LOW-RAM", color = AccentMint, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                        Text(
                                            text = "${spec.quantTag}  •  ${spec.sizeMb} MB  •  RAM: ~${spec.ramRequiredMb} MB  •  ${spec.speedRating}",
                                            color = HandyLavender,
                                            fontSize = 11.sp
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = if (isFr) spec.descriptionFr else spec.descriptionEn,
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                if (isDownloadingThis) {
                                    Spacer(Modifier.height(10.dp))
                                    LinearProgressIndicator(
                                        progress = { downloadProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = AccentMint
                                    )
                                    Text(
                                        text = "${(downloadProgress * 100).toInt()}% ($downloadedMb MB / $totalMb MB)",
                                        color = AccentMint,
                                        fontSize = 11.sp
                                    )
                                }

                                Spacer(Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isInstalled) {
                                        TextButton(
                                            onClick = {
                                                modelManager.deleteModel(spec.id)
                                                isInstalled = false
                                            }
                                        ) {
                                            Text(if (isFr) "Supprimer" else "Delete", color = TextMuted, fontSize = 12.sp)
                                        }
                                        Spacer(Modifier.width(8.dp))
                                    }

                                    Button(
                                        enabled = downloadingModelId == null,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSelected) AccentMint else HandyPurple
                                        ),
                                        onClick = {
                                            if (isInstalled) {
                                                modelManager.setActiveModelId(spec.id)
                                                activeModelId = spec.id
                                            } else {
                                                downloadingModelId = spec.id
                                                downloadProgress = 0f
                                                scope.launch {
                                                    val ok = modelManager.downloadModel(spec) { ratio, dl, tot ->
                                                        downloadProgress = ratio
                                                        downloadedMb = dl
                                                        totalMb = tot
                                                    }
                                                    downloadingModelId = null
                                                    isInstalled = ok
                                                    if (ok) {
                                                        modelManager.setActiveModelId(spec.id)
                                                        activeModelId = spec.id
                                                    }
                                                }
                                            }
                                        }
                                    ) {
                                        Text(
                                            text = when {
                                                isSelected && isInstalled -> if (isFr) "✓ Actif" else "✓ Active"
                                                isInstalled -> if (isFr) "Utiliser ce modèle" else "Use Model"
                                                else -> if (isFr) "Télécharger (${spec.sizeMb} Mo)" else "Download (${spec.sizeMb} MB)"
                                            },
                                            color = if (isSelected) WaveDarkInk else Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HandyTab.SETTINGS -> {
                    Text(
                        text = if (isFr) "Optimisations Appareil Limité (Low-RAM)"
                        else "Limited Device Optimizations (Low-RAM)",
                        color = TextLight,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(12.dp))

                    // Option 1 : Mode Éco Low-RAM
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardDark)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isFr) "Mode Éco RAM & Libération Immédiate"
                                else "Low-RAM Eco Mode & Instant Unload",
                                color = TextLight,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isFr) "Libère les tampons audio dès la fin de la dictée et réduit l'échantillonnage à 16kHz Mono."
                                else "Frees audio buffers right after dictation and locks sampling to 16kHz Mono.",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = lowRamMode,
                            onCheckedChange = {
                                lowRamMode = it
                                modelManager.setLowRamEcoMode(it)
                            }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Option 2 : Nombre de threads CPU ARM
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardDark)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = if (isFr) "Threads CPU pour l'inférence Whisper : $cpuThreads coeurs"
                            else "CPU Threads for Whisper inference: $cpuThreads cores",
                            color = TextLight,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isFr) "2 à 4 threads recommandé sur tablette/téléphone pour éviter la surchauffe."
                            else "2 to 4 threads recommended on tablet/phone to prevent thermal throttling.",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                        Slider(
                            value = cpuThreads.toFloat(),
                            onValueChange = {
                                cpuThreads = it.toInt()
                                modelManager.setThreadsCount(cpuThreads)
                            },
                            valueRange = 1f..8f,
                            steps = 6
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Option 3 : Langue de dictée
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardDark)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = if (isFr) "Langue de reconnaissance vocale" else "Speech Recognition Language",
                            color = TextLight,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("fr-FR" to "Français", "en-US" to "English", "es-ES" to "Español").forEach { (code, name) ->
                                val selected = sttLanguage == code
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) HandyPurple else BgDark)
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                                        .clickable {
                                            sttLanguage = code
                                            modelManager.setLanguageCode(code)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(name, color = TextLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
