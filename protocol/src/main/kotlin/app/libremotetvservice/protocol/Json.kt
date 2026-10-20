package app.libremotetvservice.protocol

/**
 * Just enough JSON for Libremote Receiver's messages (see SENDFILES.md): small objects, and the app list's array of
 * them. Numbers come back as their text, like strings: senders write sizes as strings, as "Send files to TV" did.
 */
internal object Json {

    fun encode(fields: Map<String, Any?>): String = buildString { value(fields) }

    private fun StringBuilder.value(value: Any?) {
        when (value) {
            null -> append("null")
            is Boolean, is Int, is Long -> append(value.toString())
            is Map<*, *> -> {
                append('{')
                for ((i, entry) in value.entries.withIndex()) {
                    if (i > 0) append(',')
                    string(entry.key.toString())
                    append(':')
                    value(entry.value)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { i, item ->
                    if (i > 0) append(',')
                    value(item)
                }
                append(']')
            }
            else -> string(value.toString())
        }
    }

    private fun StringBuilder.string(value: String) {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

    /** Parses a JSON object, with its nested objects (as maps) and arrays (as lists). */
    fun parseObject(text: String): Map<String, Any?> {
        val parser = Parser(text)
        val value = parser.value(0)
        parser.end()
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: throw ProtocolException("Expected a JSON object")
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("Too deeply nested")
            space()
            if (i >= s.length) fail("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("Unexpected '$c'")
            }
        }

        fun end() {
            space()
            if (i != s.length) fail("Trailing data")
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++ // {
            val fields = LinkedHashMap<String, Any?>()
            space()
            if (peek() == '}') return fields.also { i++ }
            while (true) {
                space()
                if (peek() != '"') fail("Expected a key")
                val key = string()
                space()
                expect(':')
                fields[key] = value(depth + 1)
                space()
                when (next()) {
                    ',' -> continue
                    '}' -> return fields
                    else -> fail("Expected , or }")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            i++ // [
            val items = ArrayList<Any?>()
            space()
            if (peek() == ']') return items.also { i++ }
            while (true) {
                items += value(depth + 1)
                space()
                when (next()) {
                    ',' -> continue
                    ']' -> return items
                    else -> fail("Expected , or ]")
                }
            }
        }

        private fun string(): String {
            i++ // "
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                when (val c = s[i++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (i >= s.length) fail("Unterminated escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            // Gson (which wrote the "Send files to TV" headers) writes = < > & ' this way.
                            'u' -> {
                                if (i + 4 > s.length) fail("Short \\u escape")
                                out.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("Bad \\u escape"))
                                i += 4
                            }
                            else -> fail("Bad escape \\$e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): String {
            val start = i
            while (i < s.length && (s[i] in '0'..'9' || s[i] in "+-.eE")) i++
            val text = s.substring(start, i)
            if (text.toDoubleOrNull() == null) fail("Bad number $text")
            return text
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Unexpected token")
            i += word.length
            return value
        }

        private fun space() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun peek() = if (i < s.length) s[i] else fail("Unexpected end")

        private fun next() = peek().also { i++ }

        private fun expect(c: Char) {
            if (next() != c) fail("Expected '$c'")
        }

        private fun fail(message: String): Nothing = throw ProtocolException("Malformed JSON: $message")
    }

    private const val MAX_DEPTH = 16
}
