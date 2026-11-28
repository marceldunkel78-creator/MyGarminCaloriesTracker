package com.example.mygarmincaloriestracker

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Strict bounded JSON transport; no data is interpreted as SQL. Pure JVM for local tests. */
internal object BackupJson {
    const val MAX_BYTES = 16 * 1024 * 1024

    fun read(input: InputStream): BackupData {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(n > 0) { "Backup stream made no progress" }
            require(bytes.size().toLong() + n <= MAX_BYTES) { "Backup too large" }
            bytes.write(buffer, 0, n)
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        val root = Parser(text).parse() as? Map<*, *> ?: error("Invalid backup root")
        require(root.keys == setOf("version", "tables")) { "Invalid backup root" }
        val version = root["version"] as? Long ?: error("Invalid backup version")
        val rawTables = root["tables"] as? Map<*, *> ?: error("Invalid backup tables")
        val tables = linkedMapOf<String, List<Map<String, Any?>>>()
        for ((name, value) in rawTables) {
            require(name is String && value is List<*>) { "Invalid backup table" }
            tables[name] = value.map { row ->
                require(row is Map<*, *> && row.keys.all { it is String }) { "Invalid backup row" }
                @Suppress("UNCHECKED_CAST")
                row as Map<String, Any?>
            }
        }
        return BackupValidation.validate(BackupData(version, tables))
    }

    private class Parser(private val text: String) {
        private var pos = 0
        private var rows = 0
        private fun ws() { while (pos < text.length && text[pos] in " \t\r\n") pos++ }
        private fun take(c: Char): Boolean { ws(); if (pos < text.length && text[pos] == c) { pos++; return true }; return false }
        private fun expect(c: Char) { require(take(c)) { "Invalid JSON" } }
        fun parse(): Any? { val result = value(0); ws(); require(pos == text.length) { "Trailing JSON" }; return result }

        private fun value(depth: Int): Any? {
            require(depth <= 4) { "Backup nesting too deep" }
            ws()
            require(pos < text.length) { "Incomplete JSON" }
            return when (text[pos]) {
                '{' -> {
                    pos++
                    val result = linkedMapOf<String, Any?>()
                    if (!take('}')) {
                        do {
                            ws(); require(pos < text.length && text[pos] == '"') { "Invalid JSON key" }
                            val key = string()
                            require(key.length <= 128 && result.size < 32 && !result.containsKey(key)) { "Invalid JSON key" }
                            expect(':'); result[key] = value(depth + 1)
                        } while (take(','))
                        expect('}')
                    }
                    result
                }
                '[' -> {
                    pos++
                    val result = mutableListOf<Any?>()
                    if (!take(']')) {
                        do {
                            require(depth == 2 && ++rows <= BackupValidation.MAX_ROWS) { "Too many rows" }
                            result.add(value(depth + 1))
                        } while (take(','))
                        expect(']')
                    }
                    result
                }
                '"' -> string()
                'n' -> { require(text.startsWith("null", pos)) { "Invalid JSON" }; pos += 4; null }
                '-', in '0'..'9' -> number()
                else -> error("Invalid JSON")
            }
        }

        private fun string(): String {
            require(pos < text.length && text[pos++] == '"') { "Invalid string" }
            val out = StringBuilder()
            while (pos < text.length) {
                val c = text[pos++]
                if (c == '"') return out.toString()
                require(c >= ' ') { "Invalid string" }
                if (c == '\\') {
                    require(pos < text.length) { "Invalid escape" }
                    when (val escape = text[pos++]) {
                        '"', '\\', '/' -> out.append(escape)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            require(pos + 4 <= text.length) { "Invalid escape" }
                            val digits = text.substring(pos, pos + 4)
                            require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid escape" }
                            val code = digits.toInt(16)
                            out.append(code.toChar()); pos += 4
                        }
                        else -> error("Invalid escape")
                    }
                } else out.append(c)
                require(out.length <= BackupValidation.MAX_TEXT_LENGTH) { "String too long" }
            }
            error("Incomplete string")
        }

        private fun number(): Any {
            val start = pos
            if (text[pos] == '-') pos++
            require(pos < text.length) { "Invalid number" }
            if (text[pos] == '0') pos++ else {
                require(text[pos] in '1'..'9') { "Invalid number" }
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            var real = false
            if (pos < text.length && text[pos] == '.') {
                real = true; pos++
                val begin = pos
                while (pos < text.length && text[pos] in '0'..'9') pos++
                require(pos > begin) { "Invalid number" }
            }
            if (pos < text.length && text[pos] in "eE") {
                real = true; pos++
                if (pos < text.length && text[pos] in "+-") pos++
                val begin = pos
                while (pos < text.length && text[pos] in '0'..'9') pos++
                require(pos > begin) { "Invalid number" }
            }
            val value = text.substring(start, pos)
            return if (real) {
                value.toDoubleOrNull() ?: error("Invalid number")
            } else {
                value.toLongOrNull() ?: error("Invalid number")
            }
        }
    }

    fun encode(data: BackupData): ByteArray {
        val valid = BackupValidation.validate(data)
        val out = StringBuilder()
        fun string(value: String) {
            out.append('"')
            for (c in value) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    in '\u0000'..'\u001f' -> out.append("\\u%04x".format(c.code))
                    else -> out.append(c)
                }
                require(out.length <= MAX_BYTES) { "Backup too large" }
            }
            out.append('"')
        }
        out.append("{\"version\":${BackupValidation.VERSION},\"tables\":{")
        valid.tables.entries.forEachIndexed { i, (name, rows) ->
            if (i > 0) out.append(',')
            string(name); out.append(":[")
            rows.forEachIndexed { j, row ->
                if (j > 0) out.append(',')
                out.append('{')
                row.entries.forEachIndexed { k, (column, value) ->
                    if (k > 0) out.append(',')
                    string(column); out.append(':')
                    when (value) {
                        null -> out.append("null")
                        is String -> string(value)
                        is Long, is Double -> out.append(value)
                        else -> error("Invalid backup value")
                    }
                }
                out.append('}')
                require(out.length <= MAX_BYTES) { "Backup too large" }
            }
            out.append(']')
        }
        out.append("}}")
        val bytes = out.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Backup too large" }
        return bytes
    }
}
