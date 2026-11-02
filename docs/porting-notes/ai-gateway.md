# Porting notes: AI gateway, search, weather, images (`:core`)

## Map

| Python | Kotlin |
|---|---|
| `Backend/LLM.py` (Provider classes, discovery, ranking, fallback, cooldown) | `ai/LlmProviders.kt` (Groq, Cohere v2, Ollama, OpenAI-compatible), `ai/LlmWire.kt`, `ai/DefaultLlmGateway.kt` |
| `Backend/Health.py` | `ai/ProviderHealth.kt` |
| `Backend/RealtimeSearchEngine.py` | `search/DefaultWebSearch.kt`, `search/RealtimeAnswer.kt` |
| `Skills.weather` | `weather/OpenMeteoWeather.kt` (wttr.in first, Open-Meteo second) |
| `Backend/ImageGeneration.py` | `image/DefaultImageService.kt`, `image/ImagePrompts.kt` |
| `requests` | `net/JdkHttpClient.kt` (`HttpURLConnection`, HTTPS only except loopback/emulator, cancellable) |

## Differences on purpose

* **Transport.** No `requests`, no Python SDKs: plain HTTPS through `HttpClient`, which is trivially faked in tests (`ScriptedHttp`). Cleartext is allowed only for
  `localhost / 127.0.0.1 / 10.0.2.2` (see `res/xml/network_security_config.xml`); a phone cannot reach a PC's Ollama over plain `http://192.168.x.x`, so the setting
  says to use HTTPS or `adb reverse`.
* **Secrets.** Keys are read from `Settings` per call, cleaned (quotes, zero-width characters) and never logged or placed in `LlmException.userMessage`. SerpAPI is the one
  service that wants the key in the query string; the URL is never logged. The Pollinations/Hugging Face keys travel in an `Authorization` header.
* **Cancellation** is never converted into an AI error: `NetworkException.Kind.CANCELLED` and coroutine cancellation propagate (search/weather/image used to swallow the
  former; found while writing the tests, `rethrowIfCancelled`).
* **Tool-argument repair.** Small models emit `{'a': 'x',}`; `Json.parseObjectLenient` now repairs single quotes, trailing commas and Python constants before giving up.
* **Hugging Face images** only use ladder models the plain HTTP router serves (no Python hub client on Android). Pollinations needs no key; **the prompt text is sent to those
  services** and the README says so.
* The legacy `LLMProvider=ollama` setting no longer exists (there are no legacy installs); `AIMode` is `auto | cloud | local`.
* Ollama has **no default URL** on a phone.

## Verified by tests

`AiGatewayTest` (60 tests: every provider's success / tool-call / 401 / 404 / 429 rate-vs-quota / 5xx / malformed JSON / empty answer / think-tag stripping / forced
tool_choice / Groq `failed_generation` recovery / Cohere `tool_plan` / Ollama tool format / model discovery + ranking + bad-model memory / caching, gateway order per
mode and provider preference, fallback + `LlmEvent.Fallback`, cooldown skipping and expiry, aggregate NETWORK / AUTH / NO_KEY errors, cancellation, key-leak scans of logs,
events and errors), `SearchTest`, `WeatherTest`, `ImageTest`, `JdkHttpClientTest`, `ProviderHealthTest`, `JsonTest`.

## Not verified

Real provider behaviour: all fixtures are written from the providers' public API shapes, not recorded from live accounts (no keys were available). Model names and ranking
regexes follow the Python original and will age; discovery makes them self-correcting but only against the live API.
