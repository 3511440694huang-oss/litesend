package app.qingchuan.core

/**
 * 极简 JSON —— 零第三方依赖，纯 Kotlin 实现，可直接在 JVM 单元测试里运行。
 *
 * 之所以不用 org.json：Android 平台自带 org.json，但 JVM 单测里没有；
 * 引入 maven 的 org.json 又会和系统类重复。协议报文很小，自己实现更可控。
 */
sealed class J {

    object Null : J()

    data class B(val value: Boolean) : J()

    data class N(val value: Double) : J()

    data class S(val value: String) : J()

    data class A(val items: MutableList<J> = mutableListOf()) : J()

    data class O(val fields: LinkedHashMap<String, J> = LinkedHashMap()) : J()

    val isNull: Boolean get() = this is Null

    val asString: String? get() = (this as? S)?.value

    val asLong: Long? get() = when (this) {
        is N -> if (value == kotlin.math.floor(value) && !value.isInfinite()) value.toLong() else null
        is S -> value.toLongOrNull()
        else -> null
    }

    val asInt: Int? get() = asLong?.toInt()

    val asDouble: Double? get() = when (this) {
        is N -> value
        is S -> value.toDoubleOrNull()
        else -> null
    }

    val asBool: Boolean? get() = when (this) {
        is B -> value
        is S -> when (value.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
        else -> null
    }

    val asArray: List<J>? get() = (this as? A)?.items

    val asObject: Map<String, J>? get() = (this as? O)?.fields

    operator fun get(key: String): J? = (this as? O)?.fields?.get(key)

    operator fun get(index: Int): J? = (this as? A)?.items?.getOrNull(index)

    fun str(key: String): String? = get(key)?.asString

    fun long(key: String): Long? = get(key)?.asLong

    fun int(key: String): Int? = get(key)?.asInt

    fun bool(key: String): Boolean? = get(key)?.asBool

    fun array(key: String): List<J> = get(key)?.asArray ?: emptyList()

    fun obj(key: String): J? = get(key)?.takeIf { it is O }

    fun stringify(): String = buildString { JsonWriter.write(this, this@J) }

    override fun toString(): String = stringify()

    companion object {
        val EMPTY: J = O()

        fun of(value: String?): J = if (value == null) Null else S(value)

        fun of(value: Boolean): J = B(value)

        fun of(value: Int): J = N(value.toDouble())

        fun of(value: Long): J = N(value.toDouble())

        fun of(value: Double): J = N(value)

        fun arr(vararg items: J): A = A(items.toMutableList())

        fun arr(items: List<J>): A = A(items.toMutableList())

        fun obj(vararg pairs: Pair<String, J?>): O {
            val o = O()
            for ((k, v) in pairs) if (v != null) o.fields[k] = v
            return o
        }

        fun parse(text: String?): J {
            if (text.isNullOrBlank()) return Null
            val p = Parser(text)
            return try {
                p.value()
            } catch (_: Exception) {
                Null
            }
        }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun value(): J {
            ws()
            if (i >= s.length) throw IllegalStateException("eof")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> S(string())
                't' -> { expect("true"); B(true) }
                'f' -> { expect("false"); B(false) }
                'n' -> { expect("null"); Null }
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalStateException("bad char $c")
            }
        }

        private fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        private fun expect(word: String) {
            if (!s.startsWith(word, i)) throw IllegalStateException("expect $word at $i")
            i += word.length
        }

        private fun obj(): J {
            i++ // {
            val o = O()
            ws()
            if (i < s.length && s[i] == '}') { i++; return o }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw IllegalStateException("key expected at $i")
                val k = string()
                ws()
                if (i >= s.length || s[i] != ':') throw IllegalStateException(": expected at $i")
                i++
                val v = value()
                o.fields[k] = v
                ws()
                if (i >= s.length) throw IllegalStateException("eof in object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return o }
                    else -> throw IllegalStateException("bad sep at $i")
                }
            }
        }

        private fun array(): J {
            i++ // [
            val a = A()
            ws()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (true) {
                a.items.add(value())
                ws()
                if (i >= s.length) throw IllegalStateException("eof in array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return a }
                    else -> throw IllegalStateException("bad sep at $i")
                }
            }
        }

        private fun string(): String {
            if (s[i] != '"') throw IllegalStateException("quote expected")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) break
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 <= s.length) {
                                    val hex = s.substring(i, i + 4)
                                    sb.append(hex.toInt(16).toChar())
                                    i += 4
                                }
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalStateException("unterminated string")
        }

        private fun number(): J {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val raw = s.substring(start, i)
            return N(raw.toDoubleOrNull() ?: throw IllegalStateException("bad number $raw"))
        }
    }

    private object JsonWriter {
        fun write(sb: StringBuilder, v: J) {
            when (v) {
                is Null -> sb.append("null")
                is B -> sb.append(if (v.value) "true" else "false")
                is N -> {
                    val d = v.value
                    if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 9.0e15) sb.append(d.toLong()) else sb.append(d)
                }
                is S -> escape(sb, v.value)
                is A -> {
                    sb.append('[')
                    v.items.forEachIndexed { idx, item ->
                        if (idx > 0) sb.append(',')
                        write(sb, item)
                    }
                    sb.append(']')
                }
                is O -> {
                    sb.append('{')
                    var first = true
                    for ((k, value) in v.fields) {
                        if (!first) sb.append(',')
                        first = false
                        escape(sb, k)
                        sb.append(':')
                        write(sb, value)
                    }
                    sb.append('}')
                }
            }
        }

        private fun escape(sb: StringBuilder, str: String) {
            sb.append('"')
            for (c in str) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c.code < 0x20) sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
                }
            }
            sb.append('"')
        }
    }
}
