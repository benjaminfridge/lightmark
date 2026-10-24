package com.benfridge.lightmark.export

import com.benfridge.lightmark.data.Highlight
import com.benfridge.lightmark.data.NATURAL
import com.benfridge.lightmark.data.titleFromDocumentId

/**
 * Turns stored highlights into a Markdown document.
 *
 * Pure: no Android, no Room, no filesystem, so the format is unit-testable and the same
 * function serves both the on-device export and a future Tool Manager job.
 *
 * The output is plain blockquotes under one heading per document, which is what Notion,
 * Obsidian and Readwise all ingest without a custom importer. No position metadata: an
 * offset means nothing to a reader, and including it would invite trusting a number that
 * is only valid against the source file as it was.
 */

/** One document's highlights, in reading order. */
data class ExportSection(
    val title: String,
    val quotes: List<String>,
)

/**
 * Groups rows by document, in reading order both between documents and within each.
 *
 * Sorted with the same natural comparator the library and the chapter list use, not
 * lexicographically: plain string order puts chapter 10 before chapter 2, so an export
 * of a book would come out shuffled against the order it was read in.
 */
fun sectionsFrom(highlights: List<Highlight>): List<ExportSection> =
    highlights
        .groupBy { it.documentId }
        .entries
        .sortedWith(compareBy(NATURAL) { it.key })
        .map { (documentId, rows) ->
            ExportSection(
                title = titleFromDocumentId(documentId),
                quotes = rows.sortedBy { it.start }.map { it.text },
            )
        }
        .filter { it.quotes.isNotEmpty() }

/**
 * Renders [sections] as Markdown. [exportedAt] is passed in rather than read from the
 * clock so the output is deterministic under test.
 */
fun formatHighlightsMarkdown(
    sections: List<ExportSection>,
    exportedAt: String,
): String {
    val quoteCount = sections.sumOf { it.quotes.size }
    val builder = StringBuilder()

    builder.append("# Highlights\n\n")
    builder.append(summaryLine(quoteCount, sections.size, exportedAt))
    builder.append("\n")

    sections.forEach { section ->
        builder.append("\n## ").append(section.title).append("\n\n")
        section.quotes.forEach { quote ->
            builder.append(asBlockquote(quote)).append("\n\n")
        }
    }

    return builder.toString().trimEnd() + "\n"
}

private fun summaryLine(quotes: Int, documents: Int, exportedAt: String): String {
    if (quotes == 0) return "Nothing highlighted yet. Exported $exportedAt.\n"
    val q = if (quotes == 1) "1 highlight" else "$quotes highlights"
    val d = if (documents == 1) "1 document" else "$documents documents"
    return "$q across $d. Exported $exportedAt.\n"
}

/**
 * A highlight can span a paragraph break, because the document's plain text joins blocks
 * with a newline. Every line needs its own marker or the quote breaks in half.
 */
private fun asBlockquote(quote: String): String {
    val trimmed = quote.trim()
    if (trimmed.isEmpty()) return "> _(text not recorded)_"
    return trimmed
        .lineSequence()
        .map { line -> if (line.isBlank()) ">" else "> ${line.trim()}" }
        .joinToString("\n")
}
