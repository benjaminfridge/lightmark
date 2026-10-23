package com.benfridge.lightmark.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
 * "shared:02-the-wild-places.md" -> "02 the wild places".
 *
 * Derived from the id rather than looked up, so an export still names a document whose
 * file has since been renamed or removed.
 */
internal fun titleFromDocumentId(documentId: String): String =
    titleFromFileName(documentId.substringAfter(':', documentId))

/**
 * [id] is stable across reparses and reboots, and is what highlights are keyed on.
 * Changing how it is derived orphans every existing highlight.
 */
data class DocumentRef(
    val id: String,
    val title: String,
    val source: DocumentSource,
    val path: String,
)

/**
 * Documents come from two places: a few bundled in the APK, and whatever the user has
 * put in `files/shared/documents`.
 *
 * Today that second directory is filled by `adb push`. When `tool-manager-provider`
 * works on a shipping LightOS, the Tool Manager writes into the same directory and
 * nothing here changes — see [com.benfridge.lightmark.Features.TOOL_MANAGER_UPLOAD].
 */
class DocumentRepository(
    private val lightContext: SealedLightContext,
    private val dataStore: DataStore<Preferences>,
) {
    private val fileShare: LightFileShare get() = lightContext.fileShare

    fun list(): List<DocumentRef> = (bundled() + shared()).sortedBy { it.title.lowercase() }

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
                    title = titleOf(name),
                    source = DocumentSource.Bundled,
                    path = "$ASSET_DIR/$name",
                )
            }
            .toList()
    }

    private fun shared(): List<DocumentRef> =
        runCatching { fileShare.list(SHARED_DIR) }.getOrDefault(emptyList())
            .filter { it.isFile && it.name.isMarkdown() }
            .map { file ->
                DocumentRef(
                    id = "shared:${file.name}",
                    title = titleOf(file.name),
                    source = DocumentSource.Shared,
                    path = "$SHARED_DIR/${file.name}",
                )
            }

    /** Returns null when the file has gone away since it was listed. */
    fun read(ref: DocumentRef): String? = when (ref.source) {
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
    suspend fun saveOffset(documentId: String, characterOffset: Int) {
        dataStore.edit { it[offsetKey(documentId)] = characterOffset }
    }

    suspend fun offset(documentId: String): Int =
        dataStore.data.map { it[offsetKey(documentId)] ?: 0 }.first()

    /** Makes the shared directory exist, so the Tool Manager has somewhere to write. */
    fun ensureSharedDirectory() {
        if (!fileShare.exists(SHARED_DIR)) {
            runCatching { fileShare.write("$SHARED_DIR/.keep") { it.write("") } }
        }
    }

    private companion object {
        const val ASSET_DIR = "documents"
        const val SHARED_DIR = "documents"
        const val INDEX_FILE = "index.txt"

        fun offsetKey(documentId: String) = intPreferencesKey("offset:$documentId")

        fun String.isMarkdown(): Boolean =
            listOf(".md", ".markdown", ".mdown", ".txt").any { endsWith(it, ignoreCase = true) }

        fun titleOf(fileName: String): String = titleFromFileName(fileName)
    }
}
