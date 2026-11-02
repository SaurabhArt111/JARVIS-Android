package com.jarvis.core.weather

import com.jarvis.core.json.Json
import com.jarvis.core.net.NetworkException
import com.jarvis.core.net.ScriptedHttp
import com.jarvis.core.net.ScriptedHttp.Companion.fail
import com.jarvis.core.net.ScriptedHttp.Companion.json
import com.jarvis.core.settings.Keys
import com.jarvis.core.testing.TestEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WeatherTest {
    private val http = ScriptedHttp()
    private val env = TestEnv()
    private val weather = OpenMeteoWeather(http, env.settings, env.log)

    private fun wttr(temp: String = "31", feels: String = "35", desc: String = "Haze", hum: String = "70", wind: String = "12", area: String = "Mumbai") = json(200, Json.stringify(mapOf(
        "current_condition" to listOf(mapOf("temp_C" to temp, "FeelsLikeC" to feels, "humidity" to hum, "windspeedKmph" to wind, "weatherDesc" to listOf(mapOf("value" to desc)))),
        "nearest_area" to listOf(mapOf("areaName" to listOf(mapOf("value" to area)))))))

    private val geo = json(200, Json.stringify(mapOf("results" to listOf(mapOf("name" to "Pune", "latitude" to 18.52, "longitude" to 73.85)))))
    private val forecast = json(200, Json.stringify(mapOf("current" to mapOf("temperature_2m" to 27.4, "apparent_temperature" to 29.0, "relative_humidity_2m" to 64, "weather_code" to 61, "wind_speed_10m" to 9.5))))

    private fun current(city: String?) = runBlocking { weather.current(city) }

    @Test fun wttrNumbersAreReadFromStrings() {
        http.on("wttr.in", wttr())
        val w = current("Mumbai").info!!
        assertEquals("Mumbai", w.city); assertEquals(31.0, w.tempC, 0.0); assertEquals(35.0, w.feelsC, 0.0)
        assertEquals("Haze", w.description); assertEquals(70, w.humidity); assertEquals(12.0, w.windKmh, 0.0)
    }

    @Test fun theCityIsUrlEncoded() {
        http.on("wttr.in", wttr()); current("New York")
        assertTrue(http.requests.single().url, http.requests.single().url.startsWith("https://wttr.in/New%20York?format=j1"))
    }

    @Test fun theSavedCityIsUsedWhenNoneIsGiven() {
        env.settings.set(Keys.WEATHER_CITY, "Delhi"); http.on("wttr.in", wttr(area = "Delhi")); current(null)
        assertTrue(http.requests.single().url.contains("/Delhi?"))
        http.requests.clear(); current("  "); assertTrue(http.requests.single().url.contains("/Delhi?"))
    }

    @Test fun openMeteoAnswersWhenWttrIsDown() {
        http.on("wttr.in", fail()).on("geocoding-api", geo).on("api.open-meteo.com", forecast)
        val w = current("Pune").info!!
        assertEquals("Pune", w.city); assertEquals(27.4, w.tempC, 0.0); assertEquals("Light rain", w.description); assertEquals(64, w.humidity)
        assertTrue(http.requests.last().url.contains("latitude=18.52") && http.requests.last().url.contains("longitude=73.85"))
    }

    @Test fun anUnknownPlaceIsAnHonestFailure() {
        http.on("wttr.in", ScriptedHttp.text(404, "Unknown location")).on("geocoding-api", json(200, """{"generationtime_ms":0.5}"""))
        val r = current("Atlantis")
        assertNull(r.info); assertTrue(r.error!!.contains("couldn't fetch the weather"))
    }

    @Test fun withoutAnyCityAndNoNetworkTheUserIsToldToSetOne() {
        http.on("wttr.in", fail())
        val r = current(null)
        assertNull(r.info); assertTrue(r.error!!.contains("set a city"))
    }

    @Test fun garbageFromWttrIsNotMistakenForWeather() {
        http.on("wttr.in", ScriptedHttp.text(200, "<html>captive portal</html>"))
        assertNull(current(null).info)
        assertNull(OpenMeteoWeather.parseWttr("""{"current_condition":[]}"""))
        assertNull(OpenMeteoWeather.parseWttr("""{"current_condition":[{"humidity":"50"}]}"""))
        assertNull(OpenMeteoWeather.parseWttr(null))
    }

    @Test fun missingFieldsGetSensibleDefaults() {
        val w = OpenMeteoWeather.parseWttr("""{"current_condition":[{"temp_C":"20"}]}""")!!
        assertEquals("your area", w.city); assertEquals(20.0, w.feelsC, 0.0); assertEquals("unknown conditions", w.description); assertEquals(0, w.humidity)
    }

    @Test fun weatherCodesAreTranslated() {
        assertEquals("Clear sky", OpenMeteoWeather.wmo(0)); assertEquals("Heavy rain", OpenMeteoWeather.wmo(65)); assertEquals("Thunderstorm", OpenMeteoWeather.wmo(95))
        assertEquals("Unknown conditions", OpenMeteoWeather.wmo(1234)); assertEquals("Unknown conditions", OpenMeteoWeather.wmo(null))
    }

    @Test fun negativeTemperaturesSurvive() {
        assertEquals(-12.0, OpenMeteoWeather.parseWttr(wttr(temp = "-12").let { String(it.body) })!!.tempC, 0.0)
    }

    @Test fun cancellationIsNotAnError() {
        http.on("wttr.in", fail(NetworkException.Kind.CANCELLED))
        try { current("Mumbai"); fail() } catch (e: CancellationException) { }
    }
}
