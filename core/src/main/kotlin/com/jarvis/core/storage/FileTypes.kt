package com.jarvis.core.storage

import com.jarvis.core.ports.ContentCategory

/** Allow-list of file types JARVIS creates, with magic-byte validation for binary formats. */
object FileTypes {
    data class Type(val ext: String, val mime: String, val categories: Set<ContentCategory>)

    private val ALL = listOf(
        Type("txt", "text/plain", setOf(ContentCategory.NOTES, ContentCategory.GENERATED_TEXT, ContentCategory.CONVERSATIONS, ContentCategory.EXPORTS, ContentCategory.OTHER)),
        Type("md", "text/markdown", setOf(ContentCategory.NOTES, ContentCategory.GENERATED_TEXT, ContentCategory.CONVERSATIONS, ContentCategory.EXPORTS, ContentCategory.OTHER)),
        Type("json", "application/json", setOf(ContentCategory.EXPORTS, ContentCategory.CONVERSATIONS, ContentCategory.OTHER)),
        Type("csv", "text/csv", setOf(ContentCategory.EXPORTS, ContentCategory.OTHER)),
        Type("html", "text/html", setOf(ContentCategory.GENERATED_TEXT, ContentCategory.EXPORTS, ContentCategory.OTHER)),
        Type("png", "image/png", setOf(ContentCategory.IMAGES, ContentCategory.OTHER)),
        Type("jpg", "image/jpeg", setOf(ContentCategory.IMAGES, ContentCategory.OTHER)),
        Type("jpeg", "image/jpeg", setOf(ContentCategory.IMAGES, ContentCategory.OTHER)),
        Type("webp", "image/webp", setOf(ContentCategory.IMAGES, ContentCategory.OTHER)),
        Type("wav", "audio/wav", setOf(ContentCategory.AUDIO, ContentCategory.OTHER)),
        Type("mp3", "audio/mpeg", setOf(ContentCategory.AUDIO, ContentCategory.OTHER)),
        Type("m4a", "audio/mp4", setOf(ContentCategory.AUDIO, ContentCategory.OTHER)),
        Type("ogg", "audio/ogg", setOf(ContentCategory.AUDIO, ContentCategory.OTHER)),
        Type("pdf", "application/pdf", setOf(ContentCategory.EXPORTS, ContentCategory.OTHER)),
    )

    fun byExtension(ext: String): Type? = ALL.firstOrNull { it.ext == SafeFileName.extension(ext) }
    fun mimeFor(ext: String): String? = byExtension(ext)?.mime

    /** Extension and category must agree with the allow-list (e.g. no ".html" in IMAGES). */
    fun isAllowed(category: ContentCategory, ext: String): Boolean = byExtension(ext)?.categories?.contains(category) == true

    private fun startsWith(b: ByteArray, vararg sig: Int, offset: Int = 0): Boolean =
        b.size >= offset + sig.size && sig.indices.all { (b[offset + it].toInt() and 0xFF) == sig[it] }

    /** Checks that [bytes] really look like [ext] (PNG/JPEG/WEBP/WAV/MP3/OGG/M4A/PDF magic; text types must be valid, NUL-free UTF-8). */
    fun looksLike(ext: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return when (SafeFileName.extension(ext)) {
            "png" -> startsWith(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            "jpg", "jpeg" -> startsWith(bytes, 0xFF, 0xD8, 0xFF)
            "webp" -> startsWith(bytes, 0x52, 0x49, 0x46, 0x46) && startsWith(bytes, 0x57, 0x45, 0x42, 0x50, offset = 8)
            "wav" -> startsWith(bytes, 0x52, 0x49, 0x46, 0x46) && startsWith(bytes, 0x57, 0x41, 0x56, 0x45, offset = 8)
            "mp3" -> startsWith(bytes, 0x49, 0x44, 0x33) || (startsWith(bytes, 0xFF) && (bytes[1].toInt() and 0xE0) == 0xE0)
            "ogg" -> startsWith(bytes, 0x4F, 0x67, 0x67, 0x53)
            "m4a" -> startsWith(bytes, 0x66, 0x74, 0x79, 0x70, offset = 4)
            "pdf" -> startsWith(bytes, 0x25, 0x50, 0x44, 0x46)
            "txt", "md", "json", "csv", "html" -> isCleanUtf8(bytes)
            else -> false
        }
    }

    private fun isCleanUtf8(b: ByteArray): Boolean {
        if (b.any { it.toInt() == 0 }) return false
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b))
            true
        } catch (e: java.nio.charset.CharacterCodingException) {
            false
        }
    }
}
