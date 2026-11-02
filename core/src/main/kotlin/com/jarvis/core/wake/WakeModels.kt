package com.jarvis.core.wake

import java.io.InputStream
import java.security.MessageDigest

/** One downloadable openWakeWord model file with the SHA-256 it must have. */
data class WakeModelSpec(val fileName: String, val url: String, val sha256: String, val approxBytes: Long)

/**
 * The three ONNX files the neural wake word needs. They are NOT shipped inside the app or the source ZIP: openWakeWord's pre-trained models are licensed
 * CC BY-NC-SA 4.0 (non-commercial, share-alike), so the user downloads them on purpose from the project's GitHub release, and every file is checked
 * against the SHA-256 pinned here before it is used.
 */
object WakeModels {
    const val RELEASE_BASE = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
    const val LICENSE_NOTE = "openWakeWord's pre-trained models are CC BY-NC-SA 4.0 (non-commercial use only). Source: https://github.com/dscripka/openWakeWord"
    const val PHRASE = "hey jarvis"

    val MELSPEC = WakeModelSpec("melspectrogram.onnx", "$RELEASE_BASE/melspectrogram.onnx", "ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f", 1_090_000)
    val EMBEDDING = WakeModelSpec("embedding_model.onnx", "$RELEASE_BASE/embedding_model.onnx", "70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f", 1_330_000)
    val WAKE = WakeModelSpec("hey_jarvis_v0.1.onnx", "$RELEASE_BASE/hey_jarvis_v0.1.onnx", "94a13cfe60075b132f6a472e7e462e8123ee70861bc3fb58434a73712ee0d2cb", 1_270_000)
    val ALL: List<WakeModelSpec> = listOf(MELSPEC, EMBEDDING, WAKE)

    /** Only HTTPS, and only GitHub (the release page and the CDN host it redirects to). */
    fun isAllowedDownloadUrl(url: String): Boolean {
        val u = try { java.net.URI(url) } catch (_: Exception) { return false }
        if (u.scheme?.lowercase() != "https") return false
        val host = u.host?.lowercase() ?: return false
        if (u.userInfo != null) return false
        return host == "github.com" || host.endsWith(".githubusercontent.com") || host == "githubusercontent.com"
    }

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(spec: WakeModelSpec, input: InputStream): Boolean = sha256Hex(input).equals(spec.sha256, ignoreCase = true)

    /** Which of [ALL] are not yet present (by file name). */
    fun missing(present: Set<String>): List<WakeModelSpec> = ALL.filter { it.fileName !in present }
}

/** Which wake-word backend to run, decided from the setting and what the phone/app actually has. */
enum class WakeBackend { NONE, NEURAL, SPEECH }

data class WakeChoice(val backend: WakeBackend, val reason: String, val needsModels: Boolean = false)

object WakeEngineChooser {
    /**
     * @param setting Keys.WAKE_ENGINE: auto | openwakeword | speech | off
     * @param modelsPresent all three verified model files are installed
     * @param onDeviceSpeech an ON-DEVICE speech recogniser exists (Android 12+)
     * @param anySpeech some speech recogniser exists (possibly cloud backed)
     *
     * "auto" never silently falls back to a cloud recogniser running all day: it uses the neural engine when its models are installed, otherwise
     * an on-device recogniser, otherwise it says the models are needed.
     */
    fun choose(setting: String?, modelsPresent: Boolean, onDeviceSpeech: Boolean, anySpeech: Boolean): WakeChoice {
        return when (setting?.trim()?.lowercase()) {
            "off" -> WakeChoice(WakeBackend.NONE, "Wake word is turned off.")
            "openwakeword", "neural" ->
                if (modelsPresent) WakeChoice(WakeBackend.NEURAL, "Neural wake word (openWakeWord).")
                else WakeChoice(WakeBackend.NONE, "The wake word models are not downloaded yet.", needsModels = true)
            "speech" ->
                if (anySpeech) WakeChoice(WakeBackend.SPEECH, "Speech-recogniser wake word.")
                else WakeChoice(WakeBackend.NONE, "No speech recogniser is available on this phone.")
            else -> when {
                modelsPresent -> WakeChoice(WakeBackend.NEURAL, "Neural wake word (openWakeWord).")
                onDeviceSpeech -> WakeChoice(WakeBackend.SPEECH, "On-device speech-recogniser wake word (download the wake models for lower battery use).")
                else -> WakeChoice(WakeBackend.NONE, "Download the wake word models to listen for the wake word.", needsModels = true)
            }
        }
    }
}
