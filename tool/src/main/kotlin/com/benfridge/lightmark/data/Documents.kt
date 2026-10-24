package com.benfridge.lightmark.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.sdk.LightFileShare
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Where a document's text came from. */
enum class DocumentSource { Bundled, Shared }

/** "my-trip-notes.md" -> "My trip notes" */
internal fun titleFromFileName(fileName: String): String = fileName
    .substringBeforeLast('.')
    .replace('-', ' ')
    .replace('_', ' ')
    .trim()
    .replaceFirstChar { it.uppercase() }

/**
 * "shared:ways-of-being/02-the-wild-places.md" -> "02 the wild places".
 *
 * Derived from the id rather than looked up, so an export still names a document whose
 * file has since been renamed or removed.
 */
internal fun titleFromDocumentId(documentId: String): String =
    titleFromFileName(documentId.substringAfter(':', documentId).substringAfterLast('/'))

/**
 * Orders "2" before "10", which plain string order gets wrong.
 *
 * Chapter files are named by whoever converted them, and expecting zero-padding is the
 * kind of rule that is remembered once and then isn't. Digit runs compare as numbers,
 * everything else case-insensitively.
 */
internal fun compareNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var iEnd = i
            while (iEnd < a.length && a[iEnd].isDigit()) iEnd++
            var jEnd = j
            while (jEnd < b.length && b[jEnd].isDigit()) jEnd++
            val na = a.substring(i, iEnd).trimStart('0').ifEmpty { "0" }
            val nb = b.substring(j, jEnd).trimStart('0').ifEmpty { "0" }
            if (na.length != nb.length) return na.length - nb.length
            na.compareTo(nb).let { if (it != 0) return it }
            i = iEnd
            j = jEnd
        } else {
            ca.lowercaseChar().compareTo(cb.lowercaseChar()).let { if (it != 0) return it }
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

internal val NATURAL: Comparator<String> = Comparator { a, b -> compareNatural(a, b) }

/**
 * [id] is stable across reparses and reboots, and is what highlights are keyed on.
 * Changing how it is derived orphans every existing highlight.
 */
data class DocumentRef(
    val id: String,
    val title: String,
    val source: DocumentSource,
    val path: String,
    /** The book this is a chapter of, or null for a loose document. */
    val bookId: String? = null,
)

/** A folder of chapters under `documents/`, read in natural filename order. */
data class Book(
    val id: String,
    val title: String,
    val chapters: List<DocumentRef>,
)

/** Everything the library shows, already ordered. */
data class Library(
    val singles: List<DocumentRef> = emptyList(),
    val books: List<Book> = emptyList(),
)

/**
 * What the screens need from storage.
 *
 * An interface so the reader's own logic — which chapter is current, what happens when
 * two loads race — can be exercised without a filesystem or a DataStore behind it.
 */
interface DocumentStore {
    fun library(): Library
    suspend fun read(ref: DocumentRef): String?
    suspend fun saveOffset(documentId: String, characterOffset: Int)
    suspend fun offset(documentId: String): Int
    suspend fun saveCurrentChapter(bookId: String, documentId: String)
    suspend fun currentChapter(bookId: String): String?
    fun ensureSharedDirectory()
}

/**
 * Documents come from two places: a few bundled in the APK, and whatever the user has
 * put in `files/shared/documents`.
 *
 * A loose file there is one document. A *folder* of files is a book, and its files are
 * its chapters — which is how a converted PDF naturally lands, one file per chapter,
 * and it means a 150-page book does not arrive as thirty equal rows in the library.
 */
class DocumentRepository(
    private val lightContext: SealedLightContext,
    private val dataStore: DataStore<Preferences>,
) : DocumentStore {
    private val fileShare: LightFileShare get() = lightContext.fileShare

    override fun library(): Library {
        val shared = runCatching { fileShare.list(SHARED_DIR) }.getOrDefault(emptyList())

        val singles = (bundled() + shared.filter { it.isFile && it.name.isMarkdown() }.map { file ->
            DocumentRef(
                id = "shared:${file.name}",
                title = titleFromFileName(file.name),
                source = DocumentSource.Shared,
                path = "$SHARED_DIR/${file.name}",
            )
        }).sortedWith(compareBy(NATURAL) { it.title })

        val books = shared
            .filter { !it.isFile }
            .mapNotNull { dir -> bookIn(dir.name) }
            .sortedWith(compareBy(NATURAL) { it.title })

        return Library(singles = singles, books = books)
    }

    private fun bookIn(folder: String): Book? {
        val files = runCatching { fileShare.list("$SHARED_DIR/$folder") }
            .getOrDefault(emptyList())
            .filter { it.isFile && it.name.isMarkdown() }
            .sortedWith(compareBy(NATURAL) { it.name })

        if (files.isEmpty()) return null

        val bookId = "book:$folder"
        return Book(
            id = bookId,
            title = titleFromFileName(folder),
            chapters = files.map { file ->
                DocumentRef(
                    id = "shared:$folder/${file.name}",
                    title = titleFromFileName(file.name),
                    source = DocumentSource.Shared,
                    path = "$SHARED_DIR/$folder/${file.name}",
                    bookId = bookId,
                )
            },
        )
    }

    private fun bundled(): List<DocumentRef> {
        val index = runCatching {
            String(lightContext.readAsset("$ASSET_DIR/$INDEX_FILE"))
        }.getOrNull() ?: return emptyList()

        return index.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { name ->
                DocumentRef(
                    id = "bundled:$name",
                    title = titleFromFileName(name),
                    source = DocumentSource.Bundled,
                    path = "$ASSET_DIR/$name",
                )
            }
            .toList()
    }

    /** Returns null when the file has gone away since it was listed. */
    override suspend fun read(ref: DocumentRef): String? = when (ref.source) {
        DocumentSource.Bundled -> runCatching { String(lightContext.readAsset(ref.path)) }.getOrNull()
        DocumentSource.Shared -> runCatching { fileShare.read(ref.path) { it.readText() } }.getOrNull()
    }

    /**
     * Reading position as a character offset into `Document.plainText`.
     *
     * Deliberately not a pixel or page number: those shift whenever the text reflows or
     * the type scale changes, and an activity recreation (light-sdk#225) is enough to
     * invalidate a pixel. A character offset resolves back to whichever page holds it.
     */
    override suspend fun saveOffset(documentId: String, characterOffset: Int) {
        dataStore.edit { it[offsetKey(documentId)] = characterOffset }
    }

    override suspend fun offset(documentId: String): Int =
        dataStore.data.map { it[offsetKey(documentId)] ?: 0 }.first()

    /**
     * Which chapter a book was last open at.
     *
     * Splitting a book into files gives every chapter its own position but leaves the
     * book with none, so this is the one piece of state that makes a shelf of chapters
     * behave like something you were reading.
     */
    override suspend fun saveCurrentChapter(bookId: String, documentId: String) {
        dataStore.edit { it[chapterKey(bookId)] = documentId }
    }

    override suspend fun currentChapter(bookId: String): String? =
        dataStore.data.map { it[chapterKey(bookId)] }.first()

    /** Makes the shared directory exist, so the Tool Manager has somewhere to write. */
    override fun ensureSharedDirectory() {
        if (!fileShare.exists(SHARED_DIR)) {
            runCatching { fileShare.write("$SHARED_DIR/.keep") { it.write("") } }
        }
    }

    private companion object {
        const val ASSET_DIR = "documents"
        const val SHARED_DIR = "documents"
        const val INDEX_FILE = "index.txt"

        fun offsetKey(documentId: String) = intPreferencesKey("offset:$documentId")
        fun chapterKey(bookId: String) = stringPreferencesKey("chapter:$bookId")

        fun String.isMarkdown(): Boolean =
            listOf(".md", ".markdown", ".mdown", ".txt").any { endsWith(it, ignoreCase = true) }
    }
}
