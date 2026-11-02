package com.jarvis.core.image

import com.jarvis.core.json.Json
import com.jarvis.core.net.NetworkException
import com.jarvis.core.net.ScriptedHttp
import com.jarvis.core.net.ScriptedHttp.Companion.bytes
import com.jarvis.core.net.ScriptedHttp.Companion.fail
import com.jarvis.core.net.ScriptedHttp.Companion.json
import com.jarvis.core.ports.*
import com.jarvis.core.settings.Keys
import com.jarvis.core.testing.InMemoryDocuments
import com.jarvis.core.testing.TestEnv
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ImagePromptsTest {
    @Test fun aspectWordsAndRatios() {
        assertEquals(1.0 to 1.0, ImagePrompts.parseAspect("square")); assertEquals(9.0 to 16.0, ImagePrompts.parseAspect("tall"))
        val r = ImagePrompts.parseAspect("16:9")!!; assertEquals(16.0 / 9.0, r.first / r.second, 1e-9)
        val b = ImagePrompts.parseAspect("4 by 3")!!; assertEquals(4.0 / 3.0, b.first / b.second, 1e-9)
        assertNull(ImagePrompts.parseAspect("")); assertNull(ImagePrompts.parseAspect("blah"))
    }

    @Test fun extremeRatiosAreClamped() {
        val r = ImagePrompts.parseAspect("30:1")!!; assertEquals(3.0, r.first / r.second, 1e-9)
        val t = ImagePrompts.parseAspect("1:30")!!; assertEquals(1 / 3.0, t.first / t.second, 1e-9)
    }

    @Test fun shapesInsideThePromptAreFoundButSceneWordsAreNot() {
        assertEquals(9.0 / 16.0, ImagePrompts.promptAspect("a cat, 9:16 aspect ratio")!!.let { it.first / it.second }, 1e-9)
        assertNotNull(ImagePrompts.promptAspect("a lake in portrait format"))
        assertNull("'wide view' describes the scene", ImagePrompts.promptAspect("a wide view of the mountains"))
    }

    @Test fun sizesAreMultiplesOf64WithAMinimum() {
        assertEquals(1024 to 1024, ImagePrompts.size(null, 1024))
        assertEquals(1024 to 576, ImagePrompts.size(16.0 to 9.0, 1024))
        assertEquals(576 to 1024, ImagePrompts.size(9.0 to 16.0, 1024))
        val (w, h) = ImagePrompts.size(3.0 to 1.0, 512); assertEquals(512, w); assertEquals(512, h)
        for (a in listOf(1.0 to 1.0, 16.0 to 9.0, 2.0 to 3.0, 3.0 to 1.0)) ImagePrompts.size(a, 1000).let { assertTrue(it.first % 64 == 0 && it.second % 64 == 0) }
    }

    @Test fun promptsAreCleanedAndStyled() {
        assertEquals("a red fox, 9:16 hmm".let { "a red fox" }, ImagePrompts.buildPrompt("generate an image of a red fox"))
        assertEquals("a red fox", ImagePrompts.buildPrompt("a red fox, 9:16 aspect ratio"))
        assertTrue(ImagePrompts.buildPrompt("a red fox", "anime").endsWith(ImagePrompts.STYLES.getValue("anime")))
        assertEquals("a red fox", ImagePrompts.buildPrompt("a red fox", "nonsense"))
    }

    @Test fun picturesAreRecognisedByTheirBytes() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        assertEquals("image/png" to "png", ImagePrompts.detectFormat(png))
        assertEquals("image/jpeg" to "jpg", ImagePrompts.detectFormat(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/webp" to "webp", ImagePrompts.detectFormat("RIFFxxxxWEBPVP8 ".toByteArray()))
        assertEquals("image/gif" to "gif", ImagePrompts.detectFormat("GIF89a".toByteArray()))
        assertNull(ImagePrompts.detectFormat("<html>not a picture</html>".toByteArray())); assertNull(ImagePrompts.detectFormat(ByteArray(0)))
    }
}

class ImageServiceTest {
    private val http = ScriptedHttp()
    private val env = TestEnv()
    private val docs = InMemoryDocuments(env.clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val svc = DefaultImageService(http, env.settings, docs, scope, env.clock, env.log, java.util.Random(7))
    private val events = ArrayList<ImageEvent>()
    init { scope.launch { svc.events.collect { events.add(it) } } }
    @After fun done() = scope.cancel()

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2, 3)
    private fun ok() = bytes(200, png, "image/png")
    private fun types() = events.map { it.type }

    @Test fun pollinationsIsTheKeylessDefault() {
        http.on("image.pollinations.ai", ok())
        val id = svc.start("a red fox", null, "", "")
        assertEquals(ImageEventType.START, events.first().type); assertEquals(ImageEventType.DONE, events.last().type)
        val f = docs.files.single(); assertEquals(ContentCategory.IMAGES, f.category); assertEquals("image/png", f.mime); assertTrue(f.name.endsWith(".png")); assertEquals("a red fox", f.source)
        assertEquals(ImageJobStatus.DONE, svc.job(id)!!.status); assertEquals(f, events.last().files.single())
        assertTrue(http.requests.single().url.contains("model=flux") && http.requests.single().url.contains("width=1024"))
    }

    @Test fun theEventSequenceIsStartStagesThenDone() {
        http.on("pollinations", ok()); svc.start("a red fox", null, "", "")
        assertEquals(ImageEventType.START, types().first()); assertEquals(ImageEventType.DONE, types().last())
        assertTrue(types().count { it == ImageEventType.STAGE } >= 2)
        assertEquals(1, events.count { it.type == ImageEventType.STAGE && it.files.isNotEmpty() })
    }

    @Test fun severalPicturesGetDistinctNames() {
        http.on("pollinations", ok()); svc.start("a red fox", 3, "", "")
        assertEquals(3, docs.files.size); assertEquals(3, docs.files.map { it.name }.toSet().size)
    }

    @Test fun theCountIsClampedToFour() {
        http.on("pollinations", ok()); svc.start("a red fox", 99, "", ""); assertEquals(4, docs.files.size)
    }

    @Test fun theAspectDecidesTheSize() {
        http.on("pollinations", ok()); svc.start("a tower", null, "", "9:16")
        val u = http.requests.single().url; assertTrue(u, u.contains("width=576") && u.contains("height=1024"))
    }

    @Test fun aShapeTypedInThePromptIsHonouredAndRemovedFromIt() {
        http.on("pollinations", ok()); svc.start("a tower, 16:9 aspect ratio", null, "", "")
        val u = http.requests.single().url; assertTrue(u, u.contains("width=1024") && u.contains("height=576")); assertFalse(u.contains("aspect"))
    }

    @Test fun huggingFaceIsTriedFirstWhenAKeyIsSaved() {
        env.settings.set(Keys.HUGGINGFACE_API_KEY, "hf_SECRET123")
        http.on("router.huggingface.co", ok()); svc.start("a red fox", null, "", "")
        assertEquals(0, http.count("pollinations")); assertEquals("Bearer hf_SECRET123", http.requests.single().headers["Authorization"])
        assertTrue(http.requests.single().url.contains("FLUX.1-schnell")); assertEquals("a red fox", Json.parseObjectOrNull(http.bodies("huggingface").single())!!["inputs"])
        assertEquals(ImageEventType.DONE, types().last())
    }

    @Test fun aRejectedHuggingFaceKeyFallsBackToPollinations() {
        env.settings.set(Keys.HUGGINGFACE_API_KEY, "hf_SECRET123")
        http.on("router.huggingface.co", json(401, """{"error":"Invalid token"}""")).on("pollinations", ok())
        svc.start("a red fox", null, "", "")
        assertEquals(1, http.count("router.huggingface.co")); assertEquals(ImageEventType.DONE, types().last()); assertEquals(1, docs.files.size)
    }

    @Test fun exhaustedCreditsFallBackToPollinations() {
        env.settings.set(Keys.HUGGINGFACE_API_KEY, "hf_SECRET123")
        http.on("router.huggingface.co", json(402, """{"error":"credits"}""")).on("pollinations", ok())
        svc.start("a red fox", null, "", "")
        assertEquals("only ladder models the HTTP router serves are tried", 1, http.count("router.huggingface.co")); assertEquals(1, docs.files.size)
    }

    @Test fun everyServiceFailingIsReportedWithAReasonAndNoFile() {
        http.on("pollinations", json(429, """{"error":"busy"}"""))
        val id = svc.start("a red fox", null, "", "")
        val e = events.last(); assertEquals(ImageEventType.ERROR, e.type); assertTrue(e.message, e.message.contains("rate limiting"))
        assertTrue(docs.files.isEmpty()); assertEquals(ImageJobStatus.ERROR, svc.job(id)!!.status)
    }

    @Test fun noNetworkAtAllIsReported() {
        http.on("pollinations", fail())
        svc.start("a red fox", null, "", "")
        assertEquals(ImageEventType.ERROR, types().last()); assertTrue(events.last().message.contains("can't reach"))
    }

    @Test fun aServiceThatAnswersWithoutAPictureIsNotSavedAsOne() {
        http.on("pollinations", bytes(200, "<html>oops</html>".toByteArray(), "image/png"))
        svc.start("a red fox", null, "", "")
        assertTrue(docs.files.isEmpty()); assertTrue(events.last().message.contains("isn't a picture"))
    }

    @Test fun aHtmlErrorPageIsNotAPicture() {
        http.on("pollinations", ScriptedHttp.text(200, "<html>Rate limited</html>"))
        svc.start("a red fox", null, "", ""); assertTrue(docs.files.isEmpty()); assertEquals(ImageEventType.ERROR, types().last())
    }

    @Test fun aStorageFailureIsReported() {
        val bad = object : DocumentStore by docs {
            override suspend fun saveBytes(category: ContentCategory, title: String, bytes: ByteArray, mime: String, extension: String, source: String?): StoredFile = throw IllegalStateException("disk full")
        }
        val s = DefaultImageService(http.on("pollinations", ok()), env.settings, bad, scope, env.clock, env.log)
        val got = ArrayList<ImageEvent>(); scope.launch { s.events.collect { got.add(it) } }
        s.start("a red fox", null, "", "")
        assertEquals(ImageEventType.ERROR, got.last().type); assertTrue(got.last().message.contains("couldn't save"))
    }

    @Test fun huggingFaceOnlyModeNeedsAKey() {
        env.settings.set(Keys.IMAGE_PROVIDER, "huggingface")
        val (ok, why) = svc.available(); assertFalse(ok); assertTrue(why.contains("no key"))
        env.settings.set(Keys.HUGGINGFACE_API_KEY, "hf_x"); assertTrue(svc.available().first)
        env.settings.set(Keys.IMAGE_PROVIDER, "auto"); env.settings.set(Keys.HUGGINGFACE_API_KEY, ""); assertTrue(svc.available().first)
    }

    @Test fun pollinationsOnlyModeNeverCallsHuggingFace() {
        env.settings.set(Keys.IMAGE_PROVIDER, "pollinations"); env.settings.set(Keys.HUGGINGFACE_API_KEY, "hf_x")
        http.on("pollinations", ok()); svc.start("a red fox", null, "", ""); assertEquals(0, http.count("huggingface"))
    }

    @Test fun anApiKeyForPollinationsGoesInAHeaderNotTheUrl() {
        env.settings.set(Keys.POLLINATIONS_API_KEY, "pol_SECRET9")
        http.on("gen.pollinations.ai", ok()); svc.start("a red fox", null, "", "")
        val r = http.requests.single(); assertEquals("Bearer pol_SECRET9", r.headers["Authorization"]); assertFalse(r.url.contains("SECRET"))
    }

    @Test fun theModelLadderFollowsTheQualitySetting() {
        assertEquals(ImagePrompts.LADDER.getValue("best"), svc.ladder())
        env.settings.set(Keys.IMAGE_QUALITY, "fast"); assertEquals(ImagePrompts.LADDER.getValue("fast"), svc.ladder())
        env.settings.set(Keys.IMAGE_MODEL, "my/model"); assertEquals(listOf("my/model"), svc.ladder())
    }

    @Test fun aCustomModelIsUsedAsIs() {
        env.settings.set(Keys.IMAGE_MODEL, "my/model"); env.settings.set(Keys.HUGGINGFACE_API_KEY, "hf_x")
        http.on("huggingface", ok()); svc.start("a red fox", null, "", ""); assertTrue(http.requests.single().url.endsWith("/models/my/model"))
    }

    @Test fun aJobCanBeCancelled() {
        http.on("pollinations", ScriptedHttp.Reply.Hang())
        val id = svc.start("a red fox", null, "", "")
        assertEquals(ImageJobStatus.WORKING, svc.job(id)!!.status)
        svc.cancel(id)
        assertEquals(ImageJobStatus.CANCELLED, svc.job(id)!!.status); assertTrue(docs.files.isEmpty()); assertTrue(types().none { it == ImageEventType.ERROR })
    }

    @Test fun cancelledTransportsAreNotReportedAsServiceFailures() {
        http.on("pollinations", fail(NetworkException.Kind.CANCELLED))
        val id = svc.start("a red fox", null, "", "")
        assertEquals(ImageJobStatus.CANCELLED, svc.job(id)!!.status); assertTrue(types().none { it == ImageEventType.ERROR })
    }

    @Test fun keysNeverAppearInTheLogOrTheEvents() {
        env.settings.set(Keys.HUGGINGFACE_API_KEY, "hf_SECRET123")
        http.on("router.huggingface.co", json(401, """{"error":"Invalid token hf_SECRET123"}""")).on("pollinations", fail())
        svc.start("a red fox", null, "", "")
        val all = env.logLines.joinToString("\n") + events.joinToString("\n") { it.message }
        assertFalse(all.contains("SECRET"))
    }

    @Test fun twoJobsKeepTheirOwnState() {
        http.on("pollinations", ok())
        val a = svc.start("one", null, "", ""); val b = svc.start("two", null, "", "")
        assertNotEquals(a, b); assertEquals("one", svc.job(a)!!.prompt); assertEquals("two", svc.job(b)!!.prompt)
    }
}
