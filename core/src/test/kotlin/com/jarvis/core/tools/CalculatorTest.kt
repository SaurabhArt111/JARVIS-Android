package com.jarvis.core.tools

import com.jarvis.core.tools.impl.Calculator
import org.junit.Assert.*
import org.junit.Test

class CalculatorTest {
    private fun spoken(s: String) = Calculator.evaluateSpoken(s)?.shown

    @Test fun basicArithmetic() {
        assertEquals("7", spoken("3 + 4"))
        assertEquals("51", spoken("(12+5)*3"))
        assertEquals("2.5", spoken("5 / 2"))
        assertEquals("1", spoken("10 % 3"))
    }

    @Test fun precedenceAndPower() {
        assertEquals("14", spoken("2 + 3 * 4"))
        assertEquals("256", spoken("2 ^ 8"))
        assertEquals("256", spoken("2 ** 8"))
        assertEquals("512", spoken("2 ^ 3 ^ 2"))          // right associative, like Python
        assertEquals(-4.0, Calculator.evaluate("-2^2"), 0.0)   // unary minus binds looser than ^, like Python
    }

    @Test fun spokenForms() {
        assertEquals("60", spoken("what's 12 times 5"))
        assertEquals("36", spoken("15% of 240"))
        assertEquals("36", spoken("15 percent of 240"))
        assertEquals("256", spoken("what is 2 to the power of 8"))
        assertEquals("4", spoken("calculate 20 divided by 5"))
        assertEquals("9", spoken("how much is 5 plus 4"))
    }

    @Test fun thousandsSeparatorsAndRounding() {
        assertEquals("1,000,000", spoken("1000 * 1000"))
        assertEquals("0.3333333333", spoken("1 / 3"))
        assertEquals("0.3", spoken("0.1 + 0.2"))              // no float noise
    }

    @Test fun nonArithmeticIsNull() {
        assertNull(Calculator.evaluateSpoken("hello there"))
        assertNull(Calculator.evaluateSpoken("open spotify"))
        assertNull(Calculator.evaluateSpoken("42"))
    }

    @Test fun divisionByZeroThrows() {
        try { Calculator.evaluate("1/0"); fail("expected an exception") } catch (e: Calculator.CalcException) { /* ok */ }
    }

    @Test fun codeIsNeverEvaluated() {
        for (bad in listOf("__import__('os').system('ls')", "1; 2", "open('x')", "2 + abc", "System.exit(0)")) {
            val r = try { Calculator.evaluateSpoken(bad) } catch (e: Calculator.CalcException) { null }
            assertNull("$bad must not evaluate", r)
        }
    }

    @Test fun unbalancedParenthesesAreErrors() {
        try { Calculator.evaluate("(1+2"); fail() } catch (e: Calculator.CalcException) { }
        try { Calculator.evaluate("1+2)"); fail() } catch (e: Calculator.CalcException) { }
    }

    @Test fun hugeExponentsDoNotHang() {
        val t0 = System.nanoTime()
        try { Calculator.evaluate("9 ^ 9 ^ 9 ^ 9") } catch (e: Calculator.CalcException) { }
        assertTrue("took too long", (System.nanoTime() - t0) / 1_000_000 < 2000)
    }
}
