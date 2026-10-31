package com.benfridge.lightmark.sync

import com.thelightphone.sdk.SealedLightContext

/**
 * Release builds never send highlights anywhere. The build Light signs and reviews stays
 * local-first, with the export file as its only way out. See the debug source set.
 */
object HighlightSync {
    fun schedule(context: SealedLightContext) = Unit
    fun changed(context: SealedLightContext) = Unit
}
