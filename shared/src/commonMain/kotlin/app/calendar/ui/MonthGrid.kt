package app.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.calendar.logic.dateSpan
import app.calendar.logic.formatShort
import app.calendar.logic.minuteOfDay
import app.calendar.logic.plusDays
import app.calendar.logic.shiftByDays
import app.calendar.logic.showsInAllDayRow
import app.calendar.model.CalEvent
import app.calendar.model.EventTiming
import app.calendar.model.ViewMode
import app.calendar.state.CalendarController
import app.calendar.ui.drag.PressOutcome
import app.calendar.ui.drag.classifyPress
import app.calendar.ui.drag.isTouch
import app.calendar.ui.drag.trackDrag
import app.calendar.ui.drag.trackSwipe
import app.calendar.ui.theme.AppType
import app.calendar.ui.theme.LocalAppColors
import app.calendar.ui.theme.eventPalette
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlin.math.abs
import kotlin.math.roundToInt

private sealed interface MonthHit {
    data class Chip(val cell: Int, val event: CalEvent) : MonthHit
    data class More(val cell: Int) : MonthHit
    data class DateLabel(val cell: Int) : MonthHit
    data class Empty(val cell: Int) : MonthHit
}

@Composable
fun MonthGridView(controller: CalendarController, compact: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val viewConfig = LocalViewConfiguration.current
    val days = controller.visibleDays()
    val zone = controller.primaryZone
    val today = controller.today()
    val month = controller.anchor.month
    val events = controller.eventsBetween(days.first(), days.last().plusDays(1))
    val draft = controller.editor?.takeIf { it.isNew }?.draft

    // Events per cell: all-day first, then by start time.
    val perCell: List<List<CalEvent>> = remember(events, draft, days, zone) {
        val cells = List(days.size) { ArrayList<CalEvent>() }
        for (e in events + listOfNotNull(draft)) {
            // Timed events under 24h appear on their start day only, as in Google/Notion.
            val (s, l) = dateSpan(e, zone).let { if (showsInAllDayRow(e)) it else it.first to it.first }
            val a = days.first().daysUntil(s).coerceAtLeast(0)
            val b = days.first().daysUntil(l).coerceAtMost(days.size - 1)
            for (i in a..b) cells[i] += e
        }
        cells.map { list -> list.sortedWith(compareByDescending<CalEvent> { it.isAllDay }.thenBy { it.startIn(zone) }) }
    }

    val headerHeight = 26.dp.scaled()
    val chipHeight = (if (compact) 16.dp else 20.dp).scaled()
    val chipGap = 2.dp
    var drag by remember { mutableStateOf<Triple<CalEvent, Offset, Int>?>(null) } // event, pointer, target cell
    var swipeOffset by remember { mutableFloatStateOf(0f) }

    Column(modifier.fillMaxSize().background(colors.background)) {
        Row(Modifier.fillMaxWidth().height(28.dp.scaled())) {
            for (i in 0 until 7) {
                val dow = days[i].dayOfWeek
                Text(
                    dow.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
                    style = AppType.small,
                    color = colors.textSecondary,
                    textAlign = if (compact) TextAlign.Center else TextAlign.End,
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically).padding(end = 10.dp),
                )
            }
        }
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .graphicsLayer { translationX = swipeOffset }
                .drawBehind {
                    val cw = size.width / 7
                    val ch = size.height / 6
                    for (r in 0..5) drawLine(colors.gridLine, Offset(0f, r * ch), Offset(size.width, r * ch), 1f)
                    for (c in 1..6) drawLine(colors.gridLine, Offset(c * cw, 0f), Offset(c * cw, size.height), 1f)
                },
        ) {
            val cellW = constraints.maxWidth / 7f
            val cellH = constraints.maxHeight / 6f
            val headerPx = with(density) { headerHeight.toPx() }
            val chipPx = with(density) { (chipHeight + chipGap).toPx() }
            val rows = ((cellH - headerPx - with(density) { 4.dp.toPx() }) / chipPx).toInt().coerceAtLeast(1)
            val state = rememberUpdatedState(perCell)
            val geom = rememberUpdatedState(Triple(cellW, cellH, rows))

            fun cellAt(p: Offset): Int {
                val (cw, ch, _) = geom.value
                val c = (p.x / cw).toInt().coerceIn(0, 6)
                val r = (p.y / ch).toInt().coerceIn(0, 5)
                return r * 7 + c
            }

            fun hitAt(p: Offset): MonthHit {
                val (_, ch, maxRows) = geom.value
                val cell = cellAt(p)
                val localY = p.y - (cell / 7) * ch
                if (localY < headerPx) return MonthHit.DateLabel(cell)
                val list = state.value[cell]
                val shown = if (list.size > maxRows) maxRows - 1 else list.size
                val idx = ((localY - headerPx) / chipPx).toInt()
                return when {
                    idx < shown -> MonthHit.Chip(cell, list[idx])
                    idx == shown && list.size > shown -> MonthHit.More(cell)
                    else -> MonthHit.Empty(cell)
                }
            }

            Box(
                Modifier.fillMaxSize().pointerInput(controller, days) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!down.isTouch && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                        val hit = hitAt(down.position)
                        val token = controller.dragCancelToken
                        val visible = days
                        val press = classifyPress(down, viewConfig.touchSlop, viewConfig.longPressTimeoutMillis)
                            when (press.outcome) {
                            PressOutcome.Tap -> when (hit) {
                                is MonthHit.Chip -> if (hit.event.id != CalendarController.DRAFT_ID) controller.openEditor(hit.event)
                                is MonthHit.More -> controller.goTo(visible[hit.cell], ViewMode.DAY)
                                is MonthHit.DateLabel -> controller.goTo(visible[hit.cell], ViewMode.DAY)
                                is MonthHit.Empty -> if (down.isTouch) {
                                    controller.goTo(visible[hit.cell], ViewMode.DAY)
                                } else {
                                    val d = visible[hit.cell]
                                    controller.startNewEvent(EventTiming.AllDay(d, d.plusDays(1)))
                                }
                            }
                            PressOutcome.Drag -> {
                                val chip = hit as? MonthHit.Chip ?: return@awaitEachGesture
                                if (!controller.canEdit(chip.event)) return@awaitEachGesture
                                if (down.isTouch) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                drag = Triple(chip.event, press.position, cellAt(press.position))
                                val ok = trackDrag(down.id, { controller.dragCancelToken != token }) {
                                    drag = Triple(chip.event, it, cellAt(it))
                                }
                                val target = drag?.third
                                drag = null
                                if (ok && target != null && target != chip.cell) {
                                    controller.changeTiming(chip.event, shiftByDays(chip.event.timing, target - chip.cell, zone))
                                }
                            }
                            PressOutcome.Swipe -> {
                                val dx = trackSwipe(down.id, down.position.x) { swipeOffset = it * 0.5f }
                                swipeOffset = 0f
                                if (abs(dx) > 72 * density.density) controller.page(if (dx < 0) 1 else -1)
                            }
                            PressOutcome.Cancel -> Unit
                        }
                    }
                },
            ) {
                for ((i, day) in days.withIndex()) {
                    val r = i / 7
                    val c = i % 7
                    MonthCell(
                        controller = controller,
                        day = day,
                        events = perCell[i],
                        inMonth = day.month == month,
                        isToday = day == today,
                        rows = rows,
                        compact = compact,
                        chipHeight = chipHeight,
                        chipGap = chipGap,
                        highlighted = drag?.third == i,
                        draggingKey = drag?.first?.key,
                        modifier = Modifier
                            .offset { IntOffset((c * cellW).roundToInt(), (r * cellH).roundToInt()) }
                            .size(with(density) { cellW.toDp() }, with(density) { cellH.toDp() }),
                    )
                }
                drag?.let { (event, pos, _) ->
                    val palette = eventPalette(controller.colorOf(event), colors)
                    Box(
                        Modifier
                            .offset { IntOffset((pos.x - cellW / 2).roundToInt(), (pos.y - with(density) { 10.dp.toPx() }).roundToInt()) }
                            .size(with(density) { (cellW - 8f).toDp() }, chipHeight)
                            .graphicsLayer { shadowElevation = 10f; shape = RoundedCornerShape(4.dp); clip = true }
                            .background(palette.fill),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(palette.bar))
                        Text(event.title, style = AppType.eventTitle, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 7.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCell(
    controller: CalendarController,
    day: LocalDate,
    events: List<CalEvent>,
    inMonth: Boolean,
    isToday: Boolean,
    rows: Int,
    compact: Boolean,
    chipHeight: androidx.compose.ui.unit.Dp,
    chipGap: androidx.compose.ui.unit.Dp,
    highlighted: Boolean,
    draggingKey: String?,
    modifier: Modifier,
) {
    val colors = LocalAppColors.current
    val zone = controller.primaryZone
    val now = controller.now()
    Column(
        modifier
            .background(
                when {
                    highlighted -> colors.selected
                    !inMonth -> colors.weekendShade
                    day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY -> colors.weekendShade
                    else -> Color.Transparent
                },
            )
            .padding(horizontal = 3.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(26.dp.scaled()), contentAlignment = if (compact) Alignment.Center else Alignment.CenterEnd) {
            Box(
                Modifier.height(22.dp.scaled()).widthIn(min = 22.dp).clip(CircleShape).background(if (isToday) colors.today else Color.Transparent)
                    .padding(horizontal = if (isToday) 0.dp else 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (day.day == 1 && !compact) "${day.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} 1" else day.day.toString(),
                    style = AppType.small.copy(fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal),
                    color = when {
                        isToday -> Color.White
                        inMonth -> colors.text
                        else -> colors.textTertiary
                    },
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        val shown = if (events.size > rows) rows - 1 else events.size
        for (e in events.take(shown)) {
            val isDraft = e.id == CalendarController.DRAFT_ID
            val color = if (isDraft) controller.defaultCalendar()?.color ?: 0xFF2F80EDL else controller.colorOf(e)
            val past = !isDraft && e.endIn(zone) < now
            val palette = eventPalette(color, colors, faded = past || e.key == draggingKey)
            val multiDay = showsInAllDayRow(e)
            Box(Modifier.padding(bottom = chipGap).fillMaxWidth().height(chipHeight)) {
                if (e.isAllDay || multiDay) {
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)).background(palette.fill),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            e.title.ifBlank { "New event" },
                            style = if (compact) AppType.tiny else AppType.eventTitle,
                            color = palette.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 5.dp),
                        )
                    }
                } else {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(start = 2.dp, end = 4.dp).size(if (compact) 5.dp else 6.dp).clip(CircleShape).background(palette.bar))
                        if (!compact) {
                            Text(
                                formatShort(minuteOfDay(e.startIn(zone), zone)),
                                style = AppType.small,
                                color = colors.textSecondary.copy(alpha = if (past) 0.6f else 1f),
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                        Text(
                            e.title.ifBlank { "New event" },
                            style = if (compact) AppType.tiny else AppType.eventTitle,
                            color = if (past) colors.textTertiary else colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (events.size > shown) {
            Text(
                "${events.size - shown} more",
                style = AppType.small,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 4.dp).height(chipHeight),
                maxLines = 1,
            )
        }
    }
}
