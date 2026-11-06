package dev.veyra.source.podcast

import dev.veyra.model.FailureCode
import dev.veyra.source.network.sourceFailure

/** A deliberately small, non-validating XML reader. No DTD, entity declarations or I/O exist. */
internal object BoundedXml {
    fun parse(xml: String): XmlNode {
        if (xml.length !in 1..8 * 1024 * 1024 || xml.any { it.code == 0 }) malformed("xml_size_or_characters")
        return Reader(xml.removePrefix("\uFEFF")).read()
    }

    private class Reader(private val input: String) {
        private var offset = 0
        private var nodes = 0
        private val stack = mutableListOf<XmlNode>()
        private var root: XmlNode? = null

        fun read(): XmlNode {
            while (offset < input.length) {
                when {
                    input.startsWith("<!--", offset) -> skip("-->", 4)
                    input.startsWith("<?", offset) -> skip("?>", 2)
                    input.startsWith("<![CDATA[", offset) -> {
                        val end = input.indexOf("]]>", offset + 9)
                        if (end < 0 || stack.isEmpty()) malformed("invalid_cdata")
                        appendText(input.substring(offset + 9, end)); offset = end + 3
                    }
                    input.startsWith("<!", offset) -> malformed("xml_declarations_disabled")
                    input.startsWith("</", offset) -> closeTag()
                    input[offset] == '<' -> openTag()
                    else -> {
                        val end = input.indexOf('<', offset).let { if (it < 0) input.length else it }
                        val text = xmlEntities(input.substring(offset, end), strict = true)
                        if (stack.isEmpty()) { if (text.isNotBlank()) malformed("text_outside_root") }
                        else appendText(text)
                        offset = end
                    }
                }
            }
            if (stack.isNotEmpty()) malformed("unclosed_xml")
            return root ?: malformed("missing_xml_root")
        }

        private fun skip(endMarker: String, startLength: Int) {
            val end = input.indexOf(endMarker, offset + startLength)
            if (end < 0) malformed("unclosed_xml_token")
            offset = end + endMarker.length
        }

        private fun appendText(value: String) {
            if (++nodes > 150_000) malformed("xml_node_limit")
            stack.last().parts += XmlText(value)
        }

        private fun name(): String {
            val start = offset
            while (offset < input.length && (input[offset].isLetterOrDigit() || input[offset] in "_:.-")) offset++
            val value = input.substring(start, offset)
            if (value.isEmpty() || value.length > 200 || (!value.first().isLetter() && value.first() != '_') || value.count { it == ':' } > 1) {
                malformed("invalid_xml_name")
            }
            return value
        }

        private fun whitespace() { while (offset < input.length && input[offset].isWhitespace()) offset++ }

        private fun openTag() {
            offset++
            val qualifiedName = name()
            val attributes = linkedMapOf<String, String>()
            var selfClosing = false
            while (true) {
                whitespace()
                if (offset >= input.length) malformed("unclosed_xml_tag")
                if (input[offset] == '>') { offset++; break }
                if (input.startsWith("/>", offset)) { offset += 2; selfClosing = true; break }
                val key = name()
                whitespace()
                if (offset >= input.length || input[offset++] != '=') malformed("invalid_xml_attribute")
                whitespace()
                if (offset >= input.length || input[offset] !in "\"'") malformed("unquoted_xml_attribute")
                val quote = input[offset++]
                val end = input.indexOf(quote, offset)
                if (end < 0 || end - offset > 16_384) malformed("xml_attribute_limit")
                val raw = input.substring(offset, end)
                if ('<' in raw || key in attributes) malformed("invalid_xml_attribute")
                attributes[key] = xmlEntities(raw, strict = true)
                if (attributes.size > 128) malformed("xml_attribute_limit")
                offset = end + 1
            }
            val namespaces = (stack.lastOrNull()?.namespaces ?: emptyMap()).toMutableMap()
            for ((key, value) in attributes) {
                if (key == "xmlns") namespaces[""] = value
                else if (key.startsWith("xmlns:")) namespaces[key.substringAfter(':')] = value
            }
            val prefix = qualifiedName.substringBefore(':', "")
            if (prefix.isNotEmpty() && prefix !in namespaces && prefix != "xml") malformed("unknown_xml_namespace")
            val node = XmlNode(qualifiedName, namespaces[prefix].orEmpty(), attributes, namespaces)
            if (++nodes > 150_000 || stack.size >= 48) malformed("xml_structure_limit")
            if (stack.isEmpty()) {
                if (root != null) malformed("multiple_xml_roots")
                root = node
            } else stack.last().parts += node
            if (!selfClosing) stack += node
        }

        private fun closeTag() {
            offset += 2
            val qualifiedName = name()
            whitespace()
            if (offset >= input.length || input[offset++] != '>' || stack.lastOrNull()?.name != qualifiedName) malformed("mismatched_xml_tag")
            stack.removeAt(stack.lastIndex)
        }
    }
}

internal sealed interface XmlPart
internal data class XmlText(val text: String) : XmlPart
internal class XmlNode(
    val name: String,
    val namespace: String,
    val attributes: Map<String, String>,
    val namespaces: Map<String, String>,
    val parts: MutableList<XmlPart> = mutableListOf(),
) : XmlPart {
    val localName: String get() = name.substringAfter(':')
    fun children(localName: String, namespace: String = this.namespace): List<XmlNode> =
        parts.filterIsInstance<XmlNode>().filter { it.localName == localName && it.namespace == namespace }
    fun child(localName: String, namespace: String = this.namespace): XmlNode? = children(localName, namespace).firstOrNull()
    /** Identity and search titles must fail on excess input, never silently change by truncation. */
    fun completeValue(limit: Int): String = buildString {
        fun add(part: XmlPart) {
            when (part) {
                is XmlText -> {
                    if (part.text.length > limit - length) malformed("xml_complete_value_limit")
                    append(part.text)
                }
                is XmlNode -> part.parts.forEach(::add)
            }
        }
        add(this@XmlNode)
    }.trim()

    fun value(limit: Int = 50_000): String = buildString {
        fun add(part: XmlPart) {
            if (length >= limit) return
            when (part) {
                is XmlText -> append(part.text.take(limit - length))
                is XmlNode -> part.parts.forEach(::add)
            }
        }
        add(this@XmlNode)
    }.trim()
}

internal fun xmlEntities(value: String, strict: Boolean): String {
    if ('&' !in value) return value
    return buildString {
        var cursor = 0
        while (cursor < value.length) {
            if (value[cursor] != '&') { append(value[cursor++]); continue }
            val end = value.indexOf(';', cursor + 1)
            if (end < 0 || end - cursor > 16) {
                if (strict) malformed("invalid_xml_entity")
                append(value[cursor++]); continue
            }
            val token = value.substring(cursor + 1, end)
            val named = when (token) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                "nbsp" -> if (strict) null else " "
                "rsquo" -> if (strict) null else "’"
                "lsquo" -> if (strict) null else "‘"
                "rdquo" -> if (strict) null else "”"
                "ldquo" -> if (strict) null else "“"
                else -> null
            }
            val code = when {
                token.startsWith("#x") -> token.substring(2).toIntOrNull(16)
                token.startsWith('#') -> token.substring(1).toIntOrNull()
                else -> null
            }
            when {
                named != null -> append(named)
                code != null && (code == 9 || code == 10 || code == 13 || code in 32..0x10ffff) && code !in 0xd800..0xdfff -> {
                    if (code < 0x10000) append(code.toChar())
                    else { val n = code - 0x10000; append((0xd800 + (n ushr 10)).toChar()); append((0xdc00 + (n and 1023)).toChar()) }
                }
                strict -> malformed("undeclared_xml_entity")
                else -> append(value.substring(cursor, end + 1))
            }
            cursor = end + 1
        }
    }
}

internal fun malformed(detail: String): Nothing = sourceFailure(FailureCode.PARSE_FAILURE, detail)
