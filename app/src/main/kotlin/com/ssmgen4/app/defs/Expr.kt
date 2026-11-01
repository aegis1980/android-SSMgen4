package com.ssmgen4.app.defs

/**
 * The arithmetic used in RomRaider logger definitions: numbers, `x` (the decoded raw
 * value), other parameter ids (`P12`), unit-qualified references (`[P7:psi]`), `+ - * /`,
 * parentheses and unary minus. Anything else fails to parse, and callers fall back to the
 * raw value rather than guessing.
 */
class Expr private constructor(private val root: Node, val text: String) {

    /** [resolve] answers a name: `x`, a bare id like `P12`, or a `[P7:psi]` reference. Null aborts. */
    fun eval(resolve: (String) -> Double?): Double? = root.eval(resolve)

    private sealed interface Node {
        fun eval(r: (String) -> Double?): Double?
    }

    private class Num(val v: Double) : Node {
        override fun eval(r: (String) -> Double?) = v
    }

    private class Name(val n: String) : Node {
        override fun eval(r: (String) -> Double?) = r(n)
    }

    private class Neg(val a: Node) : Node {
        override fun eval(r: (String) -> Double?) = a.eval(r)?.let { -it }
    }

    private class Bin(val op: Char, val a: Node, val b: Node) : Node {
        override fun eval(r: (String) -> Double?): Double? {
            val x = a.eval(r) ?: return null
            val y = b.eval(r) ?: return null
            return when (op) {
                '+' -> x + y
                '-' -> x - y
                '*' -> x * y
                else -> x / y
            }
        }
    }

    companion object {
        fun parse(text: String): Expr? =
            try {
                Parser(text).parseAll()?.let { Expr(it, text) }
            } catch (_: IllegalArgumentException) {
                null
            }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseAll(): Node? {
            val n = expr()
            skip()
            return if (i == s.length) n else null
        }

        private fun expr(): Node {
            var n = term()
            while (true) {
                skip()
                val c = peek()
                if (c == '+' || c == '-') {
                    i++
                    n = Bin(c, n, term())
                } else {
                    return n
                }
            }
        }

        private fun term(): Node {
            var n = unary()
            while (true) {
                skip()
                val c = peek()
                if (c == '*' || c == '/') {
                    i++
                    n = Bin(c, n, unary())
                } else {
                    return n
                }
            }
        }

        private fun unary(): Node {
            skip()
            return when (peek()) {
                '-' -> { i++; Neg(unary()) }
                '+' -> { i++; unary() }
                else -> primary()
            }
        }

        private fun primary(): Node {
            skip()
            val c = peek()
            return when {
                c == '(' -> {
                    i++
                    val n = expr()
                    skip()
                    require(peek() == ')') { "missing ) in $s" }
                    i++
                    n
                }
                c == '[' -> {
                    val end = s.indexOf(']', i)
                    require(end > i) { "unclosed [ in $s" }
                    val ref = s.substring(i, end + 1)
                    i = end + 1
                    Name(ref)
                }
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    Num(s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("bad number in $s"))
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    Name(s.substring(start, i))
                }
                else -> throw IllegalArgumentException("unexpected '$c' at $i in $s")
            }
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
