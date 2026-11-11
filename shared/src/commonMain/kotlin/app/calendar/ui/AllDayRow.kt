package app.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.calendar.logic.dateSpan
import app.calendar.logic.layoutLanes
import app.calendar.logic.plusDays
import app.calendar.logic.shiftByDays
import app.calendar.model.CalEvent
import app.calendar.model.EventTiming
import app.calendar.state.CalendarController
import app.calendar.ui.drag.PressOutcome
import app.calendar.ui.drag.classifyPress
import app.calendar.ui.drag.isTouch
import app.calendar.ui.drag.trackDrag
import app.calendar.ui.theme.AppType
import app.calendar.ui.theme.LocalAppColors
import app.calendar.ui.theme.eventPalette
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlin.math.roundToInt

private class AllDayItem(val event: CalEvent, val first: Int, val last: Int, val lane: Int, val clippedStart: Boolean, val clippedEnd: Boolean)

/** All-day and multi-day events above the time grid. Drag sideways to move them between days. */
@Composable
internal fun AllDayRow(
    controller: CalendarController,
    days: List<LocalDate>,
    events: List<CalEvent>,
    gutterWidth: Dp,
    gutterColWidth: Dp,
    zoneLabels: List<String>,
    compact: Boolean,
) {
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val viewConfig = LocalViewConfiguration.current
    val zone = controller.primaryZone
    val origin = days.first()

    val items = remember(events, days, zone) {
        val spans = events.mapNotNull { e ->
            val (s, l) = dateSpan(e, zone)
            val a = origin.daysUntil(s)
            val b = origin.daysUntil(l)
            if (b < 0 || a > days.size - 1) null else Triple(e, a, b)
        }
        val lanes = layoutLanes(spans.map { (_, a, b) -> a.coerceAtLeast(0)..b.coerceAtMost(days.size - 1) })
        spans.mapIndexed { i, (e, a, b) ->
            AllDayItem(e, a.coerceAtLeast(0), b.coerceAtMost(days.size - 1), lanes[i], a < 0, b > days.size - 1)
        }
    }
    val laneCount = (items.maxOfOrNull { it.lane } ?: -1) + 1
    val laneHeight = 22.dp
    val rowHeight = maxOf(28.dp, laneHeight * laneCount + 6.dp)
    var dragDays by remember { mutableStateOf<Pair<String, Int>?>(null) }

    Row(Modifier.fillMaxWidth().height(rowHeight)) {
        // Zone labels sit at the top of each gutter column, secondary zones on the left.
        Row(Modifier.width(gutterWidth).fillMaxHeight()) {
            for (label in zoneLabels.reversed()) {
                Text(
                    label,
                    style = AppType.tiny,
                    color = colors.textTertiary,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(gutterColWidth).align(Alignment.Bottom).padding(end = 6.dp, bottom = 4.dp),
                )
            }
        }
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .drawBehind {
                    val dayW = size.width / days.size
                    for (i in days.indices) drawLine(colors.gridLine, Offset(i * dayW, 0f), Offset(i * dayW, size.height), 1f)
                },
        ) {
            val dayWidth = constraints.maxWidth.toFloat() / days.size
            val lanePx = with(density) { laneHeight.toPx() }
            val topPad = with(density) { 3.dp.toPx() }
            fun rectOf(it: AllDayItem) = Rect(
                it.first * dayWidth + 2f,
                topPad + it.lane * lanePx,
                (it.last + 1) * dayWidth - 4f,
                topPad + it.lane * lanePx + lanePx - 2f,
            )
            val hits = rememberUpdatedState(items.map { it to rectOf(it) })
            val dayW = rememberUpdatedState(dayWidth)

            Box(
                Modifier.fillMaxSize().pointerInput(controller, days) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!down.isTouch && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                        val hit = hits.value.lastOrNull { it.second.contains(down.position) }?.first
                        val token = controller.dragCancelToken
                        val press = classifyPress(down, viewConfig.touchSlop, viewConfig.longPressTimeoutMillis)
                            when (press.outcome) {
                            PressOutcome.Tap -> {
                                if (hit != null) {
                                    if (hit.event.id != CalendarController.DRAFT_ID) controller.openEditor(hit.event)
                                } else {
                                    val d = days[(down.position.x / dayW.value).toInt().coerceIn(0, days.size - 1)]
                                    controller.startNewEvent(EventTiming.AllDay(d, d.plusDays(1)))
                                }
                            }
                            PressOutcome.Drag -> {
                                if (hit == null || !controller.canEdit(hit.event)) return@awaitEachGesture
                                if (down.isTouch) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                val startX = down.position.x
                                dragDays = hit.event.key to ((press.position.x - startX) / dayW.value).roundToInt()
                                val ok = trackDrag(down.id, { controller.dragCancelToken != token }) {
                                    dragDays = hit.event.key to ((it.x - startX) / dayW.value).roundToInt()
                                }
                                val delta = dragDays?.second ?: 0
                                dragDays = null
                                if (ok && delta != 0) controller.changeTiming(hit.event, shiftByDays(hit.event.timing, delta, zone))
                            }
                            else -> Unit
                        }
                    }
                },
            ) {
                for (item in items) {
                    val r = rectOf(item)
                    val shift = dragDays?.takeIf { it.first == item.event.key }?.second ?: 0
                    val isDraft = item.event.id == CalendarController.DRAFT_ID
                    val color = if (isDraft) controller.defaultCalendar()?.color ?: 0xFF2F80EDL else controller.colorOf(item.event)
                    val palette = eventPalette(color, colors)
                    Box(
                        Modifier
                            .offset { IntOffset((r.left + shift * dayWidth).roundToInt(), r.top.roundToInt()) }
                            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                            .graphicsLayer { shadowElevation = if (shift != 0) 6f else 0f }
                            .clip(RoundedCornerShape(4.dp))
                            .background(palette.fill)
                            .then(if (isDraft) Modifier.border(1.5.dp, palette.bar, RoundedCornerShape(4.dp)) else Modifier),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(palette.bar))
                        Text(
                            (if (item.clippedStart) "← " else "") + item.event.title.ifBlank { "New event" },
                            style = AppType.eventTitle,
                            color = palette.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
