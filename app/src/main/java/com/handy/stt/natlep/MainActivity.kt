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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Palette Ultra-Moderne Obsidian & Lavender (inspirée du widget de la photo)
val ObsidianBg = Color(0xFF0E0C14)
val SurfaceGlass = Color(0xFF191524)
val SurfaceElevated = Color(0xFF231E32)
val BorderLavenderSubtle = Color(0xFF362E4D)
val HandyDeepPurple = Color(0xFF67298A)
val HandySoftLavender = Color(0xFFF5EAFF)
val HandyAccentViolet = Color(0xFFB678E6)
val StatusEmerald = Color(0xFF34D399)
val StatusAmber = Color(0xFFFBBF24)
val TextPrimaryWhite = Color(0xFFF7F4FC)
val TextSecondaryMuted = Color(0xFFA79EB8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val modelManager = WhisperModelManager(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = HandyDeepPurple,
                    secondary = HandySoftLavender,
                    background = ObsidianBg,
                    surface = SurfaceGlass
                )
            ) {
                HandyRedesignedApp(
                    context = this,
                    modelManager = modelManager
                )
            }
        }
    }
}

enum class NavSection(val titleFr: String, val titleEn: String, val icon: ImageVector) {
    STUDIO("Accueil & Test", "Home & Playground", Icons.Default.AutoAwesome),
    MODELS("Modèles Whisper", "Whisper Models", Icons.Default.DownloadForOffline),
    ENGINE("Appareil Limité", "Low-RAM Engine", Icons.Default.Memory)
}

@Composable
fun HandyRedesignedApp(
    context: Context,
    modelManager: WhisperModelManager
) {
    val scope = rememberCoroutineScope()
    var isFr by remember { mutableStateOf(true) }
    var currentSection by remember { mutableStateOf(NavSection.STUDIO) }

    val isAccessibilityActive by HandyTextInjectorService.isServiceConnected.collectAsState()
    val isFieldFocused by HandyTextInjectorService.hasEditableFieldFocused.collectAsState()
    val isOverlayServiceRunning by FloatingSttService.isRunning.collectAsState()

    var activeModelId by remember { mutableStateOf(modelManager.getActiveModelId()) }
    var autoShowOnChat by remember { mutableStateOf(modelManager.isAutoShowOnChat()) }
    var lowRamMode by remember { mutableStateOf(modelManager.isLowRamEcoMode()) }
    var cpuThreads by remember { mutableIntStateOf(modelManager.getThreadsCount()) }
    var sttLanguage by remember { mutableStateOf(modelManager.getLanguageCode()) }
    var historyList by remember { mutableStateOf(modelManager.getHistory()) }

    // Zone de chat bac à sable intégrée pour tester immédiatement l'écriture directe sans quitter l'app
    var sandboxChatText by remember { mutableStateOf("") }

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

    fun startOrStopFloatingOverlay() {
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
                if (isFr) "Autorisez la superposition d'écran pour afficher la pilule Handy"
                else "Allow overlay permission to display the Handy pill",
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
        containerColor = ObsidianBg,
        bottomBar = {
            // Barre de navigation flottante moderne style capsule
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(16.dp, RoundedCornerShape(30.dp))
                        .clip(RoundedCornerShape(30.dp))
                        .background(SurfaceGlass)
                        .border(1.dp, BorderLavenderSubtle, RoundedCornerShape(30.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NavSection.entries.forEach { sec ->
                        val selected = currentSection == sec
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(22.dp))
                                .background(if (selected) HandyDeepPurple else Color.Transparent)
                                .clickable {
                                    currentSection = sec
                                    historyList = modelManager.getHistory()
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = sec.icon,
                                contentDescription = null,
                                tint = if (selected) HandySoftLavender else TextSecondaryMuted,
                                modifier = Modifier.size(19.dp)
                            )
                            AnimatedVisibility(visible = selected) {
                                Row {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (isFr) sec.titleFr else sec.titleEn,
                                        color = HandySoftLavender,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // En-tête Design avec badge de modèle et bouton FR/EN
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(HandyDeepPurple, Color(0xFF9C4DCC))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = HandySoftLavender,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Handy",
                                color = TextPrimaryWhite,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(HandySoftLavender)
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "DIRECT CHAT",
                                    color = WaveDarkInk,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                        Text(
                            text = if (isFr) "Zéro presse-papier • Écrit uniquement dans vos zones de texte"
                            else "Zero clipboard • Writes strictly inside focused text fields",
                            color = TextSecondaryMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceElevated)
                        .border(1.dp, BorderLavenderSubtle, RoundedCornerShape(10.dp))
                        .clickable { isFr = !isFr }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = if (isFr) "FR" else "EN",
                        color = HandySoftLavender,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            when (currentSection) {
                NavSection.STUDIO -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Carte Héro : Vitrine du Widget de la photo + Contrôle Maître
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFF28173B), SurfaceGlass)
                                    )
                                )
                                .border(1.dp, BorderLavenderSubtle, RoundedCornerShape(24.dp))
                                .padding(20.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                // Reproduction fidèle et interactive du widget de la photo
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(vertical = 8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .shadow(8.dp, CircleShape)
                                            .clip(CircleShape)
                                            .background(PillLavenderBg),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = null,
                                            tint = WaveDarkInk,
                                            modifier = Modifier.size(25.dp)
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .width(148.dp)
                                            .height(54.dp)
                                            .shadow(8.dp, RoundedCornerShape(27.dp))
                                            .clip(RoundedCornerShape(27.dp))
                                            .background(PillLavenderBg)
                                            .padding(horizontal = 18.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val bars = listOf(5.dp, 6.dp, 10.dp, 14.dp, 18.dp, 20.dp, 18.dp, 14.dp, 10.dp, 6.dp, 5.dp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            bars.forEach { h ->
                                                Box(
                                                    modifier = Modifier
                                                        .width(4.5.dp)
                                                        .height(h)
                                                        .clip(RoundedCornerShape(50))
                                                        .background(WaveDarkInk)
                                                )
                                            }
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .shadow(8.dp, CircleShape)
                                            .clip(CircleShape)
                                            .background(CheckPurpleBg),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(25.dp)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(10.dp))

                                Text(
                                    text = if (isFr) "Verrouillé sur les zones d'écriture"
                                    else "Locked to Editable Input Fields",
                                    color = HandySoftLavender,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = if (isFr) "La dictée s'active uniquement quand votre curseur est dans un chat, un message ou une barre de recherche. Rien n'est copié dans le presse-papier."
                                    else "Dictation triggers only when your cursor is inside a chat, message or search bar. Nothing is ever copied to the clipboard.",
                                    color = TextSecondaryMuted,
                                    fontSize = 12.sp
                                )

                                Spacer(Modifier.height(16.dp))

                                Button(
                                    onClick = { startOrStopFloatingOverlay() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isOverlayServiceRunning) Color(0xFF3B2447) else HandyDeepPurple
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isOverlayServiceRunning) Icons.Default.VisibilityOff else Icons.Default.MicExternalOn,
                                        contentDescription = null,
                                        tint = HandySoftLavender
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (isOverlayServiceRunning) {
                                            if (isFr) "Désactiver la bulle flottante" else "Stop Floating Pill"
                                        } else {
                                            if (isFr) "Lancer la bulle Handy Hors-App" else "Launch Floating Handy Pill"
                                        },
                                        color = HandySoftLavender,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }

                        // Grille des 2 Permissions requises (Accessibilité + Superposition)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StatusPermissionCard(
                                modifier = Modifier.weight(1f),
                                title = if (isFr) "Détecteur de Chat" else "Chat Detector",
                                subtitle = if (isAccessibilityActive) {
                                    if (isFr) "Actif (Prêt à écrire)" else "Active (Ready)"
                                } else {
                                    if (isFr) "Requis : Activer ici" else "Required: Tap here"
                                },
                                isOk = isAccessibilityActive,
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            )

                            val overlayOk = Settings.canDrawOverlays(context)
                            StatusPermissionCard(
                                modifier = Modifier.weight(1f),
                                title = if (isFr) "Bulle Hors-App" else "Overlay Pill",
                                subtitle = if (overlayOk) {
                                    if (isFr) "Autorisé" else "Granted"
                                } else {
                                    if (isFr) "Autoriser l'écran" else "Grant Overlay"
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

                        // Zone de test en direct ("Chat Sandbox") pour essayer immédiatement sans quitter l'application
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(SurfaceGlass)
                                .border(1.dp, BorderLavenderSubtle, RoundedCornerShape(18.dp))
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.ChatBubbleOutline,
                                        contentDescription = null,
                                        tint = HandyAccentViolet,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (isFr) "Zone de Chat Test (Essayez ici !)"
                                        else "Live Chat Sandbox (Try it here!)",
                                        color = TextPrimaryWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isFieldFocused) StatusEmerald.copy(alpha = 0.18f)
                                            else SurfaceElevated
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (isFieldFocused) {
                                            if (isFr) "● Champ détecté" else "● Input Focused"
                                        } else {
                                            if (isFr) "○ Aucun champ" else "○ No Input"
                                        },
                                        color = if (isFieldFocused) StatusEmerald else TextSecondaryMuted,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            OutlinedTextField(
                                value = sandboxChatText,
                                onValueChange = { sandboxChatText = it },
                                placeholder = {
                                    Text(
                                        text = if (isFr) "Touchez ici pour ouvrir le curseur, puis parlez avec la bulle Handy..."
                                        else "Tap here to focus cursor, then speak using the Handy pill...",
                                        fontSize = 12.sp,
                                        color = TextSecondaryMuted
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        // Historique des messages injectés dans les chats
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isFr) "Derniers messages écrits en direct"
                                else "Recent Direct Chat Injections",
                                color = TextPrimaryWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (historyList.isNotEmpty()) {
                                Text(
                                    text = if (isFr) "Effacer" else "Clear",
                                    color = HandyAccentViolet,
                                    fontSize = 12.sp,
                                    modifier = Modifier.clickable {
                                        modelManager.clearHistory()
                                        historyList = emptyList()
                                    }
                                )
                            }
                        }

                        if (historyList.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(SurfaceGlass)
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isFr) "Vos messages écrits par la voix dans vos chats apparaîtront ici."
                                    else "Messages dictated directly into your chats will appear here.",
                                    color = TextSecondaryMuted,
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            val sdf = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                            historyList.take(8).forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(SurfaceGlass)
                                        .border(1.dp, BorderLavenderSubtle, RoundedCornerShape(14.dp))
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.text,
                                            color = TextPrimaryWhite,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "${sdf.format(Date(entry.timestamp))} • Injecté dans ${entry.targetApp.substringAfterLast('.')} • ${entry.modelUsed}",
                                            color = TextSecondaryMuted,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                    }
                }

                NavSection.MODELS -> {
                    // En-tête de diagnostic RAM de l'appareil
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceElevated)
                            .border(1.dp, BorderLavenderSubtle, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isFr) "Mémoire RAM détectée : ${deviceTotalRamMb} Mo"
                                else "Detected Device RAM: ${deviceTotalRamMb} MB",
                                color = HandySoftLavender,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isFr) "Les modèles Q5_1 sont compressés sur 5 bits pour tourner sans ralentir votre appareil."
                                else "Q5_1 models are 5-bit quantized to run smoothly without slowing down your device.",
                                color = TextSecondaryMuted,
                                fontSize = 11.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = StatusEmerald,
                            modifier = Modifier.size(26.dp)
                        )
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
                                    .background(SurfaceGlass)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) HandySoftLavender else BorderLavenderSubtle,
                                        shape = RoundedCornerShape(18.dp)
                                    )
                                    .padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = spec.title,
                                            color = TextPrimaryWhite,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(SurfaceElevated)
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = spec.badge,
                                                color = HandyAccentViolet,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    if (spec.isLowRamRecommended) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(StatusEmerald.copy(alpha = 0.16f))
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = "ÉCO RAM",
                                                color = StatusEmerald,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                Text(
                                    text = if (isFr) spec.descFr else spec.descEn,
                                    color = TextSecondaryMuted,
                                    fontSize = 12.sp
                                )

                                Spacer(Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "📦 ${spec.sizeMb} MB   •   ⚡ ${spec.speedFactor}   •   🧠 ~${spec.ramRequiredMb} MB RAM",
                                        color = HandySoftLavender,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                if (isDownloadingThis) {
                                    Spacer(Modifier.height(10.dp))
                                    LinearProgressIndicator(
                                        progress = { downloadProgress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = HandySoftLavender
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "${(downloadProgress * 100).toInt()}% ($downloadedMb MB / $totalMb MB)",
                                        color = HandySoftLavender,
                                        fontSize = 11.sp
                                    )
                                }

                                Spacer(Modifier.height(12.dp))

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
                                                color = TextSecondaryMuted,
                                                fontSize = 12.sp
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                    }

                                    Button(
                                        enabled = downloadingModelId == null,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSelected) HandySoftLavender else HandyDeepPurple
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
                                                isSelected && isInstalled -> if (isFr) "✓ Modèle Actif" else "✓ Active Model"
                                                isInstalled -> if (isFr) "Activer" else "Select"
                                                else -> if (isFr) "Télécharger (${spec.sizeMb} Mo)" else "Download (${spec.sizeMb} MB)"
                                            },
                                            color = if (isSelected) WaveDarkInk else TextPrimaryWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                NavSection.ENGINE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Option 1 : Apparition automatique quand on clique dans un chat
                        SettingToggleCard(
                            title = if (isFr) "Apparition auto dans les chats"
                            else "Auto-Popup Inside Chats",
                            desc = if (isFr) "Affiche automatiquement la bulle Handy dès que vous touchez une zone d'écriture (WhatsApp, Discord, Messages...)."
                            else "Automatically displays the Handy pill whenever you tap an editable text field.",
                            checked = autoShowOnChat,
                            onCheckedChange = {
                                autoShowOnChat = it
                                modelManager.setAutoShowOnChat(it)
                            }
                        )

                        // Option 2 : Mode Éco RAM pour appareils limités
                        SettingToggleCard(
                            title = if (isFr) "Mode Ultra Low-RAM (Appareil Limité)"
                            else "Ultra Low-RAM Mode (Limited Device)",
                            desc = if (isFr) "Purge immédiatement la mémoire audio après chaque phrase écrite et évite tout stockage lourd en RAM."
                            else "Immediately purges audio buffers after each dictated sentence to save RAM.",
                            checked = lowRamMode,
                            onCheckedChange = {
                                lowRamMode = it
                                modelManager.setLowRamEcoMode(it)
                            }
                        )

                        // Option 3 : Nombre de threads CPU
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(SurfaceGlass)
                                .border(1.dp, BorderLavenderSubtle, RoundedCornerShape(18.dp))
                                .padding(16.dp)
                        ) {
                            Text(
                                text = if (isFr) "Coeurs CPU alloués à Whisper : $cpuThreads threads"
                                else "CPU Cores allocated to Whisper: $cpuThreads threads",
                                color = TextPrimaryWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (isFr) "Sur un appareil limité, gardez 2 threads pour éviter toute chauffe et garder l'interface fluide."
                                else "On a limited device, keep 2 threads to prevent overheating and UI lag.",
                                color = TextSecondaryMuted,
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

                        // Option 4 : Langue de dictée
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(SurfaceGlass)
                                .border(1.dp, BorderLavenderSubtle, RoundedCornerShape(18.dp))
                                .padding(16.dp)
                        ) {
                            Text(
                                text = if (isFr) "Langue de dictée vocale" else "Voice Dictation Language",
                                color = TextPrimaryWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
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
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (active) HandyDeepPurple else SurfaceElevated)
                                            .border(
                                                1.dp,
                                                if (active) HandySoftLavender else BorderLavenderSubtle,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                sttLanguage = code
                                                modelManager.setLanguageCode(code)
                                            }
                                            .padding(horizontal = 14.dp, vertical = 9.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (active) HandySoftLavender else TextSecondaryMuted,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
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
fun StatusPermissionCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    isOk: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceGlass)
            .border(
                width = 1.dp,
                color = if (isOk) StatusEmerald.copy(alpha = 0.5f) else StatusAmber.copy(alpha = 0.6f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (isOk) StatusEmerald else StatusAmber,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                color = TextPrimaryWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = if (isOk) StatusEmerald else StatusAmber,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SettingToggleCard(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceGlass)
            .border(1.dp, BorderLavenderSubtle, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = TextPrimaryWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = desc,
                color = TextSecondaryMuted,
                fontSize = 11.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
