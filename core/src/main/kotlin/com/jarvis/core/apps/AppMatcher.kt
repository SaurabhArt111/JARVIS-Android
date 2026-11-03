package com.jarvis.core.apps

import com.jarvis.core.ports.AppMatch

/**
 * Fuzzy "which installed app did the user mean?" (port of Backend/Apps.py `_norm/_score/_variants/resolve`, with Android aliases
 * instead of Windows/game aliases). Pure logic: the platform supplies the installed (label, package) list.
 */
object AppMatcher {
    private val ALIAS = mapOf(
        "yt" to "youtube", "you tube" to "youtube", "ig" to "instagram", "insta" to "instagram", "fb" to "facebook",
        "wa" to "whatsapp", "whats app" to "whatsapp", "watsapp" to "whatsapp", "tg" to "telegram", "gmail" to "gmail", "mail" to "gmail",
        "maps" to "maps", "google maps" to "maps", "photos" to "photos", "gallery" to "photos", "play store" to "play store",
        "playstore" to "play store", "store" to "play store", "browser" to "chrome", "internet" to "chrome", "web browser" to "chrome",
        "phone" to "phone", "dialer" to "phone", "messages" to "messages", "sms" to "messages", "text messages" to "messages",
        "clock" to "clock", "alarm" to "clock", "calc" to "calculator", "files" to "files", "file manager" to "files",
        "ms word" to "word", "ms excel" to "excel", "ppt" to "powerpoint", "vs code" to "visual studio code", "vscode" to "visual studio code",
        "teams" to "microsoft teams", "x" to "twitter", "prime video" to "prime video", "netflix" to "netflix",
    )
    /** Apps whose launcher label differs from the name people say ("WA Business" is WhatsApp Business). */
    private val PACKAGE_NAMES = mapOf(
        "com.whatsapp.w4b" to "whatsapp business", "com.whatsapp" to "whatsapp", "com.google.android.apps.messaging" to "messages",
        "com.google.android.apps.photos" to "photos", "com.google.android.youtube" to "youtube", "com.android.chrome" to "chrome",
    )
    private val PACKAGE_NOISE = setOf("com", "org", "net", "android", "google", "apps", "app", "www", "io", "co", "mobile", "the", "android")
    private val ROMAN = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10")
    private val ARABIC = ROMAN.entries.associate { it.value to it.key }
    private val SPOKEN = mapOf("two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10")

    fun norm(s: String): String {
        var t = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        t = t.replace(Regex("\\(.*?\\)"), " ")
        t = t.replace(Regex("\\b(microsoft|google|adobe|the|app|application|2\\d{3})\\b"), " ")
        return Regex("[a-z0-9+#]+").findAll(t).joinToString(" ") { it.value }
    }

    fun variants(q: String): List<String> {
        val out = arrayListOf(q)
        val parts = q.split(' ').filter { it.isNotEmpty() }
        if (parts.size > 1) {
            var last = parts.last()
            SPOKEN[last]?.let { out.add((parts.dropLast(1) + it).joinToString(" ")); last = it }
            ROMAN[last]?.let { out.add((parts.dropLast(1) + it).joinToString(" ")) }
                ?: ARABIC[last]?.let { out.add((parts.dropLast(1) + it).joinToString(" ")) }
        }
        return out
    }

    fun score(q: String, key: String): Double {
        if (q == key) return 1.0
        val qt = q.split(' ').filter { it.isNotEmpty() }
        val kt = key.split(' ').filter { it.isNotEmpty() }
        // "photoshop" must not match an installed "Photos": the query may only extend the app name by whole words.
        if (key.startsWith(q) || q.startsWith("$key ")) return 0.93
        if (qt.isNotEmpty() && qt.all { t -> kt.any { k -> t == k || k.startsWith(t) } }) return 0.9
        if (key.contains(q)) return 0.86
        return ratio(q, key) * 0.95
    }

    /** Ratcliff/Obershelp similarity (what Python's difflib.SequenceMatcher.ratio computes). */
    fun ratio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        return 2.0 * matching(a, b) / (a.length + b.length)
    }

    private fun matching(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        var best = 0; var ai = 0; var bi = 0
        val prev = IntArray(b.length + 1)
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else 0
                if (cur[j] > best) { best = cur[j]; ai = i - best; bi = j - best }
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        if (best == 0) return 0
        return best + matching(a.substring(0, ai), b.substring(0, bi)) + matching(a.substring(ai + best), b.substring(bi + best))
    }

    /** Installed apps matching [query], best first; empty when nothing reaches [threshold] (the assistant must then say it is not installed). */
    fun resolve(query: String, installed: List<Pair<String, String>>, threshold: Double = 0.80, limit: Int = 5): List<AppMatch> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val qs = arrayListOf<String>()
        for (v in variants(q)) {
            val a = ALIAS[v] ?: v
            qs.add(a)
            qs.addAll(variants(a).filter { it != a })
        }
        return installed.map { (label, pkg) ->
            val key = norm(label)
            // other names this app answers to: the well-known name for its package, and the meaningful parts of the package id ("com.whatsapp.w4b" -> whatsapp, w4b)
            val pkgNames = listOfNotNull(PACKAGE_NAMES[pkg]?.let { norm(it) }) + pkg.split('.').map { norm(it) }.filter { it.isNotEmpty() && it !in PACKAGE_NOISE }
            val s = qs.maxOf { x ->
                maxOf(score(x, key), if (key.isEmpty()) 0.0 else (pkgNames.maxOfOrNull { score(x, it) } ?: 0.0) * 0.9)
            }
            AppMatch(label, pkg, s)
        }.filter { it.score >= threshold }.sortedByDescending { it.score }.take(limit)
    }

    fun suggest(query: String, installed: List<Pair<String, String>>, n: Int = 3): List<String> {
        val q = norm(query)
        return installed.map { score(q, norm(it.first)) to it.first }.sortedByDescending { it.first }.take(n).filter { it.first >= 0.62 }.map { it.second }
    }
}
