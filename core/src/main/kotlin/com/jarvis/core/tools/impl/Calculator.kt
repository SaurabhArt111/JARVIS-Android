package com.jarvis.core.tools.impl

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Exact-enough arithmetic without `eval` (port of Skills._calc / _safe_eval): recursive descent over `+ - * / % ^ **`, parentheses,
 * unary +/-, decimals. Also understands the spoken forms ("15% of 240", "12 times 5", "2 to the power of 8").
 */
object Calculator {
    class CalcException(message: String) : RuntimeException(message)

    private val PERCENT_OF = Regex("^(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s+of\\s+(\\d+(?:\\.\\d+)?)$")
    private val WORD_OPS = listOf(
        Regex("\\bplus\\b") to "+", Regex("\\bminus\\b") to "-", Regex("\\bmultiplied by\\b|\\btimes\\b|\\bx\\b") to "*",
        Regex("\\bdivided by\\b|\\bover\\b") to "/", Regex("\\bto the power of\\b|\\bpower\\b") to "^", Regex("\\bmod(?:ulo)?\\b") to "%",
    )
    private val LEAD = Regex("^(?:calculate|compute|what(?:'s| is)|how much is|solve|whats)\\s+")

    class Result(val value: Double, val shown: String, val expression: String)

    /** Returns null when [text] is not arithmetic. Throws [CalcException] for "divide by zero" etc. */
    fun evaluateSpoken(text: String): Result? {
        var e = text.trim().lowercase().trim('?', '.', '!', ' ')
        repeat(3) { e = LEAD.replace(e, "") }
        PERCENT_OF.matchEntire(e)?.let {
            val v = it.groupValues[1].toDouble() * it.groupValues[2].toDouble() / 100
            return Result(v, format(v), e)
        }
        for ((re, sym) in WORD_OPS) e = re.replace(e, sym)
        e = e.replace(",", "")
        if (!Regex("[\\d\\s+\\-*/().%^]+").matches(e) || !e.any { it.isDigit() } || e.none { it in "+-*/%^" }) return null
        val v = evaluate(e)
        return Result(v, format(v), e.trim())
    }

    fun evaluate(expr: String): Double {
        val p = P(expr.replace("**", "^"))
        val v = p.parseExpr()
        p.skip()
        if (!p.end()) throw CalcException("unexpected '${p.peek()}'")
        if (v.isNaN() || v.isInfinite()) throw CalcException("not a finite number")
        return v
    }

    /** Python-style `{:,.10g}`: up to 10 significant digits, thousands separators, no trailing zeros. */
    fun format(v: Double): String {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return group(v.toLong().toString())
        val bd = BigDecimal(v).round(MathContext(10, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        val plain = bd.toPlainString()
        val neg = plain.startsWith("-")
        val body = plain.removePrefix("-")
        val int = body.substringBefore('.')
        val frac = if ('.' in body) "." + body.substringAfter('.') else ""
        return (if (neg) "-" else "") + group(int) + frac
    }

    private fun group(digits: String): String {
        val neg = digits.startsWith("-")
        val d = digits.removePrefix("-")
        val sb = StringBuilder()
        d.reversed().forEachIndexed { i, c -> if (i > 0 && i % 3 == 0) sb.append(','); sb.append(c) }
        return (if (neg) "-" else "") + sb.reverse().toString()
    }

    private class P(val s: String) {
        var i = 0
        fun end() = i >= s.length
        fun peek(): Char = if (end()) '\u0000' else s[i]
        fun skip() { while (!end() && s[i].isWhitespace()) i++ }

        fun parseExpr(): Double {
            var v = parseTerm()
            while (true) {
                skip()
                when (peek()) {
                    '+' -> { i++; v += parseTerm() }
                    '-' -> { i++; v -= parseTerm() }
                    else -> return v
                }
            }
        }

        fun parseTerm(): Double {
            var v = parseUnary()
            while (true) {
                skip()
                when (peek()) {
                    '*' -> { i++; v *= parseUnary() }
                    '/' -> { i++; val d = parseUnary(); if (d == 0.0) throw CalcException("divide by zero"); v /= d }
                    '%' -> { i++; val d = parseUnary(); if (d == 0.0) throw CalcException("divide by zero"); v %= d }
                    else -> return v
                }
            }
        }

        fun parseUnary(): Double {
            skip()
            return when (peek()) {
                '-' -> { i++; -parseUnary() }
                '+' -> { i++; parseUnary() }
                else -> parsePow()
            }
        }

        fun parsePow(): Double {
            val base = parseAtom()
            skip()
            if (peek() == '^') {
                i++
                val exp = parseUnary()
                if (Math.abs(exp) > 64) throw CalcException("exponent too large")
                return Math.pow(base, exp)
            }
            return base
        }

        fun parseAtom(): Double {
            skip()
            if (peek() == '(') {
                i++
                val v = parseExpr()
                skip()
                if (peek() != ')') throw CalcException("missing )")
                i++
                return v
            }
            val start = i
            while (!end() && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw CalcException("expected a number")
            return s.substring(start, i).toDoubleOrNull() ?: throw CalcException("bad number")
        }
    }
}
