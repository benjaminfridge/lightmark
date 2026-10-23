package com.benfridge.lightmark.ui

/** A range of the document's plain text, in plain-text coordinates. */
data class DocumentRange(val start: Int, val end: Int) {
    val isEmpty: Boolean get() = end <= start
    operator fun contains(offset: Int): Boolean = offset in start until end
}

/**
 * Grows [offset] to the word around it.
 *
 * Selection is word-granular so no gesture has to be pixel-exact on a 3.92" screen,
 * and so a highlight always starts and ends somewhere a reader would have chosen.
 */
fun snapToWord(text: String, offset: Int): DocumentRange {
    if (text.isEmpty()) return DocumentRange(0, 0)
    val clamped = offset.coerceIn(0, text.length - 1)

    if (!text[clamped].isWordChar()) {
        // Whitespace or punctuation: take just that character.
        return DocumentRange(clamped, clamped + 1)
    }
    var start = clamped
    while (start > 0 && text[start - 1].isWordChar()) start--
    var end = clamped + 1
    while (end < text.length && text[end].isWordChar()) end++
    return DocumentRange(start, end)
}

private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '\'' || this == '-'

/** Merges overlapping or touching ranges, so highlights never stack. */
fun List<DocumentRange>.merged(): List<DocumentRange> {
    if (size < 2) return this
    val sorted = sortedBy { it.start }
    val out = mutableListOf(sorted.first())
    sorted.drop(1).forEach { next ->
        val last = out.last()
        if (next.start <= last.end) {
            out[out.lastIndex] = DocumentRange(last.start, maxOf(last.end, next.end))
        } else {
            out += next
        }
    }
    return out
}

/**
 * The span a drag covers, whichever direction it went: from the word under the finger
 * when the press began to the word under it now.
 */
fun selectionBetween(text: String, anchorOffset: Int, cursorOffset: Int): DocumentRange {
    val anchor = snapToWord(text, anchorOffset)
    val cursor = snapToWord(text, cursorOffset)
    return DocumentRange(minOf(anchor.start, cursor.start), maxOf(anchor.end, cursor.end))
}
