package com.handy.stt.natlep

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WhisperModelSpec(
    val id: String,
    val title: String,
    val quantTag: String,
    val sizeMb: Int,
    val ramRequiredMb: Int,
    val speedRating: String,
    val descriptionFr: String,
    val descriptionEn: String,
    val downloadUrl: String,
    val recommendedForLowRam: Boolean = false
)

data class TranscriptionEntry(
    val timestamp: Long,
    val text: String,
    val modelUsed: String,
    val durationMs: Long
)

class WhisperModelManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("handy_stt_prefs", Context.MODE_PRIVATE)

    val modelsDir: File = File(context.filesDir, "whisper_models").apply { mkdirs() }
    val recordingsDir: File = File(context.cacheDir, "recordings").apply { mkdirs() }

    val catalog: List<WhisperModelSpec> = listOf(
        WhisperModelSpec(
            id = "ggml-tiny-q5_1",
            title = "Whisper Tiny (Quantized Q5_1)",
            quantTag = "Q5_1 • Ultra-Light",
            sizeMb = 31,
            ramRequiredMb = 125,
            speedRating = "10x Realtime",
            descriptionFr = "Idéal pour appareils limités. Très faible consommation RAM et batterie.",
            descriptionEn = "Ideal for limited devices. Ultra-low RAM and battery usage.",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
            recommendedForLowRam = true
        ),
        WhisperModelSpec(
            id = "ggml-tiny.en-q5_1",
            title = "Whisper Tiny.en (English Q5_1)",
            quantTag = "Q5_1 • EN Only",
            sizeMb = 31,
            ramRequiredMb = 120,
            speedRating = "11x Realtime",
            descriptionFr = "Optimisé exclusivement pour l'anglais sur petits processeurs ARM.",
            descriptionEn = "Optimized exclusively for English on small ARM CPUs.",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin",
            recommendedForLowRam = true
        ),
        WhisperModelSpec(
            id = "ggml-base-q5_1",
            title = "Whisper Base (Quantized Q5_1)",
            quantTag = "Q5_1 • Balanced",
            sizeMb = 57,
            ramRequiredMb = 195,
            speedRating = "6x Realtime",
            descriptionFr = "Le meilleur compromis rapidité / précision en Français et Anglais.",
            descriptionEn = "Best speed / accuracy balance for French and English.",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
            recommendedForLowRam = true
        ),
        WhisperModelSpec(
            id = "ggml-small-q5_1",
            title = "Whisper Small (Quantized Q5_1)",
            quantTag = "Q5_1 • High Accuracy",
            sizeMb = 181,
            ramRequiredMb = 390,
            speedRating = "2.5x Realtime",
            descriptionFr = "Excellente ponctuation et reconnaissance des termes techniques.",
            descriptionEn = "Excellent punctuation and technical vocabulary recognition.",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            recommendedForLowRam = false
        ),
        WhisperModelSpec(
            id = "ggml-medium-q5_0",
            title = "Whisper Medium (Quantized Q5_0)",
            quantTag = "Q5_0 • Pro",
            sizeMb = 515,
            ramRequiredMb = 880,
            speedRating = "1.0x Realtime",
            descriptionFr = "Qualité professionnelle proche de l'humain (appareils 6-8 Go RAM).",
            descriptionEn = "Near-human professional quality (for 6-8 GB RAM devices).",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q5_0.bin",
            recommendedForLowRam = false
        ),
        WhisperModelSpec(
            id = "ggml-large-v3-turbo-q5_0",
            title = "Whisper Large v3 Turbo (Q5_0)",
            quantTag = "Q5_0 • Turbo v3",
            sizeMb = 547,
            ramRequiredMb = 920,
            speedRating = "1.8x Realtime",
            descriptionFr = "Dernière génération OpenAI Whisper v3 distillée 4 couches décodeur.",
            descriptionEn = "Latest generation OpenAI Whisper v3 distilled 4-layer decoder.",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
            recommendedForLowRam = false
        )
    )

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

    fun getGroqOrOpenAiEndpointKey(): String = prefs.getString("whisper_api_key", "") ?: ""

    fun setGroqOrOpenAiEndpointKey(key: String) {
        prefs.edit().putString("whisper_api_key", key.trim()).apply()
    }

    fun getHistory(): List<TranscriptionEntry> {
        val raw = prefs.getString("history_lines", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n---\n").mapNotNull { block ->
            val parts = block.split("|", limit = 4)
            if (parts.size == 4) {
                TranscriptionEntry(
                    timestamp = parts[0].toLongOrNull() ?: 0L,
                    modelUsed = parts[1],
                    durationMs = parts[2].toLongOrNull() ?: 0L,
                    text = parts[3]
                )
            } else null
        }
    }

    fun addHistoryEntry(text: String, modelUsed: String, durationMs: Long) {
        if (text.isBlank()) return
        val current = getHistory().toMutableList()
        current.add(0, TranscriptionEntry(System.currentTimeMillis(), text.trim(), modelUsed, durationMs))
        val serialized = current.take(40).joinToString("\n---\n") {
            "${it.timestamp}|${it.modelUsed}|${it.durationMs}|${it.text}"
        }
        prefs.edit().putString("history_lines", serialized).apply()
    }

    fun clearHistory() {
        prefs.edit().remove("history_lines").apply()
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
                    setRequestProperty("User-Agent", "HandyMobile-STT/1.0")
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

    /**
     * Ecrit un en-tete WAV PCM 16-bit 16kHz Mono standard autour des echantillons audio enregistres
     */
    fun writeWavFile(pcmData: ByteArray, outFile: File, sampleRate: Int = 16000) {
        FileOutputStream(outFile).use { out ->
            val totalDataLen = pcmData.size + 36
            val byteRate = sampleRate * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(totalDataLen)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16) // PCM chunk size
                putShort(1) // AudioFormat PCM = 1
                putShort(1) // Mono = 1 channel
                putInt(sampleRate)
                putInt(byteRate)
                putShort(2) // BlockAlign
                putShort(16) // BitsPerSample
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcmData.size)
            }
            out.write(header.array())
            out.write(pcmData)
        }
    }
}
