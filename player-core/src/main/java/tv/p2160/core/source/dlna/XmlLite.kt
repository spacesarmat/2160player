package tv.p2160.core.source.dlna

/**
 * Элемент XML-дерева. Имена — локальные (без префикса пространства имён):
 * DLNA-серверы используют разные префиксы (`dc:`, `upnp:`, `ns0:`…) или вовсе забывают их объявить.
 */
class XmlNode(
    val name: String,
    val prefix: String?,
    /** Атрибуты по локальному имени (`sec:type` → `type`). */
    val attributes: Map<String, String>,
) {
    val children = ArrayList<XmlNode>()
    internal val textBuilder = StringBuilder()

    /** Текст элемента (без вложенных элементов), с декодированными сущностями. */
    val text: String get() = textBuilder.toString()

    fun child(name: String): XmlNode? = children.firstOrNull { it.name.equals(name, true) }
    fun children(name: String): List<XmlNode> = children.filter { it.name.equals(name, true) }
    fun childText(name: String): String? = child(name)?.text?.trim()?.takeIf { it.isNotEmpty() }
    fun attr(name: String): String? = attributes.entries.firstOrNull { it.key.equals(name, true) }?.value

    /** Первый потомок с таким именем на любой глубине (обход в ширину). */
    fun find(name: String): XmlNode? {
        val queue = ArrayDeque(children)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.name.equals(name, true)) return n
            queue.addAll(n.children)
        }
        return null
    }

    override fun toString(): String = "<$name ${attributes}>${text.take(40)}"
}

/**
 * Небольшой терпимый к ошибкам XML-парсер. Серверы DLNA нередко отдают «почти XML»:
 * неэкранированные `&`, неизвестные сущности, незакрытые теги, мусор до корня — здесь это не фатально.
 * Пространства имён сводятся к локальным именам.
 */
object XmlLite {

    fun parse(xml: String): XmlNode {
        val root = XmlNode("#document", null, emptyMap())
        val stack = ArrayList<XmlNode>().apply { add(root) }
        var i = 0
        val n = xml.length
        // Пропускаем BOM.
        if (n > 0 && xml[0].code == 0xFEFF) i = 1
        while (i < n) {
            val c = xml[i]
            if (c != '<') {
                val end = xml.indexOf('<', i).let { if (it < 0) n else it }
                if (stack.size > 1) stack.last().textBuilder.append(decodeEntities(xml.substring(i, end)))
                i = end
                continue
            }
            when {
                xml.startsWith("<!--", i) -> {
                    val end = xml.indexOf("-->", i + 4)
                    i = if (end < 0) n else end + 3
                }
                xml.startsWith("<![CDATA[", i) -> {
                    val end = xml.indexOf("]]>", i + 9)
                    val stop = if (end < 0) n else end
                    if (stack.size > 1) stack.last().textBuilder.append(xml, i + 9, stop)
                    i = if (end < 0) n else end + 3
                }
                xml.startsWith("<?", i) -> {
                    val end = xml.indexOf("?>", i + 2)
                    i = if (end < 0) n else end + 2
                }
                xml.startsWith("<!", i) -> {
                    i = skipDeclaration(xml, i)
                }
                i + 1 < n && xml[i + 1] == '/' -> {
                    val end = xml.indexOf('>', i).let { if (it < 0) n else it }
                    val local = localName(xml.substring(i + 2, end).trim()).second
                    // Закрываем ближайший открытый элемент с таким именем; лишние закрывающие теги игнорируем.
                    val idx = stack.indexOfLast { it.name.equals(local, true) }
                    if (idx > 0) while (stack.size > idx) stack.removeAt(stack.size - 1)
                    i = end + 1
                }
                i + 1 < n && (xml[i + 1].isLetter() || xml[i + 1] == '_' || xml[i + 1] == ':') -> {
                    val end = tagEnd(xml, i + 1)
                    val raw = xml.substring(i + 1, end)
                    val selfClosing = raw.endsWith("/")
                    val body = if (selfClosing) raw.dropLast(1) else raw
                    val nameEnd = body.indexOfFirst { it.isWhitespace() }.let { if (it < 0) body.length else it }
                    val (prefix, local) = localName(body.substring(0, nameEnd))
                    val node = XmlNode(local, prefix, parseAttributes(body.substring(nameEnd)))
                    stack.last().children += node
                    if (!selfClosing) stack += node
                    i = if (end < n && xml[end] == '>') end + 1 else end
                }
                else -> {
                    // Одиночный `<` в тексте.
                    if (stack.size > 1) stack.last().textBuilder.append('<')
                    i++
                }
            }
        }
        return root
    }

    /** Корневой элемент документа или `null`, если элементов нет. */
    fun parseRoot(xml: String): XmlNode? = parse(xml).children.firstOrNull()

    private fun skipDeclaration(xml: String, start: Int): Int {
        // <!DOCTYPE ... [ ... ]>
        var depth = 0
        var i = start + 2
        while (i < xml.length) {
            when (xml[i]) {
                '[' -> depth++
                ']' -> depth--
                '>' -> if (depth <= 0) return i + 1
            }
            i++
        }
        return xml.length
    }

    /** Позиция `>` в конце тега (с учётом `>` внутри атрибутов); если тег не закрыт — позиция следующего `<`. */
    private fun tagEnd(xml: String, start: Int): Int {
        var quote = 0.toChar()
        var i = start
        while (i < xml.length) {
            val c = xml[i]
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar()
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return i
            } else if (c == '<') {
                // Тег не закрыт — считаем, что он закончился здесь.
                return i
            }
            i++
        }
        return xml.length
    }

    private fun localName(qname: String): Pair<String?, String> {
        val colon = qname.indexOf(':')
        return if (colon < 0) null to qname else qname.substring(0, colon) to qname.substring(colon + 1)
    }

    private val attrRegex = Regex("""([^\s=/]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>"']+))""")

    private fun parseAttributes(s: String): Map<String, String> {
        if (s.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        attrRegex.findAll(s).forEach { m ->
            val (prefix, local) = localName(m.groupValues[1])
            if (prefix == "xmlns" || local == "xmlns" && prefix == null) return@forEach
            val value = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value.orEmpty()
            map.putIfAbsent(local, decodeEntities(value))
        }
        return map
    }

    /** Декодирует `&lt;`, `&amp;`, `&#123;`, `&#x1F;`. Неизвестные сущности и одиночный `&` оставляет как есть. */
    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i + 1)
                if (semi in (i + 2)..(i + 10)) {
                    val ent = s.substring(i + 1, semi)
                    val decoded: String? = when {
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "amp" -> "&"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent == "nbsp" -> " "
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let(::codePoint)
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let(::codePoint)
                        else -> null
                    }
                    if (decoded != null) {
                        out.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun codePoint(cp: Int): String? =
        if (cp in 0..0x10FFFF) String(Character.toChars(cp)) else null

    /** Экранирование для текста/атрибутов в SOAP-запросе. */
    fun escape(s: String): String = buildString(s.length + 8) {
        s.forEach { c ->
            when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
