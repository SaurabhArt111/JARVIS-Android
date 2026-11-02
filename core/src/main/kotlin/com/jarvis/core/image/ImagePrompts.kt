package com.jarvis.core.image

/** Shape and prompt helpers (port of the pure functions in Backend/ImageGeneration.py). */
object ImagePrompts {
    val LADDER = mapOf(
        "best" to listOf("Qwen/Qwen-Image-2512", "black-forest-labs/FLUX.1-dev", "Tongyi-MAI/Z-Image-Turbo", "black-forest-labs/FLUX.1-schnell"),
        "fast" to listOf("black-forest-labs/FLUX.1-schnell", "Tongyi-MAI/Z-Image-Turbo"),
    )
    /** Models the plain hf-inference router can serve over HTTPS (no Python hub client on Android). */
    val HTTP_LADDER = listOf("black-forest-labs/FLUX.1-schnell", "stabilityai/stable-diffusion-xl-base-1.0")

    val STYLES = mapOf(
        "photo" to "photorealistic, natural lighting, sharp focus, high detail, 85mm photograph",
        "cinematic" to "cinematic still, dramatic lighting, shallow depth of field, anamorphic, film grain",
        "anime" to "anime illustration, clean line art, vibrant colours, detailed background",
        "3d" to "high quality 3D render, soft studio lighting, subsurface scattering, octane render",
        "watercolor" to "delicate watercolor painting, soft washes, visible paper texture",
        "illustration" to "polished digital illustration, rich colours, detailed",
        "pixel" to "pixel art, 16-bit, crisp pixels, limited palette",
    )

    private val ASPECTS = linkedMapOf(
        "square" to (1.0 to 1.0), "wide" to (16.0 to 9.0), "tall" to (9.0 to 16.0), "landscape" to (16.0 to 9.0), "portrait" to (9.0 to 16.0),
        "vertical" to (9.0 to 16.0), "horizontal" to (16.0 to 9.0), "widescreen" to (16.0 to 9.0), "cinematic" to (16.0 to 9.0),
        "phone" to (9.0 to 16.0), "story" to (9.0 to 16.0), "reel" to (9.0 to 16.0), "banner" to (3.0 to 1.0), "poster" to (2.0 to 3.0), "1:1" to (1.0 to 1.0),
    )
    private val RATIO = Regex("(?<![\\d.:])(\\d{1,2}(?:\\.\\d+)?)\\s*(?:[:/]|x|by)\\s*(\\d{1,2}(?:\\.\\d+)?)(?![\\d:])", RegexOption.IGNORE_CASE)
    private val RATIO_PHRASE = Regex(
        "[,;]?\\s*(?:in\\s+)?(?:an?\\s+)?(?:\\d{1,2}\\s*(?:[:/]|x|by)\\s*\\d{1,2})\\s*(?:aspect\\s*ratio|ratio|format)?|" +
            "[,;]?\\s*(?:in\\s+)?(?:a\\s+)?(?:portrait|landscape|vertical|horizontal|square)\\s+(?:aspect\\s*ratio|format|orientation|mode)",
        RegexOption.IGNORE_CASE,
    )
    private val PROMPT_WORD = Regex("\\b(portrait|landscape|vertical|horizontal|square)\\s+(?:aspect\\s*ratio|format|orientation|mode)\\b", RegexOption.IGNORE_CASE)
    private val LEAD = Regex("^(?:generate|create|make|draw)\\s+(?:an?\\s+)?(?:image|picture|photo|pic)\\s+(?:of\\s+)?", RegexOption.IGNORE_CASE)

    /** '9:16', '16 by 9', 'tall', 'portrait' ... -> (a, b), or null when no shape was asked for. Ratios are kept within 1:3 .. 3:1. */
    fun parseAspect(text: String?): Pair<Double, Double>? {
        val t = text?.trim()?.lowercase().orEmpty()
        if (t.isEmpty()) return null
        ASPECTS[t]?.let { return it }
        RATIO.find(t)?.let { m ->
            val a = m.groupValues[1].toDouble(); val b = m.groupValues[2].toDouble()
            if (a > 0 && b > 0) return (a / b).coerceIn(1 / 3.0, 3.0) to 1.0
        }
        for ((word, ab) in ASPECTS) if (Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(t)) return ab
        return null
    }

    /** A shape typed inside the prompt itself ('... 9:16 aspect ratio', '... in portrait format') - never plain 'wide view', which describes the scene. */
    fun promptAspect(prompt: String?): Pair<Double, Double>? {
        val p = prompt.orEmpty()
        RATIO.find(p)?.let { return parseAspect(it.value) }
        return PROMPT_WORD.find(p)?.let { parseAspect(it.groupValues[1]) }
    }

    /** Width/height around [side] px on the long edge, multiples of 64, at least 512. */
    fun size(aspect: Pair<Double, Double>?, side: Int): Pair<Int, Int> {
        val (a, b) = aspect ?: (1.0 to 1.0)
        val w: Double; val h: Double
        if (a >= b) { w = side.toDouble(); h = side * b / a } else { h = side.toDouble(); w = side * a / b }
        fun snap(v: Double) = maxOf(512, Math.rint(v / 64.0).toInt() * 64)
        return snap(w) to snap(h)
    }

    fun buildPrompt(prompt: String, style: String = ""): String {
        val stripped = RATIO_PHRASE.replace(prompt, "").trim().ifEmpty { prompt } // the shape goes in the size, not into the picture
        val p = LEAD.replace(stripped.trim(), "").ifEmpty { stripped.trim() }
        val st = STYLES[style.lowercase().trim()]
        return if (st != null) "$p, $st" else p
    }

    /** PNG / JPEG / WEBP / GIF by magic bytes, or null when the bytes are not a picture. Returns (mime, extension). */
    fun detectFormat(b: ByteArray): Pair<String, String>? = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png" to "png"
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg" to "jpg"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp" to "webp"
        b.size >= 6 && String(b, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "image/gif" to "gif"
        else -> null
    }
}
