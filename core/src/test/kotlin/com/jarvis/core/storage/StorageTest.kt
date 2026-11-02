package com.jarvis.core.storage

import com.jarvis.core.ports.ContentCategory
import org.junit.Assert.*
import org.junit.Test

class StorageTest {
    @Test fun stemIsSafeAndNeverEmpty() {
        assertEquals("plan_for_q3_budget", SafeFileName.stem("Plan for Q3 / budget?"))
        assertEquals("cafe", SafeFileName.stem("Café"))
        assertEquals("file", SafeFileName.stem("???"))
        assertEquals("con_file", SafeFileName.stem("CON"))
        assertEquals("passwd", SafeFileName.stem("../../etc/passwd").takeLast(6))
        assertTrue(SafeFileName.stem("a".repeat(300)).length <= 60)
    }

    @Test fun uniqueNeverOverwrites() {
        assertEquals("note.txt", SafeFileName.unique("Note", "txt", emptyList()))
        assertEquals("note_2.txt", SafeFileName.unique("Note", ".TXT", listOf("note.txt")))
        assertEquals("note_3.txt", SafeFileName.unique("Note", "txt", listOf("NOTE.txt", "note_2.txt")))
    }

    @Test fun safeNameRejectsTraversal() {
        assertTrue(SafeFileName.isSafeName("a_b.txt"))
        assertFalse(SafeFileName.isSafeName("../x"))
        assertFalse(SafeFileName.isSafeName("a/b"))
        assertFalse(SafeFileName.isSafeName(".hidden"))
        assertFalse(SafeFileName.isSafeName(""))
    }

    @Test fun allowListMatchesCategory() {
        assertTrue(FileTypes.isAllowed(ContentCategory.IMAGES, "PNG"))
        assertFalse(FileTypes.isAllowed(ContentCategory.IMAGES, "html"))
        assertFalse(FileTypes.isAllowed(ContentCategory.NOTES, "exe"))
        assertEquals("image/jpeg", FileTypes.mimeFor("jpeg"))
    }

    @Test fun magicBytesValidated() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)
        assertTrue(FileTypes.looksLike("png", png))
        assertFalse(FileTypes.looksLike("jpg", png))
        assertFalse(FileTypes.looksLike("png", "<html>".toByteArray()))
        assertTrue(FileTypes.looksLike("txt", "héllo".toByteArray()))
        assertFalse(FileTypes.looksLike("txt", byteArrayOf(0x41, 0, 0x42)))
        assertFalse(FileTypes.looksLike("txt", byteArrayOf(0xC3.toByte(), 0x28)))
        assertFalse(FileTypes.looksLike("txt", ByteArray(0)))
        val webp = "RIFF....WEBP".toByteArray()
        assertTrue(FileTypes.looksLike("webp", webp))
        assertFalse(FileTypes.looksLike("wav", webp))
    }
}
