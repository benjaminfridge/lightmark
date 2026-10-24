package com.benfridge.lightmark.markdown

/**
 * A deliberately small Markdown subset, parsed into blocks that each know where they
 * sit in the document's *plain text*.
 *
 * Highlights are stored as character ranges into [Document.plainText], not as block
 * indices, so reflowing the document (a font-size change, a narrower screen) never
 * moves a highlight. Nothing here touches Android; it is covered by unit tests.
 *
 * There is no Markdown library on the SDK's dependency allow-list, so this is written
 * by hand. Supported: ATX headings, paragraphs, bullet and ordered lists, blockquotes,
 * fenced and indented code, thematic breaks, and inline bold/italic/code/links.
 * Not supported, by choice: tables, reference links, raw HTML, nested lists, footnotes.
 */

enum class Emphasis { Bold, Italic, Code, Link }

/** A run of text sharing one set of emphases. */
data class Span(val text: String, val emphasis: Set<Emphasis> = emptySet())

sealed interface Block {
    /** The inline content. Empty for blocks that render no text, like [Rule]. */
    val spans: List<Span>

    data class Heading(val level: Int, override val spans: List<Span>) : Block
    data class Paragraph(override val spans: List<Span>) : Block
    data class Quote(override val spans: List<Span>) : Block

    /** [marker] is the rendered bullet or ordinal; it is not part of the plain text. */
    data class ListItem(val marker: String, override val spans: List<Span>) : Block

    data class Code(val text: String) : Block {
        override val spans: List<Span> get() = listOf(Span(text, setOf(Emphasis.Code)))
    }

    data object Rule : Block {
        override val spans: List<Span> get() = emptyList()
    }
}

/** A [Block] placed at [start] in the document's plain text. */
data class PlacedBlock(val block: Block, val start: Int) {
    val text: String = block.spans.joinToString("") { it.text }
    val end: Int get() = start + text.length
}

data class Document(val blocks: List<PlacedBlock>) {
    /** Blocks joined by a single newline. Highlight offsets index into this. */
    val plainText: String = blocks.joinToString("\n") { it.text }

    val isEmpty: Boolean get() = blocks.isEmpty()
}

object Markdown {

    fun parse(source: String): Document {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<Block>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            when {
                trimmed.isEmpty() -> i++

                isFence(trimmed) -> {
                    val fence = trimmed.take(3)
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                        body += lines[i]
                        i++
                    }
                    if (i < lines.size) i++ // closing fence
                    blocks += Block.Code(body.joinToString("\n").trimEnd())
                }

                isRule(trimmed) -> {
                    blocks += Block.Rule
                    i++
                }

                headingLevel(trimmed) > 0 -> {
                    val level = headingLevel(trimmed)
                    val content = trimmed.drop(level).trim().trimEnd('#').trim()
                    blocks += Block.Heading(level, parseInline(content))
                    i++
                }

                trimmed.startsWith(">") -> {
                    val body = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        body += lines[i].trim().removePrefix(">").trim()
                        i++
                    }
                    blocks += Block.Quote(parseInline(body.joinToString(" ").trim()))
                }

                bulletMarker(trimmed) != null -> {
                    // One block per item; continuation lines fold into the item.
                    val marker = bulletMarker(trimmed)!!
                    val first = trimmed.removePrefix(marker).trim()
                    val body = mutableListOf(first)
                    i++
                    while (i < lines.size &&
                        lines[i].isNotBlank() &&
                        bulletMarker(lines[i].trim()) == null &&
                        headingLevel(lines[i].trim()) == 0 &&
                        !lines[i].trim().startsWith(">") &&
                        !isRule(lines[i].trim()) &&
                        !isFence(lines[i].trim())
                    ) {
                        body += lines[i].trim()
                        i++
                    }
                    val rendered = if (marker.isOrdered()) marker.trim() else BULLET
                    blocks += Block.ListItem(rendered, parseInline(body.joinToString(" ")))
                }

                line.startsWith("    ") || line.startsWith("\t") -> {
                    val body = mutableListOf<String>()
                    while (i < lines.size && (lines[i].startsWith("    ") || lines[i].startsWith("\t") || lines[i].isBlank())) {
                        if (lines[i].isBlank() && body.isEmpty()) { i++; continue }
                        body += lines[i].removePrefix("\t").removePrefix("    ")
                        i++
                    }
                    blocks += Block.Code(body.joinToString("\n").trimEnd())
                }

                else -> {
                    val body = mutableListOf(trimmed)
                    i++
                    while (i < lines.size &&
                        lines[i].isNotBlank() &&
                        headingLevel(lines[i].trim()) == 0 &&
                        bulletMarker(lines[i].trim()) == null &&
                        !lines[i].trim().startsWith(">") &&
                        !isRule(lines[i].trim()) &&
                        !isFence(lines[i].trim())
                    ) {
                        body += lines[i].trim()
                        i++
                    }
                    blocks += Block.Paragraph(parseInline(body.joinToString(" ")))
                }
            }
        }

        return place(blocks)
    }

    /** Assigns each block its offset into the joined plain text. */
    private fun place(blocks: List<Block>): Document {
        var cursor = 0
        val placed = blocks.map { block ->
            val p = PlacedBlock(block, cursor)
            cursor = p.end + 1 // the "\n" joining blocks
            p
        }
        return Document(placed)
    }

    private const val BULLET = "•  "

    private fun String.isOrdered(): Boolean = first().isDigit()

    private fun isFence(trimmed: String) = trimmed.startsWith("```") || trimmed.startsWith("~~~")

    private fun isRule(trimmed: String): Boolean {
        if (trimmed.length < 3) return false
        val c = trimmed.first()
        if (c != '-' && c != '*' && c != '_') return false
        return trimmed.all { it == c || it == ' ' } && trimmed.count { it == c } >= 3
    }

    private fun headingLevel(trimmed: String): Int {
        val hashes = trimmed.takeWhile { it == '#' }.length
        if (hashes !in 1..6) return 0
        // "#foo" is not a heading; "# foo" is.
        return if (trimmed.length > hashes && trimmed[hashes] == ' ') hashes else 0
    }

    /** Returns the literal list marker ("- ", "3. ") or null. */
    private fun bulletMarker(trimmed: String): String? {
        for (b in listOf("- ", "* ", "+ ")) {
            // Guard against "***" being read as a list.
            if (trimmed.startsWith(b) && !isRule(trimmed)) return b
        }
        val digits = trimmed.takeWhile { it.isDigit() }
        if (digits.isNotEmpty() && digits.length <= 3) {
            val rest = trimmed.drop(digits.length)
            if (rest.startsWith(". ") || rest.startsWith(") ")) {
                return trimmed.take(digits.length + 2)
            }
        }
        return null
    }

    // ---- inline ----

    /**
     * Scans inline markup into [Span]s. Link URLs are dropped: the text stays, so plain-text
     * offsets never include a URL the reader cannot see.
     */
    internal fun parseInline(raw: String): List<Span> {
        if (raw.isEmpty()) return emptyList()

        val spans = mutableListOf<Span>()
        val buffer = StringBuilder()
        var active = mutableSetOf<Emphasis>()
        var i = 0

        fun flush() {
            if (buffer.isNotEmpty()) {
                spans += Span(buffer.toString(), active.toSet())
                buffer.clear()
            }
        }

        fun toggle(e: Emphasis) {
            flush()
            active = active.toMutableSet().apply { if (!remove(e)) add(e) }
        }

        while (i < raw.length) {
            val c = raw[i]

            // Escapes
            if (c == '\\' && i + 1 < raw.length && raw[i + 1] in "\\`*_[]()#>-+") {
                buffer.append(raw[i + 1]); i += 2; continue
            }

            // Inline code wins over everything inside it.
            if (c == '`') {
                val close = raw.indexOf('`', i + 1)
                if (close > i) {
                    flush()
                    spans += Span(raw.substring(i + 1, close), active + Emphasis.Code)
                    i = close + 1
                    continue
                }
            }

            // Links: [text](url) -> keep text only.
            if (c == '[') {
                val closeBracket = raw.indexOf(']', i)
                if (closeBracket > i && closeBracket + 1 < raw.length && raw[closeBracket + 1] == '(') {
                    val closeParen = raw.indexOf(')', closeBracket)
                    if (closeParen > closeBracket) {
                        val label = raw.substring(i + 1, closeBracket)
                        flush()
                        spans += parseInline(label).map {
                            Span(it.text, it.emphasis + active + Emphasis.Link)
                        }
                        i = closeParen + 1
                        continue
                    }
                }
            }

            if (c == '*' || c == '_') {
                val isDouble = i + 1 < raw.length && raw[i + 1] == c
                if (isDouble) {
                    toggle(Emphasis.Bold); i += 2; continue
                }
                // `_` only delimits at a word boundary, so snake_case survives.
                val boundaryOk = c == '*' || run {
                    val before = raw.getOrNull(i - 1)
                    val after = raw.getOrNull(i + 1)
                    val opening = (before == null || !before.isLetterOrDigit()) && after != null && !after.isWhitespace()
                    val closing = Emphasis.Italic in active && (before != null && !before.isWhitespace())
                    opening || closing
                }
                if (boundaryOk) {
                    toggle(Emphasis.Italic); i++; continue
                }
            }

            buffer.append(c)
            i++
        }

        flush()
        return spans
    }
}
