package com.jarvis.core.weather

import com.jarvis.core.json.Json
import com.jarvis.core.net.HttpClient
import com.jarvis.core.net.HttpRequest
import com.jarvis.core.net.NetworkException
import com.jarvis.core.net.rethrowIfCancelled
import com.jarvis.core.ports.WeatherInfo
import com.jarvis.core.ports.WeatherProvider
import com.jarvis.core.ports.WeatherResult
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.util.JarvisLog
import java.net.URLEncoder

/**
 * Current weather. wttr.in (what the Python version used) first; if it fails and a city is known, Open-Meteo
 * (free geocoding + forecast, no key) answers instead.
 */
class OpenMeteoWeather(
    private val http: HttpClient,
    private val settings: Settings,
    private val log: JarvisLog = JarvisLog.None,
) : WeatherProvider {

    override suspend fun current(city: String?): WeatherResult {
        val place = city?.takeIf { it.isNotBlank() } ?: settings.get(Keys.WEATHER_CITY)
        wttr(place)?.let { return WeatherResult(it) }
        if (place.isNotBlank()) openMeteo(place)?.let { return WeatherResult(it) }
        return WeatherResult(null, if (place.isBlank()) "I couldn't fetch the weather. Check your internet connection, or set a city in Settings." else "I couldn't fetch the weather. Check your internet connection.")
    }

    private suspend fun get(url: String, ua: String? = null): String? = try {
        val r = http.execute(HttpRequest(url, "GET", buildMap { put("Accept", "application/json"); ua?.let { put("User-Agent", it) } }, null as ByteArray?, 8_000))
        if (r.status == 200) r.text else { log.i("Weather", "service answered ${r.status}"); null }
    } catch (e: NetworkException) {
        e.rethrowIfCancelled()
        log.i("Weather", "service unreachable (${e.kind})"); null
    }

    private suspend fun wttr(place: String): WeatherInfo? = parseWttr(get("https://wttr.in/${enc(place)}?format=j1", "curl/8"))

    private suspend fun openMeteo(place: String): WeatherInfo? {
        val geo = Json.parseObjectOrNull(get("https://geocoding-api.open-meteo.com/v1/search?name=${enc(place)}&count=1&language=en&format=json")) ?: return null
        val hit = Json.obj(Json.list(geo["results"])?.firstOrNull()) ?: return null
        val lat = Json.num(hit["latitude"]) ?: return null
        val lon = Json.num(hit["longitude"]) ?: return null
        val name = Json.str(hit["name"]) ?: place
        val body = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&wind_speed_unit=kmh" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m",
        )
        return parseOpenMeteo(body, name)
    }

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** wttr.in `?format=j1`. Numbers arrive as strings. */
        fun parseWttr(body: String?): WeatherInfo? {
            val j = Json.parseObjectOrNull(body) ?: return null
            val cur = Json.obj(Json.list(j["current_condition"])?.firstOrNull()) ?: return null
            val area = Json.str(Json.path(j, "nearest_area", 0, "areaName", 0, "value")).orEmpty()
            return try {
                WeatherInfo(
                    city = area.ifEmpty { "your area" },
                    tempC = Json.num(cur["temp_C"]) ?: return null,
                    feelsC = Json.num(cur["FeelsLikeC"]) ?: Json.num(cur["temp_C"])!!,
                    description = Json.str(Json.path(cur, "weatherDesc", 0, "value")).orEmpty().ifEmpty { "unknown conditions" },
                    humidity = Json.int(cur["humidity"]) ?: 0,
                    windKmh = Json.num(cur["windspeedKmph"]) ?: 0.0,
                )
            } catch (e: Exception) { null }
        }

        fun parseOpenMeteo(body: String?, city: String): WeatherInfo? {
            val cur = Json.obj(Json.parseObjectOrNull(body)?.get("current")) ?: return null
            val t = Json.num(cur["temperature_2m"]) ?: return null
            return WeatherInfo(
                city, t, Json.num(cur["apparent_temperature"]) ?: t, wmo(Json.int(cur["weather_code"])),
                Json.int(cur["relative_humidity_2m"]) ?: 0, Json.num(cur["wind_speed_10m"]) ?: 0.0,
            )
        }

        /** WMO weather interpretation codes -> words. */
        fun wmo(code: Int?): String = when (code) {
            0 -> "Clear sky"; 1 -> "Mainly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"; 56, 57 -> "Freezing drizzle"
            61 -> "Light rain"; 63 -> "Rain"; 65 -> "Heavy rain"; 66, 67 -> "Freezing rain"
            71 -> "Light snow"; 73 -> "Snow"; 75 -> "Heavy snow"; 77 -> "Snow grains"
            80, 81, 82 -> "Rain showers"; 85, 86 -> "Snow showers"
            95 -> "Thunderstorm"; 96, 99 -> "Thunderstorm with hail"
            else -> "Unknown conditions"
        }
    }
}
