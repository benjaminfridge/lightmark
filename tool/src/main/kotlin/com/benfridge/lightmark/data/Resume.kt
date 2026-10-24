package com.benfridge.lightmark.data

/**
 * Everything needed to reopen the last thing that was being read.
 *
 * [chapters] and [index] are what the reader takes, so resuming a chapter of a book
 * arrives with the rest of the book alongside it and swiping carries on as normal.
 */
data class Resume(
    val chapters: List<DocumentRef>,
    val index: Int,
    val title: String,
    /** The book it belongs to, or null for a loose document. */
    val bookTitle: String?,
)

/**
 * Finds [documentId] in the library.
 *
 * Returns null when the document is no longer there — a chapter renamed or a file
 * deleted since it was last read — so a stale pointer offers nothing rather than
 * opening the wrong thing.
 */
fun resumeTarget(library: Library, documentId: String?): Resume? {
    if (documentId.isNullOrEmpty()) return null

    library.books.forEach { book ->
        val index = book.chapters.indexOfFirst { it.id == documentId }
        if (index >= 0) {
            return Resume(
                chapters = book.chapters,
                index = index,
                title = book.chapters[index].title,
                bookTitle = book.title,
            )
        }
    }

    val single = library.singles.firstOrNull { it.id == documentId } ?: return null
    return Resume(chapters = listOf(single), index = 0, title = single.title, bookTitle = null)
}
