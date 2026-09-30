package io.github.ndev.roadsight.core

/**
 * A small JSON reader and writer (the core module has no dependencies). Objects are maps, arrays
 * are lists, numbers are doubles.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "Unexpected text after JSON at ${p.i}" }
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(text: String): Map<String, Any?> = parse(text) as Map<String, Any?>

    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(v, sb)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null")
                else if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k.toString(), sb)
                    sb.append(':')
                    write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(x, sb)
                }
                sb.append(']')
            }
            is DoubleArray -> write(v.toList(), sb)
            is IntArray -> write(v.toList(), sb)
            else -> quote(v.toString(), sb)
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            require(i < s.length) { "Unexpected end of JSON" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Unexpected '$c' at $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "Unexpected text at $i" }
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                val k = str()
                ws()
                require(s[i] == ':') { "Expected ':' at $i" }
                i++
                m[k] = value()
                ws()
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return m
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $i")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') {
                i++
                return l
            }
            while (true) {
                l.add(value())
                ws()
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return l
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $i")
                }
            }
        }

        private fun str(): String {
            require(s[i] == '"') { "Expected a string at $i" }
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(start, i).toDouble()
        }
    }
}
