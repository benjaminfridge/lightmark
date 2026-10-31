package com.benfridge.lightmark.sync

import com.benfridge.lightmark.data.Databases
import com.benfridge.lightmark.data.Highlight
import com.benfridge.lightmark.export.formatHighlightsMarkdown
import com.benfridge.lightmark.export.sectionsFrom
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.SealedLightContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

@LightJob(HighlightSync.JOB_KEY)
val highlightPushJob: LightJobHandler = { context, _ -> HighlightPush.run(context) }

/**
 * Personal builds only: pushes every highlight to a private GitHub repo, where an Action
 * copies them on to Notion. Debug source set on purpose, so the build Light reviews makes
 * no network calls. When LightOS ships SDK backups, those replace this.
 *
 * The token is put on the phone over USB by scripts/set-highlight-sync-token.sh and never
 * appears in the source.
 */
object HighlightPush {
    private const val REPO = "benjaminfridge/lightmark-highlights"
    private const val TOKEN_PATH = ".sync/github-token"
    private const val LAST_PATH = ".sync/last-pushed"
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy")

    suspend fun run(context: SealedLightContext): LightJobResult {
        val share = context.fileShare
        val token = share.read(TOKEN_PATH) { it.readText().trim() }
            ?: return LightJobResult.Error("No sync token on this phone.")
        val highlights = Databases.highlights(context).listAll()

        val json = toJson(highlights)
        val digest = sha256(json)
        val last = share.read(LAST_PATH) { it.readText().trim() }
        if (digest == last || (last == null && highlights.isEmpty())) {
            return LightJobResult.Success(outputFilePath = null, message = "Nothing new.")
        }

        val markdown = formatHighlightsMarkdown(
            sectionsFrom(highlights),
            DATE_FORMAT.format(LocalDateTime.now()),
        )
        return try {
            // JSON last: pushing it is what starts the Notion sync.
            put(token, "lightmark-highlights.md", markdown)
            put(token, "highlights.json", json)
            share.write(LAST_PATH) { it.write(digest) }
            LightJobResult.Success(outputFilePath = null, message = "Highlights pushed.")
        } catch (e: IOException) {
            LightJobResult.Retry
        } catch (e: RejectedException) {
            LightJobResult.Error("GitHub refused the push: HTTP ${e.status}")
        }
    }

    private class RejectedException(val status: Int) : Exception()

    private fun toJson(highlights: List<Highlight>): String {
        val rows = JSONArray()
        highlights
            .sortedWith(compareBy({ it.documentId }, { it.start }, { it.end }))
            .forEach {
                rows.put(
                    JSONObject()
                        .put("key", "${it.documentId}#${it.start}-${it.end}")
                        .put("documentId", it.documentId)
                        .put("start", it.start)
                        .put("end", it.end)
                        .put("createdAt", it.createdAt)
                        .put("text", it.text)
                )
            }
        return JSONObject().put("highlights", rows).toString(1) + "\n"
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Creates or replaces one file. A 409 means the Action committed first, so retry. */
    private fun put(token: String, path: String, content: String) {
        val sha = request(token, "GET", path, null).let { (status, body) ->
            when (status) {
                200 -> JSONObject(body).getString("sha")
                404 -> null
                else -> throw failure(status)
            }
        }
        val payload = JSONObject()
            .put("message", "Update $path from Lightmark")
            .put("content", Base64.getEncoder().encodeToString(content.toByteArray()))
        if (sha != null) payload.put("sha", sha)
        val (status, _) = request(token, "PUT", path, payload.toString())
        if (status != 200 && status != 201) throw failure(status)
    }

    private fun failure(status: Int): Exception =
        if (status == 409 || status == 429 || status >= 500) {
            IOException("HTTP $status")
        } else {
            RejectedException(status)
        }

    private fun request(token: String, method: String, path: String, body: String?): Pair<Int, String> {
        val connection = URL("https://api.github.com/repos/$REPO/contents/$path")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            return status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }
}
