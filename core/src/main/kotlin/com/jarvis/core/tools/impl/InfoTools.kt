package com.jarvis.core.tools.impl

import com.jarvis.core.model.Card
import com.jarvis.core.model.CardItem
import com.jarvis.core.model.Outcome
import com.jarvis.core.model.ReplyKind
import com.jarvis.core.model.Requirement
import com.jarvis.core.settings.Keys
import com.jarvis.core.tools.Args
import com.jarvis.core.tools.Capability
import com.jarvis.core.tools.LockClass
import com.jarvis.core.tools.Tool
import com.jarvis.core.tools.ToolContext
import com.jarvis.core.tools.ToolSpec

// ===================================================================================================== web_search
class WebSearchTool : Tool {
    override val spec = ToolSpec(
        "web_search", "Search the web and get results back to answer current-events or factual questions you are unsure about.",
        mapOf("query" to T.p("Search query")), listOf("query"), Capability.INFORMATION, LockClass.OPEN, isAction = false, needs = listOf(Requirement.NETWORK),
    )

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val q = args.str("query")
        if (q.isEmpty()) return T.fail("What should I look up?", "globe")
        val hits = ctx.services.web.search(q, 5)
        if (hits.isEmpty()) {
            return Outcome(true, "The web search returned nothing (offline or blocked). Answer from your own knowledge and say it may be out of date.",
                T.reply("I couldn't reach the web search.", "globe", kind = ReplyKind.INFO), info = true, verified = false)
        }
        val text = hits.joinToString("\n") { "- ${it.title}: ${it.snippet}" }
        val sources = hits.take(4).filter { it.url.isNotBlank() }.map { mapOf("title" to it.title, "url" to it.url) }
        return T.info("Web results (use them to answer; do not read out URLs):\n$text",
            T.reply("Searched the web.", "globe", kind = ReplyKind.INFO, extras = mapOf("sources" to sources)))
    }
}

// ===================================================================================================== weather
class WeatherTool : Tool {
    override val spec = ToolSpec(
        "weather", "Current weather. Leave city empty for the user's default location.",
        mapOf("city" to T.p("City name (optional)")), emptyList(), Capability.INFORMATION, LockClass.OPEN, isAction = false, needs = listOf(Requirement.NETWORK),
    )

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val city = args.str("city", "location", "place").ifBlank { ctx.services.settings.get(Keys.WEATHER_CITY) }
        val res = ctx.services.weather.current(city.ifBlank { null })
        val w = res.info ?: return T.fail(res.error ?: "I couldn't fetch the weather. Check your internet connection.", "cloud-off")
        val line = "It's ${"%.0f".format(w.tempC)} degrees and ${w.description.lowercase()} in ${w.city}, feels like ${"%.0f".format(w.feelsC)}. Humidity ${w.humidity} percent."
        val card = Card("weather", w.city, w.description, "cloud", fields = mapOf(
            "temp" to "%.0f".format(w.tempC), "feels" to "%.0f".format(w.feelsC), "humidity" to w.humidity.toString(), "wind" to "%.0f".format(w.windKmh), "desc" to w.description))
        return T.info("${w.city}: ${"%.0f".format(w.tempC)}°C, ${w.description}, feels like ${"%.0f".format(w.feelsC)}°C, humidity ${w.humidity}%, wind ${"%.0f".format(w.windKmh)} km/h.", T.reply(line, "cloud", card))
    }
}

// ===================================================================================================== calculate
class CalculateTool : Tool {
    override val spec = ToolSpec(
        "calculate", "Exact arithmetic. Use for any maths with numbers.",
        mapOf("expression" to T.p("e.g. 15% of 240, (12+5)*3")), listOf("expression"), Capability.INFORMATION, LockClass.OPEN, isAction = false,
    )

    override suspend fun execute(args: Args, ctx: ToolContext): Outcome {
        val expr = args.str("expression", "expr", "query")
        if (expr.isEmpty()) return T.fail("What should I calculate?", "hash")
        return try {
            val spoken = Calculator.evaluateSpoken("calculate $expr")
            val (shown, e) = if (spoken != null) spoken.shown to spoken.expression else Calculator.format(Calculator.evaluate(expr)) to expr
            T.info("$e = $shown", T.reply("$e = $shown", "hash", Card("info", shown, e, "hash")))
        } catch (ex: Calculator.CalcException) {
            if (ex.message?.contains("zero") == true) T.fail("I can't divide by zero.", "hash") else T.fail("I couldn't work out “$expr”.", "hash")
        }
    }
}

internal fun unused(c: CardItem) = c
