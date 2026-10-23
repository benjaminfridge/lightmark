package com.benfridge.lightmark.export

import com.benfridge.lightmark.data.HighlightDao
import com.thelightphone.sdk.LightFileShare
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What an export attempt produced. */
sealed interface ExportOutcome {
    data class Written(
        val relativePath: String,
        val quoteCount: Int,
        val documentCount: Int,
    ) : ExportOutcome

    data object NothingToExport : ExportOutcome

    data class Failed(val reason: String) : ExportOutcome
}

/**
 * Writes every highlight out as one Markdown file.
 *
 * The file lands in the tool's shared directory, under a subdirectory named for the job
 * key the Tool Manager will eventually use. That is the whole design: today the file is
 * fetched with `adb pull`, and when `tool-manager-provider` works on a shipping LightOS
 * a `JobSpec(.., JOB_KEY, ..)` plus a handler returning
 * `LightJobResult.Success(outputFilePath = FILE_NAME)` turns the identical file into a
 * browser download. No part of this class changes for that — see docs/HOLDBACK.md.
 *
 * Nothing here reads a source document. The quoted text comes from the highlight row
 * itself, so an export is correct even if a chapter file has since been edited by a
 * converter, renamed, or removed.
 */
class HighlightExporter(
    private val fileShare: LightFileShare,
    private val dao: HighlightDao,
) {

    suspend fun export(now: LocalDateTime = LocalDateTime.now()): ExportOutcome {
        val highlights = runCatching { dao.listAll() }.getOrElse {
            return ExportOutcome.Failed("Could not read highlights.")
        }
        if (highlights.isEmpty()) return ExportOutcome.NothingToExport

        val sections = sectionsFrom(highlights)
        val markdown = formatHighlightsMarkdown(sections, DATE_FORMAT.format(now))

        return runCatching {
            fileShare.write(RELATIVE_PATH) { it.write(markdown) }
            ExportOutcome.Written(
                relativePath = RELATIVE_PATH,
                quoteCount = sections.sumOf { it.quotes.size },
                documentCount = sections.size,
            )
        }.getOrElse {
            ExportOutcome.Failed("Could not write the export file.")
        }
    }

    suspend fun highlightCount(): Int = runCatching { dao.totalCount() }.getOrDefault(0)

    companion object {
        /**
         * Doubles as the Tool Manager job key and the subdirectory name, which is the
         * convention the SDK's own example follows.
         */
        const val JOB_KEY = "highlights"
        const val FILE_NAME = "lightmark-highlights.md"
        const val RELATIVE_PATH = "$JOB_KEY/$FILE_NAME"

        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy")
    }
}
