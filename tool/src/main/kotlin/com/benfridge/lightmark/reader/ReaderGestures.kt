package com.benfridge.lightmark.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/** What a touch turned out to mean. */
internal enum class ReaderIntent { Select, Swipe, Tap }

/**
 * Which way a swipe went: 1 forward, -1 back, 0 not a swipe.
 *
 * Separated out so the thresholds can be tested; the gesture plumbing around it cannot
 * be, because the test runner this platform allows has no Compose pointer input.
 * Content follows the finger, so dragging left advances.
 */
internal fun swipeDirection(dx: Float, dy: Float, threshold: Float): Int = when {
    kotlin.math.abs(dx) < threshold -> 0
    kotlin.math.abs(dx) <= kotlin.math.abs(dy) -> 0
    dx < 0f -> 1
    else -> -1
}

/**
 * Whether a drag has become a scrub along the progress bar.
 *
 * Only a drag that began in the bar's touch zone, mostly sideways, and still going after
 * [SCRUB_DELAY_MS]. A flick near the bottom of the page stays a page turn, because a scrub
 * jumps to wherever the finger is and an accidental one would lose your place.
 */
internal fun startsScrub(startedInZone: Boolean, dx: Float, dy: Float, elapsedMs: Long): Boolean =
    startedInZone && elapsedMs >= SCRUB_DELAY_MS && kotlin.math.abs(dx) > kotlin.math.abs(dy)

/**
 * The page a scrub at [x] lands on, each page owning an equal slice of the track.
 *
 * The track stops [edgeInset] short of each side, so the first and last pages can be
 * reached without a fingertip having to find the bezel.
 */
internal fun scrubPageAt(x: Float, width: Float, edgeInset: Float, pageCount: Int): Int {
    if (pageCount <= 0) return 0
    val track = width - edgeInset * 2f
    if (track <= 0f) return 0
    val page = ((x - edgeInset) / track * pageCount).toInt()
    return if (x <= edgeInset) 0 else page.coerceAtMost(pageCount - 1)
}

/** Long enough to tell a deliberate drag from the flick a page turn is. */
internal const val SCRUB_DELAY_MS = 180L

/**
 * The reader's whole input surface: swipe to turn the page, press and hold then drag to
 * highlight, drag along the bottom edge to scrub. Nothing else.
 *
 * Both gestures start with a finger down in the same place, so they are arbitrated
 * explicitly rather than by stacking two detectors: the long-press timer is raced
 * against movement and release. Move first and it is a swipe; stay still past the
 * timeout and it is a selection. Two independent detectors would both claim a
 * press-then-drag and turn the page mid-highlight.
 *
 * A plain tap deliberately does nothing.
 *
 * [onSelectStart] returns false when the press landed on an existing highlight and
 * removed it; the rest of that gesture is then swallowed, so a removal cannot slide
 * into drawing a new highlight.
 *
 * A scrub is decided inside the swipe, so all three still race from one finger down.
 * [scrubZoneTop] is where the bar's touch zone starts. [key] must not change during a
 * scrub: this restarts on a new key, and that would cancel the drag.
 */
internal fun Modifier.readerGestures(
    key: Any?,
    swipeThreshold: Float,
    onPageForward: () -> Unit,
    onPageBack: () -> Unit,
    onSelectStart: (Offset) -> Boolean,
    onSelectExtend: (Offset) -> Unit,
    onSelectCommit: () -> Unit,
    onSelectCancel: () -> Unit,
    scrubZoneTop: Float,
    onScrubStart: () -> Unit,
    onScrubTo: (x: Float) -> Unit,
    onScrubEnd: () -> Unit,
): Modifier = pointerInput(key) {
    val slop = viewConfiguration.touchSlop
    val longPressMs = viewConfiguration.longPressTimeoutMillis

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)

        // Carried across both loops. The movement that crosses the slop is what makes
        // this a swipe, so losing it would mean a flick quick enough to cross and lift
        // before the next event turned no page at all.
        var travel = Offset.Zero

        val intent = withTimeoutOrNull(longPressMs) {
            var outcome = ReaderIntent.Tap
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    outcome = ReaderIntent.Tap
                    break
                }
                travel = change.position - down.position
                if (!change.pressed) {
                    outcome = ReaderIntent.Tap
                    break
                }
                if (travel.getDistance() > slop) {
                    outcome = ReaderIntent.Swipe
                    break
                }
            }
            outcome
        } ?: ReaderIntent.Select

        when (intent) {
            ReaderIntent.Tap -> Unit

            ReaderIntent.Select -> {
                val tracking = onSelectStart(down.position)
                var cancelled = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        cancelled = true
                        break
                    }
                    change.consume()
                    if (!change.pressed) break
                    if (tracking) onSelectExtend(change.position)
                }
                if (tracking) {
                    if (cancelled) onSelectCancel() else onSelectCommit()
                }
            }

            ReaderIntent.Swipe -> {
                val inZone = down.position.y >= scrubZoneTop
                var scrubbing = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        travel = change.position - down.position
                        change.consume()
                        if (!change.pressed) break
                        val elapsed = change.uptimeMillis - down.uptimeMillis
                        if (!scrubbing && startsScrub(inZone, travel.x, travel.y, elapsed)) {
                            scrubbing = true
                            onScrubStart()
                        }
                        if (scrubbing) onScrubTo(change.position.x)
                    }
                } finally {
                    // Also when this is cancelled, or the reader stays stuck mid-scrub
                    // and stops saving your place.
                    if (scrubbing) onScrubEnd()
                }
                if (!scrubbing) {
                    when (swipeDirection(travel.x, travel.y, swipeThreshold)) {
                        1 -> onPageForward()
                        -1 -> onPageBack()
                    }
                }
            }
        }
    }
}
