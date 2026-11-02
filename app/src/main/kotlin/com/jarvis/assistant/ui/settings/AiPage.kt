package com.jarvis.assistant.ui.settings

import android.view.View
import android.widget.LinearLayout
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.kit.Dialogs
import com.jarvis.assistant.ui.kit.MATCH
import com.jarvis.assistant.ui.kit.Ui
import com.jarvis.assistant.ui.kit.WRAP
import com.jarvis.core.ai.ProviderDiagnosis
import com.jarvis.core.settings.Keys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** AI providers: keys (stored encrypted), which service answers, models, the agent loop, search and image services, and a connection test that reports what really happened. */
class AiPage : SettingsPage() {
    override val id = "settings:ai"
    override fun title() = s(R.string.ui_set_ai)

    private fun httpsOrLocal(v: String): String? {
        val u = try { java.net.URI(v.trim()) } catch (e: Exception) { return s(R.string.ui_ai_url_bad) }
        val scheme = u.scheme?.lowercase()
        val host = u.host?.lowercase()
        if (host.isNullOrBlank() || (scheme != "https" && scheme != "http")) return s(R.string.ui_ai_url_bad)
        if (scheme == "http" && host != "localhost" && host != "127.0.0.1" && host != "10.0.2.2") return s(R.string.ui_ai_url_http)
        return null
    }

    override fun fill(c: LinearLayout) {
        val lost = app.secretStore.lostSecrets()
        if (lost.isNotEmpty()) c.addView(ui.banner(s(R.string.ui_ai_lost_keys), ui.pal.warn, "alert-triangle"))
        if (!app.permissions.hasAnyProvider()) c.addView(ui.banner(s(R.string.ui_ai_none), ui.pal.accent, "info"))

        c.group(s(R.string.ui_ai_which)) {
            choice(s(R.string.ui_ai_mode), Keys.AI_MODE, listOf("auto" to s(R.string.ui_ai_mode_auto), "cloud" to s(R.string.ui_ai_mode_cloud), "local" to s(R.string.ui_ai_mode_local)), "auto", s(R.string.ui_ai_mode_sub))
            val providers = listOf("auto" to s(R.string.ui_ai_auto), "groq" to "Groq", "cohere" to "Cohere", "openai" to "OpenAI", "ollama" to "Ollama (local)")
            choice(s(R.string.ui_ai_chat_provider), Keys.CHAT_PROVIDER, providers, "auto", s(R.string.ui_ai_chat_provider_sub))
            choice(s(R.string.ui_ai_decision_provider), Keys.DECISION_PROVIDER, providers, "auto", s(R.string.ui_ai_decision_provider_sub))
        }
        c.group(s(R.string.ui_ai_keys), s(R.string.ui_ai_keys_note)) {
            secretRow("Groq", Keys.GROQ_API_KEY, "console.groq.com") { app.llm.resetAfterSettingsChange() }
            secretRow("Cohere", Keys.COHERE_API_KEY, "dashboard.cohere.com") { app.llm.resetAfterSettingsChange() }
            secretRow("OpenAI", Keys.OPENAI_API_KEY, "platform.openai.com") { app.llm.resetAfterSettingsChange() }
        }
        c.group(s(R.string.ui_ai_models), s(R.string.ui_ai_models_note)) {
            textRow(s(R.string.ui_ai_groq_model), Keys.MODEL_NAME, s(R.string.ui_ai_model_hint))
            textRow(s(R.string.ui_ai_cohere_model), Keys.COHERE_MODEL, s(R.string.ui_ai_model_hint))
            textRow(s(R.string.ui_ai_openai_model), Keys.OPENAI_MODEL, s(R.string.ui_ai_model_hint))
            textRow(s(R.string.ui_ai_openai_url), Keys.OPENAI_BASE_URL, "https://api.openai.com/v1", s(R.string.ui_ai_openai_url_sub), { httpsOrLocal(it) })
            textRow(s(R.string.ui_ai_ollama_url), Keys.OLLAMA_URL, "http://localhost:11434", s(R.string.ui_ai_ollama_url_sub), { httpsOrLocal(it) })
            textRow(s(R.string.ui_ai_ollama_model), Keys.OLLAMA_MODEL, "llama3.1")
        }
        c.group(s(R.string.ui_ai_agent), s(R.string.ui_ai_agent_note)) {
            flag(s(R.string.ui_ai_agent_on), s(R.string.ui_ai_agent_on_sub), Keys.AGENT_MODE, true)
            flag(s(R.string.ui_ai_agent_fallback), s(R.string.ui_ai_agent_fallback_sub), Keys.AGENT_FALLBACK, true)
            choice(s(R.string.ui_ai_agent_steps), Keys.AGENT_MAX_STEPS, listOf("3", "4", "6", "8", "10").map { it to it }, "6", s(R.string.ui_ai_agent_steps_sub))
        }
        c.group(s(R.string.ui_ai_services), s(R.string.ui_ai_services_note)) {
            secretRow(s(R.string.ui_ai_serp), Keys.SERP_API_KEY, "serpapi.com")
            choice(s(R.string.ui_ai_image_provider), Keys.IMAGE_PROVIDER, listOf("auto" to s(R.string.ui_ai_auto), "pollinations" to "Pollinations", "huggingface" to "Hugging Face"), "auto")
            secretRow("Hugging Face", Keys.HUGGINGFACE_API_KEY, "huggingface.co/settings/tokens")
            secretRow("Pollinations", Keys.POLLINATIONS_API_KEY, "pollinations.ai")
            choice(s(R.string.ui_ai_image_quality), Keys.IMAGE_QUALITY, listOf("best" to s(R.string.ui_ai_quality_best), "fast" to s(R.string.ui_ai_quality_fast)), "best")
            choice(s(R.string.ui_ai_image_count), Keys.IMAGE_COUNT, listOf("1", "2", "3", "4").map { it to it }, "1")
        }
        c.group(s(R.string.ui_ai_privacy), s(R.string.ui_ai_privacy_note)) {
            flag(s(R.string.ui_ai_share), s(R.string.ui_ai_share_sub), Keys.AI_SHARE_PERSONAL, true)
        }
        c.addView(ui.button(s(R.string.ui_ai_test), Ui.Kind.PRIMARY, "activity") { runTest() })
    }

    private fun runTest() {
        var body: LinearLayout? = null
        var close: (() -> Unit)? = null
        Dialogs.show(activity, ui) { card, dismiss ->
            body = card; close = dismiss
            card.addView(ui.title(s(R.string.ui_ai_test), 19f))
            card.addView(ui.loadingState(s(R.string.ui_ai_testing)))
        }
        viewScope.launch {
            val results: List<ProviderDiagnosis>? = try { withContext(Dispatchers.IO) { app.llm.diagnose() } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            val card = body ?: return@launch
            card.removeAllViews()
            card.addView(ui.title(s(R.string.ui_ai_test), 19f))
            if (results == null) card.addView(ui.text(s(R.string.ui_ai_test_failed), 14.5f))
            else results.forEach { r ->
                val color = if (r.ok) ui.pal.ok else if (!r.configured) ui.pal.muted else ui.pal.err
                card.addView(ui.row(10) {
                    addView(ui.icon(if (r.ok) "check-circle" else if (!r.configured) "minus" else "x-circle", 20, color))
                    addView(ui.column(gap = 2) {
                        addView(ui.text(r.label, 15f, bold = true))
                        addView(ui.muted(r.detail + if (r.ok) " · ${r.millis} ms" + if (r.models.isNotEmpty()) " · " + s(R.string.ui_ai_models_count, r.models.size) else "" else "", 12.5f))
                    }, LinearLayout.LayoutParams(0, WRAP, 1f))
                })
            }
            card.addView(ui.muted(s(R.string.ui_ai_test_note), 12.5f))
            card.addView(ui.button(s(R.string.ui_close), Ui.Kind.PRIMARY) { close?.invoke() })
        }
    }
}
