package com.benfridge.lightmark

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.FileBrowserSpec
import kotlinx.coroutines.flow.MutableStateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {

    /**
     * The directory the Tool Manager writes documents into. It is the same path
     * [com.benfridge.lightmark.data.DocumentRepository] reads from, so the reader does
     * not care whether a file arrived over WiFi or through `adb push`.
     */
    private val documentsDirectory = FileBrowserSpec(
        label = "Documents",
        path = "documents",
    )

    /** Bumped whenever the Tool Manager reports new files, so the library can reload. */
    val libraryChanged = MutableStateFlow(0L)

    /**
     * HELD BACK. Returning null means "not a Tool Manager provider", which is the
     * interface default.
     *
     * The manifest below is written and works against the SDK emulator, but
     * `tool-manager-provider` is not reachable on LightOS 582 — declaring the capability
     * today ships a feature that silently does nothing on a phone. Flipping
     * [Features.TOOL_MANAGER_UPLOAD] and uncommenting the `capabilities` line in
     * `lighttool.toml` turns it on together; see docs/HOLDBACK.md.
     */
    override fun getToolManagerManifest(): ClientToolManifest? {
        if (!Features.TOOL_MANAGER_UPLOAD) return null
        return ClientToolManifest(
            title = "Lightmark",
            roots = listOf(ClientLeafNode(documentsDirectory)),
        )
    }

    override suspend fun onToolManagerDataUpdate() {
        super.onToolManagerDataUpdate()
        // Runs in a WorkManager worker after the user adds or removes files. Parsing is
        // cheap and happens on open, so there is nothing to pre-process here yet; the
        // library screen re-lists on show.
        libraryChanged.value = System.currentTimeMillis()
    }
}
