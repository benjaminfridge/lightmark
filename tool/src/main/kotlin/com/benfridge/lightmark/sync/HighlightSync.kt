package com.benfridge.lightmark.sync

import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlin.time.Duration.Companion.hours

/**
 * Asks for a job that only debug builds contain.
 *
 * [LightWork] returns false for a key with no registered handler, so in a release build
 * these calls do nothing: the job lives in `tool/src/debug`, which Light's builder does
 * not copy and `assembleRelease` would not compile. What Light signs makes no network
 * calls.
 */
object HighlightSync {
    const val JOB_KEY = "highlight-sync"

    /** A safety net for a push that failed while the phone was offline. */
    fun schedule(context: SealedLightContext) {
        LightWork.enqueuePeriodic(context, JOB_KEY, 6.hours, tag = "$JOB_KEY-periodic")
    }

    fun changed(context: SealedLightContext) {
        LightWork.enqueue(context, JOB_KEY)
    }
}
