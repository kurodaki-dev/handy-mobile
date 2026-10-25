package com.handy.stt.natlep

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class WhisperModelSpec(
    val id: String,
    val title: String,
    val badge: String,
    val sizeMb: Int,
    val ramRequiredMb: Int,
    val speedFactor: String,
    val descFr: String,
    val descEn: String,
    val downloadUrl: String,
    val isLowRamRecommended: Boolean = false
)

data class TranscriptionEntry(
    val timestamp: Long,
    val text: String,
    val targetApp: String,
    val modelUsed: String,
    val durationMs: Long
)

class WhisperModelManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("handy_stt_prefs", Context.MODE_PRIVATE)

    val modelsDir: File = File(context.filesDir, "whisper_models").apply { mkdirs() }

    val catalog: List<WhisperModelSpec> = listOf(
        WhisperModelSpec(
            id = "ggml-tiny-q5_1",
            title = "Whisper Tiny",
            badge = "Q5_1 • Ultra-Light",
            sizeMb = 31,
            ramRequiredMb = 120,
            speedFactor = "10x",
            descFr = "Conçu pour appareils limités. Latence quasi nulle, très faible empreinte RAM.",
            descEn = "Built for limited devices. Near-zero latency, tiny RAM footprint.",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
            isLowRamRecommended = true
        ),
        WhisperModelSpec(
            id = "ggml-tiny.en-q5_1",
            title = "Whisper Tiny.en",
            badge = "Q5_1 • English",
            sizeMb = 31,
            ramRequiredMb = 115,
            speedFactor = "11x",
            descFr = "Modèle spécialisé anglais ultra-léger pour petits processeurs mobiles.",
            descEn = "Ultra-light English-only model for modest mobile CPUs.",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin",
            isLowRamRecommended = true
        ),
        WhisperModelSpec(
            id = "ggml-base-q5_1",
            title = "Whisper Base",
            badge = "Q5_1 • Équilibré",
            sizeMb = 57,
            ramRequiredMb = 190,
            speedFactor = "6x",
            descFr = "L'équilibre idéal entre rapidité et compréhension du Français / Anglais.",
            descEn = "Ideal balance between speed and French / English accuracy.",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
            isLowRamRecommended = true
        ),
        WhisperModelSpec(
            id = "ggml-small-q5_1",
            title = "Whisper Small",
            badge = "Q5_1 • Précis",
            sizeMb = 181,
            ramRequiredMb = 380,
            speedFactor = "2.5x",
            descFr = "Ponctuation naturelle et excellente reconnaissance dans les chats bruyants.",
            descEn = "Natural punctuation and great accuracy in noisy environments.",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            isLowRamRecommended = false
        ),
        WhisperModelSpec(
            id = "ggml-medium-q5_0",
            title = "Whisper Medium",
            badge = "Q5_0 • Studio",
            sizeMb = 515,
            ramRequiredMb = 860,
            speedFactor = "1.1x",
            descFr = "Haute précision multilingue pour appareils disposant d'au moins 6 Go de RAM.",
            descEn = "High multilingual accuracy for devices with at least 6 GB RAM.",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q5_0.bin",
            isLowRamRecommended = false
        ),
        WhisperModelSpec(
            id = "ggml-large-v3-turbo-q5_0",
            title = "Whisper Large v3 Turbo",
            badge = "Q5_0 • Turbo v3",
            sizeMb = 547,
            ramRequiredMb = 910,
            speedFactor = "1.8x",
            descFr = "Le tout dernier modèle OpenAI Whisper Large v3 distillé et accéléré.",
            descEn = "Latest distilled and accelerated OpenAI Whisper Large v3 model.",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
            isLowRamRecommended = false
        )
    )

    fun isUserMasterEnabled(): Boolean = prefs.getBoolean("user_master_enabled", false)

    fun setUserMasterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("user_master_enabled", enabled).apply()
    }

    fun isModelDownloaded(modelId: String): Boolean {
        val file = File(modelsDir, "$modelId.bin")
        return file.exists() && file.length() > 1024 * 1024L
    }

    fun getModelFile(modelId: String): File = File(modelsDir, "$modelId.bin")

    fun deleteModel(modelId: String) {
        getModelFile(modelId).delete()
    }

    fun getActiveModelId(): String =
        prefs.getString("active_model_id", "ggml-tiny-q5_1") ?: "ggml-tiny-q5_1"

    fun setActiveModelId(id: String) {
        prefs.edit().putString("active_model_id", id).apply()
    }

    fun isHideBubbleWhenNoInput(): Boolean = prefs.getBoolean("hide_when_no_input", true)

    fun setHideBubbleWhenNoInput(enabled: Boolean) {
        prefs.edit().putBoolean("hide_when_no_input", enabled).apply()
    }

    fun isLowRamEcoMode(): Boolean = prefs.getBoolean("low_ram_eco_mode", true)

    fun setLowRamEcoMode(enabled: Boolean) {
        prefs.edit().putBoolean("low_ram_eco_mode", enabled).apply()
    }

    fun getThreadsCount(): Int = prefs.getInt("cpu_threads", 2)

    fun setThreadsCount(threads: Int) {
        prefs.edit().putInt("cpu_threads", threads.coerceIn(1, 8)).apply()
    }

    fun getLanguageCode(): String = prefs.getString("stt_lang", "fr-FR") ?: "fr-FR"

    fun setLanguageCode(code: String) {
        prefs.edit().putString("stt_lang", code).apply()
    }

    fun getHistory(): List<TranscriptionEntry> {
        val raw = prefs.getString("history_v2", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n---\n").mapNotNull { block ->
            val p = block.split("|", limit = 5)
            if (p.size == 5) {
                TranscriptionEntry(
                    timestamp = p[0].toLongOrNull() ?: 0L,
                    targetApp = p[1],
                    modelUsed = p[2],
                    durationMs = p[3].toLongOrNull() ?: 0L,
                    text = p[4]
                )
            } else null
        }
    }

    fun addHistoryEntry(text: String, targetApp: String, modelUsed: String, durationMs: Long) {
        if (text.isBlank()) return
        val current = getHistory().toMutableList()
        current.add(0, TranscriptionEntry(System.currentTimeMillis(), text.trim(), targetApp, modelUsed, durationMs))
        val serialized = current.take(40).joinToString("\n---\n") {
            "${it.timestamp}|${it.targetApp}|${it.modelUsed}|${it.durationMs}|${it.text}"
        }
        prefs.edit().putString("history_v2", serialized).apply()
    }

    fun clearHistory() {
        prefs.edit().remove("history_v2").apply()
    }

    suspend fun downloadModel(
        spec: WhisperModelSpec,
        onProgress: (Float, Long, Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val tempFile = File(modelsDir, "${spec.id}.bin.part")
        val finalFile = getModelFile(spec.id)
        try {
            var currentUrl = spec.downloadUrl
            var conn: HttpURLConnection
            var redirects = 0

            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("User-Agent", "HandyMobile/4.0")
                }
                val code = conn.responseCode
                if (code in 301..308 && redirects < 6) {
                    currentUrl = conn.getHeaderField("Location") ?: break
                    redirects++
                } else {
                    break
                }
            }

            if (conn.responseCode != 200) return@withContext false

            val totalBytes = conn.contentLengthLong.coerceAtLeast(spec.sizeMb * 1024L * 1024L)
            var downloaded = 0L

            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        val ratio = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        onProgress(ratio, downloaded / (1024 * 1024), totalBytes / (1024 * 1024))
                    }
                }
            }

            if (finalFile.exists()) finalFile.delete()
            tempFile.renameTo(finalFile)
            true
        } catch (e: Exception) {
            tempFile.delete()
            false
        }
    }
}
