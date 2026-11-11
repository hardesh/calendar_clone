package app.calendar.ui.drag

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/** How a press turned out before any dragging happens. */
enum class PressOutcome {
    /** Released without moving. */
    Tap,

    /** Mouse moved past slop, or touch was held long enough: start dragging. */
    Drag,

    /** Touch moved sideways before the long-press fired: page the view. */
    Swipe,

    /** Touch moved vertically (the scroll container takes it) or the pointer vanished. */
    Cancel,
}

val PointerInputChange.isTouch: Boolean get() = type == PointerType.Touch || type == PointerType.Stylus

/** Pointer position when the press was classified (where a drag starts from). */
class PressResult(val outcome: PressOutcome, val position: Offset)

/**
 * Mouse: a drag starts once the pointer moves past [touchSlop].
 * Touch: a drag starts after [longPressMs] without moving, so plain finger
 * movement still scrolls and swipes.
 */
suspend fun AwaitPointerEventScope.classifyPress(
    down: PointerInputChange,
    touchSlop: Float,
    longPressMs: Long,
): PressResult {
    if (!down.isTouch) {
        while (true) {
            val ev = awaitPointerEvent()
            val c = ev.changes.firstOrNull { it.id == down.id } ?: return PressResult(PressOutcome.Cancel, down.position)
            if (c.changedToUpIgnoreConsumed()) return PressResult(PressOutcome.Tap, c.position)
            if ((c.position - down.position).getDistance() > touchSlop) {
                c.consume()
                return PressResult(PressOutcome.Drag, c.position)
            }
        }
    }
    val early = withTimeoutOrNull(longPressMs) {
        while (true) {
            val ev = awaitPointerEvent()
            val c = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull PressOutcome.Cancel
            if (c.changedToUpIgnoreConsumed()) return@withTimeoutOrNull PressOutcome.Tap
            if (ev.changes.size > 1) return@withTimeoutOrNull PressOutcome.Cancel
            val d = c.position - down.position
            if (d.getDistance() > touchSlop) {
                return@withTimeoutOrNull if (abs(d.x) > abs(d.y) * 1.2f) PressOutcome.Swipe else PressOutcome.Cancel
            }
        }
        @Suppress("UNREACHABLE_CODE")
        PressOutcome.Cancel
    }
    return PressResult(early ?: PressOutcome.Drag, down.position)
}

/**
 * Follows [pointerId] until it lifts. Returns true if released normally, false if
 * the drag was cancelled (pointer lost, second finger, or [isCancelled]).
 */
suspend fun AwaitPointerEventScope.trackDrag(
    pointerId: PointerId,
    isCancelled: () -> Boolean,
    onMove: (Offset) -> Unit,
): Boolean {
    while (true) {
        val ev = awaitPointerEvent()
        val c = ev.changes.firstOrNull { it.id == pointerId } ?: return false
        if (isCancelled()) {
            ev.changes.forEach { it.consume() }
            waitForRelease(pointerId)
            return false
        }
        if (c.changedToUpIgnoreConsumed()) {
            c.consume()
            return true
        }
        if (c.positionChange() != Offset.Zero) {
            c.consume()
            onMove(c.position)
        }
    }
}

/** Follows a horizontal swipe; returns the total horizontal distance at release. */
suspend fun AwaitPointerEventScope.trackSwipe(pointerId: PointerId, startX: Float, onMove: (Float) -> Unit): Float {
    var dx = 0f
    while (true) {
        val ev = awaitPointerEvent()
        val c = ev.changes.firstOrNull { it.id == pointerId } ?: return 0f
        dx = c.position.x - startX
        if (c.changedToUpIgnoreConsumed()) return dx
        c.consume()
        onMove(dx)
    }
}

private suspend fun AwaitPointerEventScope.waitForRelease(pointerId: PointerId) {
    while (true) {
        val ev = awaitPointerEvent()
        val c = ev.changes.firstOrNull { it.id == pointerId } ?: return
        c.consume()
        if (c.changedToUpIgnoreConsumed()) return
    }
}
