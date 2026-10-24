package com.handy.stt.natlep

import android.Manifest
import android.app.ActivityManager
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Palette Officielle Handy (identique à la photo de la mascotte Gant & texte rose sur fond crème)
val HandyCreamBg = Color(0xFFFFFDF0)
val HandyCardWhite = Color(0xFFFFFFFF)
val HandySoftCreamCard = Color(0xFFF7F3DF)
val HandyPink = Color(0xFFFF9CE3)
val HandyDeepPink = Color(0xFFE86BC5)
val HandyInkOutline = Color(0xFF2B1E23)
val HandyMutedBrown = Color(0xFF6E5D63)
val HandyMintOk = Color(0xFF2ECC71)
val HandyWarnOrange = Color(0xFFF39C12)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val modelManager = WhisperModelManager(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = HandyPink,
                    onPrimary = HandyInkOutline,
                    secondary = CheckPurpleBg,
                    background = HandyCreamBg,
                    surface = HandyCardWhite,
                    onSurface = HandyInkOutline
                )
            ) {
                HandyCartoonApp(
                    context = this,
                    modelManager = modelManager
                )
            }
        }
    }
}

/**
 * Texte bulle rose avec contour épais brun/noir exactement comme le logo "handy" de la photo !
 */
@Composable
fun HandyBubbleLogoText(
    text: String = "handy",
    fontSize: TextUnit = 38.sp
) {
    Box(contentAlignment = Alignment.Center) {
        // Contour épais noir/brun (#2B1E23)
        Text(
            text = text,
            style = TextStyle(
                color = HandyInkOutline,
                fontSize = fontSize,
                fontWeight = FontWeight.Black,
                drawStyle = Stroke(
                    width = 14f,
                    join = StrokeJoin.Round
                )
            )
        )
        // Intérieur rose bonbon (#FF9CE3)
        Text(
            text = text,
            style = TextStyle(
                color = HandyPink,
                fontSize = fontSize,
                fontWeight = FontWeight.Black
            )
        )
    }
}

enum class HandySection(val labelFr: String, val labelEn: String, val icon: ImageVector) {
    HOME("Accueil", "Home", Icons.Default.Mic),
    MODELS("Modèles Whisper", "Whisper Models", Icons.Default.Download),
    SETTINGS("Réglages", "Settings", Icons.Default.Tune)
}

@Composable
fun HandyCartoonApp(
    context: Context,
    modelManager: WhisperModelManager
) {
    val scope = rememberCoroutineScope()
    var isFr by remember { mutableStateOf(true) }
    var currentSection by remember { mutableStateOf(HandySection.HOME) }

    val isAccessibilityActive by HandyTextInjectorService.isServiceConnected.collectAsState()
    val isFieldFocused by HandyTextInjectorService.hasEditableFieldFocused.collectAsState()
    val isOverlayServiceRunning by FloatingSttService.isRunning.collectAsState()

    var activeModelId by remember { mutableStateOf(modelManager.getActiveModelId()) }
    var hideWhenNoInput by remember { mutableStateOf(modelManager.isHideBubbleWhenNoInput()) }
    var lowRamMode by remember { mutableStateOf(modelManager.isLowRamEcoMode()) }
    var cpuThreads by remember { mutableIntStateOf(modelManager.getThreadsCount()) }
    var sttLanguage by remember { mutableStateOf(modelManager.getLanguageCode()) }
    var historyList by remember { mutableStateOf(modelManager.getHistory()) }

    var testInputText by remember { mutableStateOf("") }

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

    val deviceTotalRamMb = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        (memInfo.totalMem / (1024 * 1024)).toInt()
    }

    fun toggleFloatingService() {
        if (isOverlayServiceRunning) {
            context.startService(Intent(context, FloatingSttService::class.java).apply {
                action = "ACTION_STOP_SERVICE"
            })
            return
        }

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
                if (isFr) "Autorisez l'affichage par-dessus les autres applications"
                else "Please grant Display over other apps permission",
                Toast.LENGTH_LONG
            ).show()
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            )
            return
        }

        val serviceIntent = Intent(context, FloatingSttService::class.java).apply {
            action = "ACTION_SHOW_STANDBY_PILL"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    Scaffold(
        containerColor = HandyCreamBg,
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(HandyCardWhite)
                        .border(2.5.dp, HandyInkOutline, RoundedCornerShape(28.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HandySection.entries.forEach { sec ->
                        val selected = currentSection == sec
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (selected) HandyPink else Color.Transparent)
                                .then(
                                    if (selected) Modifier.border(2.dp, HandyInkOutline, RoundedCornerShape(20.dp))
                                    else Modifier
                                )
                                .clickable {
                                    currentSection = sec
                                    historyList = modelManager.getHistory()
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = sec.icon,
                                contentDescription = null,
                                tint = HandyInkOutline,
                                modifier = Modifier.size(20.dp)
                            )
                            AnimatedVisibility(visible = selected) {
                                Row {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (isFr) sec.labelFr else sec.labelEn,
                                        color = HandyInkOutline,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(10.dp))

            // En-tête officiel avec la Mascotte Gant + Texte Bulle Rose "handy"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "Handy Mascot",
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(HandySoftCreamCard)
                            .border(2.5.dp, HandyInkOutline, RoundedCornerShape(16.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        HandyBubbleLogoText(text = "handy", fontSize = 32.sp)
                        Text(
                            text = if (isFr) "Dictée vocale directe dans vos zones de texte"
                            else "Direct voice typing inside text input fields",
                            color = HandyMutedBrown,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(HandyPink)
                        .border(2.dp, HandyInkOutline, RoundedCornerShape(12.dp))
                        .clickable { isFr = !isFr }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isFr) "FR" else "EN",
                        color = HandyInkOutline,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            when (currentSection) {
                HandySection.HOME -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Carte Principale Style Handy Cartoon
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(HandyCardWhite)
                                .border(2.5.dp, HandyInkOutline, RoundedCornerShape(24.dp))
                                .padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Aperçu Bulle Inactive (Micro) -> Barre Active (Croix + Onde + Check)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Bulle Inactive : Microphone
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(HandyPink)
                                        .border(2.dp, HandyInkOutline, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Mic, contentDescription = null, tint = HandyInkOutline)
                                }

                                Icon(Icons.Default.ArrowForward, contentDescription = null, tint = HandyMutedBrown)

                                // Barre Active : [ X ] [ ••|||||•• ] [ ✓ ]
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(PillLavenderBg)
                                        .border(1.5.dp, HandyInkOutline, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, tint = WaveDarkInk, modifier = Modifier.size(20.dp))
                                }

                                Box(
                                    modifier = Modifier
                                        .width(105.dp)
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(22.dp))
                                        .background(PillLavenderBg)
                                        .border(1.5.dp, HandyInkOutline, RoundedCornerShape(22.dp))
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val demoBars = listOf(4.dp, 5.dp, 9.dp, 13.dp, 16.dp, 18.dp, 16.dp, 13.dp, 9.dp, 5.dp, 4.dp)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        demoBars.forEach { h ->
                                            Box(
                                                modifier = Modifier
                                                    .width(3.5.dp)
                                                    .height(h)
                                                    .clip(RoundedCornerShape(50))
                                                    .background(WaveDarkInk)
                                            )
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(CheckPurpleBg)
                                        .border(1.5.dp, HandyInkOutline, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }

                            Spacer(Modifier.height(14.dp))

                            Text(
                                text = if (isFr) "Actif uniquement dans les zones de texte"
                                else "Active strictly inside text input fields",
                                color = HandyInkOutline,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (isFr) "La bulle Micro apparaît dès que vous cliquez dans un champ d'écriture et écrit directement votre voix sans copier dans le presse-papier."
                                else "The Mic bubble pops up whenever you tap a text input field and types your voice directly without touching the clipboard.",
                                color = HandyMutedBrown,
                                fontSize = 12.sp
                            )

                            Spacer(Modifier.height(14.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isOverlayServiceRunning) HandySoftCreamCard else HandyPink)
                                    .border(2.5.dp, HandyInkOutline, RoundedCornerShape(16.dp))
                                    .clickable { toggleFloatingService() },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isOverlayServiceRunning) Icons.Default.StopCircle else Icons.Default.Mic,
                                        contentDescription = null,
                                        tint = HandyInkOutline
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (isOverlayServiceRunning) {
                                            if (isFr) "Arrêter la bulle Handy" else "Stop Handy Bubble"
                                        } else {
                                            if (isFr) "Activer la bulle Micro Handy" else "Start Handy Mic Bubble"
                                        },
                                        color = HandyInkOutline,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }

                        // Cartes des 2 autorisations requises
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CartoonStatusCard(
                                modifier = Modifier.weight(1f),
                                title = if (isFr) "1. Accès Texte" else "1. Text Access",
                                status = if (isAccessibilityActive) {
                                    if (isFr) "Activé ✓" else "Enabled ✓"
                                } else {
                                    if (isFr) "Activer ici" else "Enable here"
                                },
                                isOk = isAccessibilityActive,
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            )

                            val overlayOk = Settings.canDrawOverlays(context)
                            CartoonStatusCard(
                                modifier = Modifier.weight(1f),
                                title = if (isFr) "2. Bulle Écran" else "2. Overlay",
                                status = if (overlayOk) {
                                    if (isFr) "Autorisé ✓" else "Granted ✓"
                                } else {
                                    if (isFr) "Autoriser ici" else "Grant here"
                                },
                                isOk = overlayOk,
                                onClick = {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                }
                            )
                        }

                        // Zone de saisie de texte de test direct
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(HandyCardWhite)
                                .border(2.5.dp, HandyInkOutline, RoundedCornerShape(20.dp))
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isFr) "Zone de saisie test" else "Test Text Input Box",
                                    color = HandyInkOutline,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isFieldFocused) HandyMintOk.copy(alpha = 0.2f) else HandySoftCreamCard)
                                        .border(1.5.dp, HandyInkOutline, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = if (isFieldFocused) {
                                            if (isFr) "Zone de saisie active ✓" else "Text input focused ✓"
                                        } else {
                                            if (isFr) "Touchez le champ ↓" else "Tap box below ↓"
                                        },
                                        color = HandyInkOutline,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            OutlinedTextField(
                                value = testInputText,
                                onValueChange = { testInputText = it },
                                placeholder = {
                                    Text(
                                        text = if (isFr) "Cliquez ici pour activer le curseur et tester le micro..."
                                        else "Tap here to focus cursor and test the mic bubble...",
                                        color = HandyMutedBrown,
                                        fontSize = 12.sp
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }

                        // Historique des phrases écrites
                        if (historyList.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isFr) "Dernières saisies vocales" else "Recent Voice Typings",
                                    color = HandyInkOutline,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = if (isFr) "Vider" else "Clear",
                                    color = HandyDeepPink,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        modelManager.clearHistory()
                                        historyList = emptyList()
                                    }
                                )
                            }

                            val sdf = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                            historyList.take(6).forEach { item ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(HandyCardWhite)
                                        .border(2.dp, HandyInkOutline, RoundedCornerShape(14.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = item.text,
                                        color = HandyInkOutline,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${sdf.format(Date(item.timestamp))} • ${item.modelUsed}",
                                        color = HandyMutedBrown,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                    }
                }

                HandySection.MODELS -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(HandySoftCreamCard)
                            .border(2.dp, HandyInkOutline, RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isFr) "RAM de l'appareil : ${deviceTotalRamMb} Mo"
                                else "Device RAM: ${deviceTotalRamMb} MB",
                                color = HandyInkOutline,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = if (isFr) "Modèles Whisper quantifiés Q5_1 pour appareils limités."
                                else "Quantized Q5_1 Whisper models for limited devices.",
                                color = HandyMutedBrown,
                                fontSize = 11.sp
                            )
                        }
                        Icon(Icons.Default.Memory, contentDescription = null, tint = HandyInkOutline)
                    }

                    Spacer(Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (isSelected) Color(0xFFFFF0FA) else HandyCardWhite)
                                    .border(2.5.dp, HandyInkOutline, RoundedCornerShape(18.dp))
                                    .padding(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = spec.title,
                                        color = HandyInkOutline,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(HandyPink)
                                            .border(1.5.dp, HandyInkOutline, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = spec.badge,
                                            color = HandyInkOutline,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                }

                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = if (isFr) spec.descFr else spec.descEn,
                                    color = HandyMutedBrown,
                                    fontSize = 12.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "📦 ${spec.sizeMb} MB  •  ⚡ ${spec.speedFactor}  •  🧠 ~${spec.ramRequiredMb} MB RAM",
                                    color = HandyInkOutline,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                if (isDownloadingThis) {
                                    Spacer(Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = { downloadProgress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        color = HandyDeepPink
                                    )
                                    Text(
                                        text = "${(downloadProgress * 100).toInt()}% ($downloadedMb MB / $totalMb MB)",
                                        color = HandyInkOutline,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
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
                                            Text(
                                                text = if (isFr) "Supprimer" else "Delete",
                                                color = HandyMutedBrown,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                    }

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isSelected) HandyMintOk else HandyPink)
                                            .border(2.dp, HandyInkOutline, RoundedCornerShape(12.dp))
                                            .clickable(enabled = downloadingModelId == null) {
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
                                            .padding(horizontal = 14.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = when {
                                                isSelected && isInstalled -> if (isFr) "✓ Actif" else "✓ Active"
                                                isInstalled -> if (isFr) "Choisir" else "Select"
                                                else -> if (isFr) "Télécharger (${spec.sizeMb} Mo)" else "Download (${spec.sizeMb} MB)"
                                            },
                                            color = HandyInkOutline,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HandySection.SETTINGS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CartoonToggleCard(
                            title = if (isFr) "Masquer hors des zones de saisie"
                            else "Hide Outside Text Input Fields",
                            desc = if (isFr) "La bulle Micro n'apparaît QUE lorsque votre curseur se trouve dans une zone où l'on peut écrire du texte."
                            else "The Mic bubble only appears when your cursor is inside an editable text box.",
                            checked = hideWhenNoInput,
                            onCheckedChange = {
                                hideWhenNoInput = it
                                modelManager.setHideBubbleWhenNoInput(it)
                            }
                        )

                        CartoonToggleCard(
                            title = if (isFr) "Mode Éco RAM (Appareil Limité)"
                            else "Low-RAM Eco Mode (Limited Device)",
                            desc = if (isFr) "Libère immédiatement la mémoire audio après chaque phrase dictée."
                            else "Immediately frees audio buffers right after each dictation.",
                            checked = lowRamMode,
                            onCheckedChange = {
                                lowRamMode = it
                                modelManager.setLowRamEcoMode(it)
                            }
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(HandyCardWhite)
                                .border(2.5.dp, HandyInkOutline, RoundedCornerShape(18.dp))
                                .padding(16.dp)
                        ) {
                            Text(
                                text = if (isFr) "Threads CPU Whisper : $cpuThreads coeurs"
                                else "Whisper CPU Threads: $cpuThreads cores",
                                color = HandyInkOutline,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold
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

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(HandyCardWhite)
                                .border(2.5.dp, HandyInkOutline, RoundedCornerShape(18.dp))
                                .padding(16.dp)
                        ) {
                            Text(
                                text = if (isFr) "Langue de dictée" else "Dictation Language",
                                color = HandyInkOutline,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    "fr-FR" to "🇫🇷 Français",
                                    "en-US" to "🇬🇧 English",
                                    "es-ES" to "🇪🇸 Español"
                                ).forEach { (code, label) ->
                                    val active = sttLanguage == code
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (active) HandyPink else HandySoftCreamCard)
                                            .border(2.dp, HandyInkOutline, RoundedCornerShape(12.dp))
                                            .clickable {
                                                sttLanguage = code
                                                modelManager.setLanguageCode(code)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            color = HandyInkOutline,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CartoonStatusCard(
    modifier: Modifier = Modifier,
    title: String,
    status: String,
    isOk: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (isOk) Color(0xFFEAFBF1) else Color(0xFFFFF4E5))
            .border(2.5.dp, HandyInkOutline, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Text(
            text = title,
            color = HandyInkOutline,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = status,
            color = if (isOk) Color(0xFF1B8045) else Color(0xFFB76E00),
            fontSize = 12.sp,
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
fun CartoonToggleCard(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(HandyCardWhite)
            .border(2.5.dp, HandyInkOutline, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = HandyInkOutline,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = desc,
                color = HandyMutedBrown,
                fontSize = 11.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
