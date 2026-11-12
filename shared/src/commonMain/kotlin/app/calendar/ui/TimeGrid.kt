package app.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.calendar.logic.DaySegment
import app.calendar.logic.MINUTES_PER_DAY
import app.calendar.logic.absMinuteOf
import app.calendar.logic.displayLabel
import app.calendar.logic.formatTime
import app.calendar.logic.formatTimeRange
import app.calendar.logic.hourLabels
import app.calendar.logic.instantAt
import app.calendar.logic.layoutColumns
import app.calendar.logic.minuteOfDay
import app.calendar.logic.plusDays
import app.calendar.logic.segmentsFor
import app.calendar.logic.segmentsForRange
import app.calendar.logic.showsInAllDayRow
import app.calendar.logic.snap
import app.calendar.logic.snapDown
import app.calendar.logic.zoneOrNull
import app.calendar.model.CalEvent
import app.calendar.model.EventTiming
import app.calendar.model.ViewMode
import app.calendar.platform.ResizeVerticalPointerIcon
import app.calendar.state.CalendarController
import app.calendar.ui.drag.PressOutcome
import app.calendar.ui.drag.classifyPress
import app.calendar.ui.drag.isTouch
import app.calendar.ui.drag.trackDrag
import app.calendar.ui.drag.trackSwipe
import app.calendar.ui.theme.AppType
import app.calendar.ui.theme.LocalAppColors
import app.calendar.ui.theme.eventPalette
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class DragKind { Move, ResizeTop, ResizeBottom, Create }

/** What is drawn while a drag is in progress. Minutes are absolute from midnight of the first visible day. */
private data class GridPreview(
    val eventKey: String?,
    val title: String,
    val color: Long,
    val startAbs: Int,
    val endAbs: Int,
)

private data class PlacedItem(
    val event: CalEvent,
    val segment: DaySegment,
    val column: Int,
    val columns: Int,
    val editable: Boolean,
    val isDraft: Boolean,
)

private class HitItem(val item: PlacedItem, val rect: Rect)

/** 1 day and 3 day views. */
@Composable
fun TimeGridView(controller: CalendarController, compact: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val days = controller.visibleDays()
    val zone = controller.primaryZone
    val events = controller.eventsBetween(days.first(), days.last().plusDays(1))
    val draft = controller.editor?.takeIf { it.isNew }?.draft
    val all = if (draft != null) events + draft else events
    val (allDayEvents, timedEvents) = all.partition { showsInAllDayRow(it) }
    // Larger text gets taller hours so event blocks keep room for their text; zoom applies on top.
    val textGrow = controller.textScale.coerceAtLeast(1f)
    val hourHeight = (controller.hourHeightFor(compact) * textGrow).dp
    val zonesShown = if (compact) controller.zones.take(2) else controller.zones
    val gutterColWidth = (if (compact) 42.dp else 52.dp) * textGrow
    val gutterWidth = gutterColWidth * zonesShown.size
    var swipeOffset by remember { mutableFloatStateOf(0f) }

    Column(modifier.fillMaxSize().background(colors.background)) {
        DayHeaderRow(controller, days, gutterWidth, gutterColWidth)
        AllDayRow(controller, days, allDayEvents, gutterWidth, gutterColWidth, zonesShown.map { it.displayLabel(controller.now()) }, compact)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.gridLineStrong))
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        // Scroll position to apply once the grid has been laid out at a new zoom level.
        var pendingScroll by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(hourHeight) {
            val target = pendingScroll ?: return@LaunchedEffect
            withFrameNanos { }
            scroll.scrollTo(target.coerceAtLeast(0))
            pendingScroll = null
        }
        // Zooms by [factor] keeping the grid point at [focusY] (relative to the viewport) in place.
        fun zoomAround(factor: Float, focusY: Float) {
            val oldPx = with(density) { hourHeight.toPx() }
            controller.zoomBy(factor, compact)
            val newPx = with(density) { (controller.hourHeightFor(compact) * textGrow).dp.toPx() }
            val currentScroll = pendingScroll ?: scroll.value
            pendingScroll = ((currentScroll + focusY) * (newPx / oldPx) - focusY).roundToInt()
        }
        val zoomState = rememberUpdatedState(::zoomAround)
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("time-grid")
                .pointerInput(compact) {
                    awaitPointerEventScope {
                        while (true) {
                            // Initial pass: see pinches and ⌘/Ctrl+wheel before scrolling and dragging do.
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val mods = ev.keyboardModifiers
                            if (ev.type == PointerEventType.Scroll && (mods.isCtrlPressed || mods.isMetaPressed)) {
                                val change = ev.changes.first()
                                val dy = change.scrollDelta.y
                                if (dy != 0f) zoomState.value(if (dy < 0) 1.1f else 1 / 1.1f, change.position.y)
                                ev.changes.forEach { it.consume() }
                                continue
                            }
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                val a = pressed[0]
                                val b = pressed[1]
                                val now = (a.position - b.position).getDistance()
                                val before = (a.previousPosition - b.previousPosition).getDistance()
                                if (before > 8f && now > 8f && now != before) {
                                    zoomState.value(now / before, (a.position.y + b.position.y) / 2f)
                                }
                                ev.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        ) {
            val viewportHeight = constraints.maxHeight.toFloat()
            LaunchedEffect(Unit) {
                // Open scrolled so the current time sits about a third of the way down.
                val nowMin = minuteOfDay(controller.now(), zone)
                val target = with(density) { (hourHeight.toPx() * nowMin / 60f) } - viewportHeight / 3f
                scroll.scrollTo(target.roundToInt().coerceAtLeast(0))
            }
            Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
                TimeGutter(controller, days.first(), zone, zonesShown.map { it.id }, gutterColWidth, hourHeight)
                DaysArea(
                    controller = controller,
                    days = days,
                    zone = zone,
                    timedEvents = timedEvents,
                    hourHeight = hourHeight,
                    compact = compact,
                    scrollValue = { scroll.value.toFloat() },
                    scrollBy = { scroll.scrollBy(it) },
                    viewportHeight = viewportHeight,
                    swipeOffset = swipeOffset,
                    onSwipe = { swipeOffset = it },
                    modifier = Modifier.weight(1f).height(hourHeight * 24),
                )
            }
        }
    }
}

@Composable
private fun DayHeaderRow(controller: CalendarController, days: List<LocalDate>, gutterWidth: Dp, gutterColWidth: Dp) {
    val colors = LocalAppColors.current
    val today = controller.today()
    Row(Modifier.fillMaxWidth().height(52.dp.scaled())) {
        Box(Modifier.width(gutterWidth).fillMaxHeight())
        for (d in days) {
            val isToday = d == today
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(enabled = controller.viewMode != ViewMode.DAY) { controller.goTo(d, ViewMode.DAY) }
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (days.size == 1) Arrangement.Start else Arrangement.Center,
            ) {
                Text(
                    d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
                    style = AppType.body,
                    color = if (isToday) colors.today else colors.textSecondary,
                )
                Box(
                    Modifier
                        .padding(start = 6.dp)
                        .size(26.dp.scaled())
                        .clip(CircleShape)
                        .background(if (isToday) colors.today else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        d.day.toString(),
                        style = AppType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                        color = if (isToday) Color.White else colors.text,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeGutter(
    controller: CalendarController,
    firstDay: LocalDate,
    primary: TimeZone,
    zoneIds: List<String>,
    columnWidth: Dp,
    hourHeight: Dp,
) {
    val colors = LocalAppColors.current
    val nowMinute = minuteOfDay(controller.now(), primary)
    Row(Modifier.height(hourHeight * 24)) {
        // Secondary zones first (left), primary zone closest to the grid, as Notion does.
        for ((index, id) in zoneIds.withIndex().reversed()) {
            val zone = zoneOrNull(id) ?: continue
            val labels = remember(firstDay, primary, id) { hourLabels(firstDay, primary, zone) }
            Box(Modifier.width(columnWidth).fillMaxHeight()) {
                for (h in 1 until 24) {
                    val text = labels[h]
                    Text(
                        text,
                        style = AppType.tiny,
                        color = if (index == 0) colors.textSecondary else colors.textTertiary,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier
                            .offset(y = hourHeight * h - 7.dp)
                            .width(columnWidth)
                            .padding(end = 6.dp),
                    )
                }
                if (index == 0 && controller.today() in (0 until 3).map { firstDay.plusDays(it) }) {
                    Text(
                        formatTime(nowMinute, compact = true).let { if (nowMinute % 60 == 0) it else formatTime(nowMinute).substringBefore(' ') },
                        style = AppType.tiny.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        softWrap = false,
                        color = colors.today,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .offset(y = hourHeight * (nowMinute / 60f) - 7.dp)
                            .width(columnWidth)
                            .background(colors.background)
                            .padding(end = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DaysArea(
    controller: CalendarController,
    days: List<LocalDate>,
    zone: TimeZone,
    timedEvents: List<CalEvent>,
    hourHeight: Dp,
    compact: Boolean,
    scrollValue: () -> Float,
    scrollBy: suspend (Float) -> Float,
    viewportHeight: Float,
    swipeOffset: Float,
    onSwipe: (Float) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val viewConfig = LocalViewConfiguration.current
    val today = controller.today()
    var preview by remember { mutableStateOf<GridPreview?>(null) }
    var now by remember { mutableStateOf(controller.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = controller.now()
        }
    }

    // Lay out every timed event per visible day.
    val placed: List<PlacedItem> = remember(timedEvents, days, zone, controller.calendars) {
        val perDay = HashMap<Int, MutableList<Pair<CalEvent, DaySegment>>>()
        for (e in timedEvents) {
            for (seg in segmentsFor(e, days, zone)) perDay.getOrPut(seg.dayIndex) { ArrayList() } += e to seg
        }
        perDay.values.flatMap { list ->
            val placements = layoutColumns(list.map { (_, s) -> s.startMinute..maxOf(s.endMinute, s.startMinute + 15) })
            list.mapIndexed { i, (e, s) ->
                PlacedItem(e, s, placements[i].column, placements[i].columnCount, controller.canEdit(e), e.id == CalendarController.DRAFT_ID)
            }
        }
    }

    BoxWithConstraints(
        modifier
            .graphicsLayer { translationX = swipeOffset }
            .drawBehind {
                val hourPx = hourHeight.toPx()
                val dayW = size.width / days.size
                for (i in days.indices) {
                    if (days[i].dayOfWeek.ordinal >= 5) {
                        drawRect(colors.weekendShade, topLeft = Offset(i * dayW, 0f), size = androidx.compose.ui.geometry.Size(dayW, size.height))
                    }
                }
                for (h in 1 until 24) {
                    drawLine(colors.gridLine, Offset(0f, h * hourPx), Offset(size.width, h * hourPx), strokeWidth = 1f)
                }
                for (i in 0 until days.size) {
                    drawLine(colors.gridLine, Offset(i * dayW, 0f), Offset(i * dayW, size.height), strokeWidth = 1f)
                }
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val dayWidth = widthPx / days.size
        val pxPerMinute = with(density) { hourHeight.toPx() } / 60f
        val rightGap = with(density) { (if (compact) 4.dp else 10.dp).toPx() }
        val gapPx = with(density) { 1.dp.toPx() }

        fun rectOf(p: PlacedItem): Rect {
            val usable = dayWidth - rightGap
            val colW = usable / p.columns
            val left = p.segment.dayIndex * dayWidth + p.column * colW + gapPx
            val top = p.segment.startMinute * pxPerMinute + gapPx
            val bottom = maxOf(p.segment.endMinute, p.segment.startMinute + 15) * pxPerMinute - gapPx
            return Rect(left, top, left + colW - 2 * gapPx, bottom)
        }

        val hits = placed.map { HitItem(it, rectOf(it)) }
        val hitsState = rememberUpdatedState(hits)
        val daysState = rememberUpdatedState(days)
        val geometry = rememberUpdatedState(Triple(dayWidth, pxPerMinute, zone))
        val edgePx = with(density) { (if (compact) 14.dp else 6.dp).toPx() }
        val autoScrollEdge = with(density) { 56.dp.toPx() }
        val swipeThreshold = with(density) { 72.dp.toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(controller) {
                    val gestures = this
                    coroutineScope {
                        val outer = this
                        gestures.awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (!down.isTouch && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                            val (dayW, pxMin, z) = geometry.value
                            val visible = daysState.value
                            val origin = visible.first()
                            fun dayAt(x: Float) = (x / dayW).toInt().coerceIn(0, visible.size - 1)
                            fun minuteAt(y: Float) = (y / pxMin).roundToInt().coerceIn(0, MINUTES_PER_DAY)
                            fun absAt(p: Offset) = dayAt(p.x) * MINUTES_PER_DAY + minuteAt(p.y)

                            val hit = hitsState.value.lastOrNull { !it.item.isDraft && it.rect.contains(down.position) }
                            val kind = when {
                                hit == null -> DragKind.Create
                                !hit.item.editable -> null
                                else -> {
                                    val r = hit.rect
                                    val edge = minOf(edgePx, r.height / 4f)
                                    when {
                                        down.position.y >= r.bottom - edge && !hit.item.segment.continuesAfter -> DragKind.ResizeBottom
                                        down.position.y <= r.top + edge && !hit.item.segment.continuesBefore -> DragKind.ResizeTop
                                        else -> DragKind.Move
                                    }
                                }
                            }
                            val startToken = controller.dragCancelToken
                            val press = classifyPress(down, viewConfig.touchSlop, viewConfig.longPressTimeoutMillis)
                            when (press.outcome) {
                                PressOutcome.Cancel -> Unit
                                PressOutcome.Swipe -> {
                                    val dx = trackSwipe(down.id, down.position.x) { onSwipe(it * 0.6f) }
                                    onSwipe(0f)
                                    if (abs(dx) > swipeThreshold) controller.page(if (dx < 0) 1 else -1)
                                }
                                PressOutcome.Tap -> {
                                    if (hit != null) {
                                        controller.openEditor(hit.item.event)
                                    } else {
                                        val day = dayAt(down.position.x)
                                        val start = snapDown(minuteAt(down.position.y), 30).coerceAtMost(MINUTES_PER_DAY - 60)
                                        val abs = day * MINUTES_PER_DAY + start
                                        controller.startNewEvent(EventTiming.Timed(instantAt(origin, abs, z), instantAt(origin, abs + 60, z)))
                                    }
                                }
                                PressOutcome.Drag -> {
                                    if (kind == null) return@awaitEachGesture
                                    if (down.isTouch) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val event = hit?.item?.event
                                    val evStart = event?.let { absMinuteOf(it.startIn(z), origin, z) } ?: 0
                                    val evEnd = event?.let { absMinuteOf(it.endIn(z), origin, z) } ?: 0
                                    val duration = evEnd - evStart
                                    val grab = absAt(down.position) - evStart
                                    val createDay = dayAt(down.position.x)
                                    val createAnchor = snapDown(minuteAt(down.position.y))
                                    val color = event?.let { controller.colorOf(it) } ?: (controller.defaultCalendar()?.color ?: 0xFF2F80EDL)

                                    fun compute(p: Offset): GridPreview {
                                        val (s, e) = when (kind) {
                                            DragKind.Move -> {
                                                val day = dayAt(p.x)
                                                val s = snap(absAt(p) - grab)
                                                    .coerceIn(day * MINUTES_PER_DAY, day * MINUTES_PER_DAY + MINUTES_PER_DAY - 15)
                                                s to s + duration
                                            }
                                            DragKind.ResizeBottom -> evStart to maxOf(evStart + 15, snap(absAt(p)))
                                            DragKind.ResizeTop -> minOf(evEnd - 15, snap(absAt(p))) to evEnd
                                            DragKind.Create -> {
                                                val cur = snap(minuteAt(p.y))
                                                val base = createDay * MINUTES_PER_DAY
                                                if (cur > createAnchor) base + createAnchor to base + maxOf(cur, createAnchor + 15)
                                                else base + cur.coerceAtMost(createAnchor) to base + createAnchor + 15
                                            }
                                        }
                                        return GridPreview(event?.key, event?.title ?: "New event", color, s, e)
                                    }

                                    var pos = press.position
                                    preview = compute(pos)
                                    var dragging = true
                                    val autoScroll = outer.launch {
                                        while (isActive && dragging) {
                                            val y = pos.y - scrollValue()
                                            val speed = when {
                                                y < autoScrollEdge -> -(autoScrollEdge - y) / autoScrollEdge * 18f
                                                y > viewportHeight - autoScrollEdge -> (y - (viewportHeight - autoScrollEdge)) / autoScrollEdge * 18f
                                                else -> 0f
                                            }
                                            if (speed != 0f) {
                                                val moved = scrollBy(speed)
                                                if (moved != 0f) {
                                                    pos = pos.copy(y = pos.y + moved)
                                                    preview = compute(pos)
                                                }
                                            }
                                            delay(16)
                                        }
                                    }
                                    val released = trackDrag(down.id, { controller.dragCancelToken != startToken }) {
                                        pos = it
                                        preview = compute(it)
                                    }
                                    dragging = false
                                    autoScroll.cancel()
                                    val result = preview
                                    preview = null
                                    if (!released || result == null) return@awaitEachGesture
                                    val newStart = instantAt(origin, result.startAbs, z)
                                    when (kind) {
                                        DragKind.Create -> controller.startNewEvent(
                                            EventTiming.Timed(newStart, instantAt(origin, result.endAbs, z)),
                                        )
                                        DragKind.Move -> {
                                            val t = event!!.timing as EventTiming.Timed
                                            controller.changeTiming(event, EventTiming.Timed(newStart, newStart + (t.end - t.start)))
                                        }
                                        else -> controller.changeTiming(
                                            event!!,
                                            EventTiming.Timed(newStart, instantAt(origin, result.endAbs, z)),
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
        ) {
            val activePreview = preview
            for (h in hits) {
                val p = h.item
                val dragged = activePreview?.eventKey != null && activePreview.eventKey == p.event.key
                val past = p.event.endIn(zone) < now && !p.isDraft
                EventBlock(
                    event = p.event,
                    color = controller.colorOf(p.event).let { if (p.isDraft) controller.defaultCalendar()?.color ?: it else it },
                    rect = h.rect,
                    segment = p.segment,
                    zone = zone,
                    faded = past || dragged,
                    ghost = dragged,
                    isDraft = p.isDraft,
                    resizable = p.editable && !compact,
                )
            }
            if (activePreview != null) {
                for ((i, seg) in segmentsForRange(activePreview.startAbs, activePreview.endAbs, days.size).withIndex()) {
                    val left = seg.dayIndex * dayWidth + gapPx
                    val top = seg.startMinute * pxPerMinute + gapPx
                    val rect = Rect(left, top, left + dayWidth - rightGap - 2 * gapPx, seg.endMinute * pxPerMinute - gapPx)
                    PreviewBlock(activePreview, rect, showLabel = i == 0)
                }
            }
            // Current time line on today's column.
            val todayIndex = days.indexOf(today)
            if (todayIndex >= 0) {
                val y = minuteOfDay(now, zone) * pxPerMinute
                val x = todayIndex * dayWidth
                Box(
                    Modifier
                        .offset { IntOffset(x.roundToInt(), (y - with(density) { 1.dp.toPx() }).roundToInt()) }
                        .width(with(density) { dayWidth.toDp() })
                        .height(2.dp)
                        .background(colors.today),
                )
                Box(
                    Modifier
                        .offset { IntOffset((x - with(density) { 4.dp.toPx() }).roundToInt(), (y - with(density) { 4.dp.toPx() }).roundToInt()) }
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.today),
                )
            }
        }
    }
}

@Composable
private fun EventBlock(
    event: CalEvent,
    color: Long,
    rect: Rect,
    segment: DaySegment,
    zone: TimeZone,
    faded: Boolean,
    ghost: Boolean,
    isDraft: Boolean,
    resizable: Boolean,
) {
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    val palette = eventPalette(color, colors, faded)
    val heightDp = with(density) { rect.height.toDp() }
    val widthDp = with(density) { rect.width.toDp() }
    val startMin = minuteOfDay(event.startIn(zone), zone)
    val endMin = minuteOfDay(event.endIn(zone), zone)
    val title = if (isDraft) event.title.ifBlank { "New event" } else event.title
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(widthDp, heightDp)
            .testTag("event:${event.title}")
            .graphicsLayer { alpha = if (ghost) 0.45f else 1f }
            .clip(RoundedCornerShape(4.dp))
            .background(palette.fill)
            .then(if (isDraft) Modifier.border(1.5.dp, palette.bar, RoundedCornerShape(4.dp)) else Modifier),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(palette.bar))
        EventLabel(title, formatTimeRange(startMin, endMin), heightDp, widthDp, palette.text, palette.subText)
        if (resizable) {
            Box(Modifier.fillMaxWidth().height(5.dp).align(Alignment.TopCenter).pointerHoverIcon(ResizeVerticalPointerIcon))
            Box(Modifier.fillMaxWidth().height(5.dp).align(Alignment.BottomCenter).pointerHoverIcon(ResizeVerticalPointerIcon))
        }
    }
}

@Composable
private fun EventLabel(title: String, time: String, height: Dp, width: Dp, text: Color, sub: Color) {
    // Thresholds follow the text size, so larger text switches to the compact layout sooner.
    val fs = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val narrow = width < 90.dp * fs
    if (height < 34.dp * fs) {
        val tiny = height < 20.dp * fs
        Row(Modifier.padding(start = 7.dp, end = 4.dp, top = if (tiny) 0.dp else 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = AppType.eventTitle.copy(fontSize = if (tiny) 10.sp else 12.sp), color = text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (!narrow) Text("  " + time.substringBefore(" –"), style = AppType.eventTime.copy(fontSize = if (tiny) 10.sp else 11.sp), color = sub, maxLines = 1)
        }
    } else {
        Column(Modifier.padding(start = 7.dp, end = 4.dp, top = 3.dp)) {
            val titleLines = if (narrow) 1 else (((height - 18.dp * fs) / (15.dp * fs)).toInt()).coerceIn(1, 4)
            Text(title, style = AppType.eventTitle, color = text, maxLines = titleLines, overflow = TextOverflow.Ellipsis, softWrap = !narrow)
            Text(if (narrow) time.substringBefore(" –") else time, style = AppType.eventTime, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PreviewBlock(preview: GridPreview, rect: Rect, showLabel: Boolean) {
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    val palette = eventPalette(preview.color, colors)
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { maxOf(rect.height, 1f).toDp() })
            .graphicsLayer { shadowElevation = 8f; shape = RoundedCornerShape(4.dp); clip = true }
            .background(palette.fill)
            .border(1.5.dp, palette.bar, RoundedCornerShape(4.dp)),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(palette.bar))
        if (showLabel) {
            val start = preview.startAbs.mod(MINUTES_PER_DAY)
            val end = preview.endAbs.mod(MINUTES_PER_DAY)
            EventLabel(preview.title, formatTimeRange(start, end), with(density) { rect.height.toDp() }, with(density) { rect.width.toDp() }, palette.text, palette.subText)
        }
    }
}
