package com.benfridge.lightmark.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** What a touch turned out to mean. */
internal enum class ReaderIntent { Select, Swipe, Tap }

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

        val intent = withTimeoutOrNull(longPressMs) {
            var outcome = ReaderIntent.Tap
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    outcome = ReaderIntent.Tap
                    break
                }
                if (!change.pressed) {
                    outcome = ReaderIntent.Tap
                    break
                }
                if ((change.position - down.position).getDistance() > slop) {
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
                var travel = Offset.Zero
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    travel = change.position - down.position
                    change.consume()
                    if (!change.pressed) break
                }
                if (abs(travel.x) >= swipeThreshold && abs(travel.x) > abs(travel.y)) {
                    // Content follows the finger: dragging left advances.
                    if (travel.x < 0f) onPageForward() else onPageBack()
                }
            }
        }
    }
}
