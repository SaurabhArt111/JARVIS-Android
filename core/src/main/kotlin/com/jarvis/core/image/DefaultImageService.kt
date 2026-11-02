package com.jarvis.core.image

import com.jarvis.core.json.Json
import com.jarvis.core.net.HttpClient
import com.jarvis.core.net.HttpRequest
import com.jarvis.core.net.HttpResponse
import com.jarvis.core.net.NetworkException
import com.jarvis.core.net.rethrowIfCancelled
import com.jarvis.core.ports.ContentCategory
import com.jarvis.core.ports.DocumentStore
import com.jarvis.core.ports.ImageEvent
import com.jarvis.core.ports.ImageEventType
import com.jarvis.core.ports.ImageService
import com.jarvis.core.ports.StoredFile
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.util.JarvisClock
import com.jarvis.core.util.JarvisLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class ImageJobStatus { WORKING, DONE, ERROR, CANCELLED }

data class ImageJobInfo(
    val id: String, val prompt: String, val count: Int, val width: Int, val height: Int,
    val status: ImageJobStatus, val stage: String, val files: List<StoredFile>, val error: String, val provider: String, val model: String,
)

/**
 * Image generation with the best available model (port of Backend/ImageGeneration.py): Hugging Face router ladder when a key is
 * saved, then Pollinations. Pictures are stored through [DocumentStore] (never overwritten). Progress arrives on [events]:
 * START, STAGE (also used once per finished picture, with `files` set), DONE, ERROR.
 */
class DefaultImageService(
    private val http: HttpClient,
    private val settings: Settings,
    private val documents: DocumentStore,
    private val scope: CoroutineScope,
    private val clock: JarvisClock,
    private val log: JarvisLog = JarvisLog.None,
    private val random: java.util.Random = java.util.Random(),
) : ImageService {

    private class Skip(val why: String, val fatal: Boolean = false) : Exception(why)

    private val _events = MutableSharedFlow<ImageEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<ImageEvent> = _events.asSharedFlow()

    private val jobs = LinkedHashMap<String, ImageJobInfo>()
    private val running = HashMap<String, Job>()

    fun job(id: String): ImageJobInfo? = synchronized(jobs) { jobs[id] }

    fun cancel(id: String) { synchronized(running) { running[id] }?.cancel() }

    private fun hfKey() = Settings.cleanSecret(settings.get(Keys.HUGGINGFACE_API_KEY))

    override fun available(): Pair<Boolean, String> {
        val pref = settings.get(Keys.IMAGE_PROVIDER, "auto").lowercase()
        if (pref == "huggingface" && hfKey().isEmpty()) {
            return false to "Image generation is set to Hugging Face but no key is saved. Add HuggingFaceAPIKey in Settings > Images, or switch the provider to Automatic."
        }
        return true to ""
    }

    /** Models to try, best first (a model saved in Settings is used on its own). */
    fun ladder(): List<String> {
        val custom = settings.get(Keys.IMAGE_MODEL).trim()
        if (custom.isNotEmpty()) return listOf(custom)
        return ImagePrompts.LADDER.getValue(if (settings.get(Keys.IMAGE_QUALITY, "best").lowercase() == "fast") "fast" else "best")
    }

    override fun start(prompt: String, count: Int?, style: String, aspect: String): String {
        val n = (count ?: settings.int(Keys.IMAGE_COUNT, 1)).coerceIn(1, 4)
        val id = UUID.randomUUID().toString().replace("-", "").take(8)
        val side = settings.int(Keys.IMAGE_SIZE, 1024).coerceIn(512, 1536)
        val clean = prompt.trim()
        val (w, h) = ImagePrompts.size(ImagePrompts.parseAspect(aspect) ?: ImagePrompts.promptAspect(clean), side)
        update(id) { ImageJobInfo(id, clean, n, w, h, ImageJobStatus.WORKING, "Queued", emptyList(), "", "", "") }
        _events.tryEmit(ImageEvent(ImageEventType.START, id, clean, "${w}x$h"))
        val j = scope.launch {
            try {
                run(id, clean, n, style, w, h)
            } catch (e: CancellationException) {
                update(id) { it?.copy(status = ImageJobStatus.CANCELLED, stage = "Cancelled") }
                throw e
            } catch (e: Exception) {
                log.e("Image", "image job crashed", e)
                fail(id, clean, "Image generation hit an error (${e.javaClass.simpleName}).")
            } finally {
                synchronized(running) { running.remove(id) }
            }
        }
        synchronized(running) { running[id] = j }
        return id
    }

    private fun update(id: String, f: (ImageJobInfo?) -> ImageJobInfo?) {
        synchronized(jobs) { f(jobs[id])?.let { jobs[id] = it } }
    }

    private fun fail(id: String, prompt: String, message: String) {
        update(id) { it?.copy(status = ImageJobStatus.ERROR, error = message) }
        _events.tryEmit(ImageEvent(ImageEventType.ERROR, id, prompt, message))
    }

    private suspend fun run(id: String, prompt: String, n: Int, style: String, w: Int, h: Int) {
        val full = ImagePrompts.buildPrompt(prompt, style)
        val pref = settings.get(Keys.IMAGE_PROVIDER, "auto").lowercase()
        val notes = ArrayList<String>()
        val saved = ArrayList<StoredFile>()
        for (i in 1..n) {
            val seed = random.nextInt(2_000_000_000)
            val stage = if (n == 1) "Dreaming it up…" else "Dreaming up image $i of $n…"
            setStage(id, prompt, stage, "", "")
            var data: ByteArray? = null
            if (pref == "auto" || pref == "huggingface") data = tryHuggingFace(id, prompt, full, w, h, seed, notes)
            if (data == null && (pref == "auto" || pref == "pollinations")) data = tryPollinations(id, prompt, full, w, h, seed, notes)
            if (data == null) break
            val fmt = ImagePrompts.detectFormat(data)
            if (fmt == null) { notes.add("The service answered with something that isn't a picture."); break }
            val slug = prompt.lowercase().replace(Regex("[^\\w\\-]+"), "_").trim('_').take(48).ifEmpty { "image" }
            val stamp = clock.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val file = try {
                documents.saveBytes(ContentCategory.IMAGES, "${slug}_${stamp}_$i", data, fmt.first, fmt.second, prompt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notes.add("I couldn't save the picture (${e.javaClass.simpleName}).")
                break
            }
            saved.add(file)
            update(id) { it?.copy(files = saved.toList()) }
            _events.tryEmit(ImageEvent(ImageEventType.STAGE, id, prompt, "Saved image $i of $n", listOf(file)))
        }
        if (saved.isNotEmpty()) {
            update(id) { it?.copy(status = ImageJobStatus.DONE, stage = "Done") }
            _events.tryEmit(ImageEvent(ImageEventType.DONE, id, prompt, job(id)?.model.orEmpty(), saved.toList()))
        } else {
            val why = notes.firstOrNull { it.isNotEmpty() } ?: "No image service answered."
            fail(id, prompt, why + if ("key" in why.lowercase()) "" else
                " Check your internet connection, add or fix the Hugging Face key in Settings > Images, or try again in a minute.")
        }
    }

    private fun setStage(id: String, prompt: String, stage: String, provider: String, model: String) {
        update(id) { it?.copy(stage = stage, provider = provider.ifEmpty { it.provider }, model = model.ifEmpty { it.model }) }
        _events.tryEmit(ImageEvent(ImageEventType.STAGE, id, prompt, stage))
    }

    // ------------------------------------------------------------------------------------ providers
    private fun httpSkip(r: HttpResponse, who: String): Skip {
        val msg = Json.parseObjectOrNull(r.text)?.let { (Json.str(it["error"]) ?: Json.str(it["message"])).orEmpty() } ?: r.text
        return when (r.status) {
            401, 403 -> Skip("$who rejected the key (${r.status}).", fatal = true)
            402 -> Skip("$who credits are used up (402).")
            429 -> Skip("$who is rate limiting (429).")
            else -> Skip("$who error ${r.status}: ${msg.take(140)}")
        }
    }

    private fun isImage(r: HttpResponse) = r.status == 200 && (r.header("content-type")?.lowercase()?.startsWith("image") == true)

    private suspend fun tryHuggingFace(id: String, prompt: String, full: String, w: Int, h: Int, seed: Int, notes: MutableList<String>): ByteArray? {
        val key = hfKey()
        if (key.isEmpty()) return null
        val models = ladder()
        val custom = settings.get(Keys.IMAGE_MODEL).trim().isNotEmpty()
        val todo = if (custom) models else models.filter { it in ImagePrompts.HTTP_LADDER }.ifEmpty { ImagePrompts.HTTP_LADDER }
        for (model in todo) {
            setStage(id, prompt, "Rendering with ${model.substringAfterLast('/')}", "Hugging Face", model)
            try {
                val body = Json.stringify(mapOf("inputs" to full, "parameters" to mapOf("width" to w, "height" to h, "seed" to seed)))
                val r = try {
                    http.execute(HttpRequest(
                        "https://router.huggingface.co/hf-inference/models/$model", "POST",
                        mapOf("Authorization" to "Bearer $key", "Accept" to "image/png", "Content-Type" to "application/json"), body, 150_000,
                    ))
                } catch (e: NetworkException) {
                    e.rethrowIfCancelled()
                    throw Skip("I can't reach Hugging Face.")
                }
                if (isImage(r)) return r.body
                throw httpSkip(r, "Hugging Face")
            } catch (s: Skip) {
                notes.add(s.why)
                log.i("Image", "model $model skipped: ${s.why}")
                if (s.fatal) return null
            }
        }
        return null
    }

    private suspend fun tryPollinations(id: String, prompt: String, full: String, w: Int, h: Int, seed: Int, notes: MutableList<String>): ByteArray? {
        val key = Settings.cleanSecret(settings.get(Keys.POLLINATIONS_API_KEY))
        setStage(id, prompt, "Rendering with FLUX (Pollinations)", "Pollinations", "flux")
        val q = URLEncoder.encode(full, "UTF-8").replace("+", "%20")
        val params = "?model=flux&width=$w&height=$h&seed=$seed&nologo=true&enhance=true"
        val urls = ArrayList<Pair<String, Map<String, String>>>()
        if (key.isNotEmpty()) urls.add("https://gen.pollinations.ai/image/$q$params" to mapOf("Authorization" to "Bearer $key"))
        urls.add("https://image.pollinations.ai/prompt/$q$params" to emptyMap())
        for ((url, hdr) in urls) {
            val r = try {
                http.execute(HttpRequest(url, "GET", mapOf("User-Agent" to "Jarvis/2") + hdr, null as ByteArray?, 150_000))
            } catch (e: NetworkException) {
                e.rethrowIfCancelled()
                notes.add("I can't reach Pollinations."); continue
            }
            if (isImage(r)) return r.body
            notes.add(httpSkip(r, "Pollinations").why)
        }
        return null
    }
}
