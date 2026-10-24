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
 * The reader's whole input surface: swipe to turn the page, press and hold then drag to
 * highlight. Nothing else.
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
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    travel = change.position - down.position
                    change.consume()
                    if (!change.pressed) break
                }
                when (swipeDirection(travel.x, travel.y, swipeThreshold)) {
                    1 -> onPageForward()
                    -1 -> onPageBack()
                }
            }
        }
    }
}
