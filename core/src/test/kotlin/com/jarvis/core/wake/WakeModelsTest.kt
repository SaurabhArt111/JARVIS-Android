package com.jarvis.core.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeModelsTest {
    @Test
    fun sha256OfKnownInputs() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", WakeModels.sha256Hex("".byteInputStream()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", WakeModels.sha256Hex("abc".byteInputStream()))
        // longer than the read buffer, to exercise the loop
        val big = ByteArray(200_000) { (it % 251).toByte() }
        assertEquals(64, WakeModels.sha256Hex(big.inputStream()).length)
    }

    @Test
    fun matchesIsCaseInsensitiveAndRejectsTamperedBytes() {
        val spec = WakeModelSpec("a", "https://github.com/x", "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", 3)
        assertTrue(WakeModels.matches(spec, "abc".byteInputStream()))
        assertFalse(WakeModels.matches(spec, "abd".byteInputStream()))
    }

    @Test
    fun pinsLookLikeSha256AndUrlsAreHttpsGithub() {
        for (m in WakeModels.ALL) {
            assertTrue(m.fileName, Regex("[0-9a-f]{64}").matches(m.sha256))
            assertTrue(m.url, WakeModels.isAllowedDownloadUrl(m.url))
            assertTrue(m.url.endsWith(m.fileName))
        }
        assertEquals(3, WakeModels.ALL.map { it.sha256 }.toSet().size)
    }

    @Test
    fun downloadUrlAllowlist() {
        assertTrue(WakeModels.isAllowedDownloadUrl("https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/x.onnx"))
        assertTrue(WakeModels.isAllowedDownloadUrl("https://objects.githubusercontent.com/abc?x=1"))
        assertTrue(WakeModels.isAllowedDownloadUrl("https://release-assets.githubusercontent.com/abc"))
        assertFalse("plain http", WakeModels.isAllowedDownloadUrl("http://github.com/x"))
        assertFalse("other host", WakeModels.isAllowedDownloadUrl("https://example.com/x.onnx"))
        assertFalse("look-alike", WakeModels.isAllowedDownloadUrl("https://github.com.evil.example/x"))
        assertFalse("look-alike 2", WakeModels.isAllowedDownloadUrl("https://evilgithubusercontent.com/x"))
        assertFalse("credentials", WakeModels.isAllowedDownloadUrl("https://user:pw@github.com/x"))
        assertFalse("garbage", WakeModels.isAllowedDownloadUrl("not a url"))
        assertFalse("file", WakeModels.isAllowedDownloadUrl("file:///sdcard/x"))
    }

    @Test
    fun missingListsOnlyAbsentFiles() {
        assertEquals(3, WakeModels.missing(emptySet()).size)
        assertEquals(listOf(WakeModels.WAKE), WakeModels.missing(setOf("melspectrogram.onnx", "embedding_model.onnx")))
        assertTrue(WakeModels.missing(WakeModels.ALL.map { it.fileName }.toSet()).isEmpty())
    }

    @Test
    fun autoPrefersNeuralThenOnDeviceSpeechThenAsksForModels() {
        assertEquals(WakeBackend.NEURAL, WakeEngineChooser.choose("auto", true, true, true).backend)
        assertEquals(WakeBackend.SPEECH, WakeEngineChooser.choose("auto", false, true, true).backend)
        val c = WakeEngineChooser.choose("auto", false, false, true)
        assertEquals("auto must not silently use a cloud recogniser all day", WakeBackend.NONE, c.backend)
        assertTrue(c.needsModels)
        assertEquals(WakeBackend.NEURAL, WakeEngineChooser.choose(null, true, false, false).backend)
    }

    @Test
    fun explicitChoicesAreRespectedOrExplained() {
        assertEquals(WakeBackend.NONE, WakeEngineChooser.choose("off", true, true, true).backend)
        assertEquals(WakeBackend.NEURAL, WakeEngineChooser.choose("openwakeword", true, false, false).backend)
        val noModels = WakeEngineChooser.choose("openwakeword", false, true, true)
        assertEquals(WakeBackend.NONE, noModels.backend)
        assertTrue(noModels.needsModels)
        assertEquals(WakeBackend.SPEECH, WakeEngineChooser.choose("speech", false, false, true).backend)
        assertEquals(WakeBackend.NONE, WakeEngineChooser.choose("speech", true, false, false).backend)
        assertEquals(WakeBackend.NEURAL, WakeEngineChooser.choose(" OpenWakeWord ", true, false, false).backend)
    }
}
