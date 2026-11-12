package app.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.calendar.logic.formatTime
import app.calendar.logic.instantAt
import app.calendar.logic.minuteOfDay
import app.calendar.logic.minusDays
import app.calendar.logic.plusDays
import app.calendar.logic.zoneAbbreviation
import app.calendar.logic.zoneOrNull
import app.calendar.model.CalEvent
import app.calendar.model.EventColors
import app.calendar.model.EventTiming
import app.calendar.state.CalendarController
import app.calendar.ui.theme.AppType
import app.calendar.ui.theme.LocalAppColors
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorHost(controller: CalendarController, compact: Boolean) {
    val state = controller.editor ?: return
    val colors = LocalAppColors.current
    if (compact) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { controller.closeEditor() },
            sheetState = sheet,
            containerColor = colors.surface,
        ) {
            Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp).imePadding()) {
                EditorContent(controller, state.draft, state.isNew, compact = true)
            }
        }
    } else {
        Dialog(onDismissRequest = { controller.closeEditor() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier
                    .width(440.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .padding(18.dp)
                    .onPreviewKeyEvent {
                        if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            it.key == Key.Escape -> { controller.closeEditor(); true }
                            it.key == Key.Enter && (it.isMetaPressed || it.isCtrlPressed) -> { controller.saveEditor(); true }
                            else -> false
                        }
                    },
            ) {
                EditorContent(controller, state.draft, state.isNew, compact = false)
            }
        }
    }
}

@Composable
private fun ColumnScope.EditorContent(controller: CalendarController, draft: CalEvent, isNew: Boolean, compact: Boolean) {
    val colors = LocalAppColors.current
    val zone = controller.primaryZone
    val calendar = controller.calendarOf(draft)
    val editable = isNew || calendar?.readOnly == false
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isNew) runCatching { focus.requestFocus() } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = draft.title,
            onValueChange = { v -> controller.updateDraft { it.copy(title = v) } },
            readOnly = !editable,
            singleLine = true,
            textStyle = AppType.title.copy(color = colors.text, fontSize = 20.sp),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { controller.saveEditor() }),
            decorationBox = { inner ->
                Box {
                    if (draft.title.isEmpty()) Text("Event title", style = AppType.title.copy(fontSize = 20.sp), color = colors.textTertiary)
                    inner()
                }
            },
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        if (!compact) IconBtn(Icons.Default.Close, "Close", { controller.closeEditor() }, size = 28.dp)
    }
    Spacer(Modifier.height(14.dp))

    val scroll = rememberScrollState()
    Column(Modifier.heightIn(max = 520.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CalendarPicker(controller, draft, editable)
        if (editable) ColorPicker(controller, draft)
        TimingEditor(draft.timing, zone, editable) { t -> controller.updateDraft { it.copy(timing = t) } }
        SecondaryZoneTimes(controller, draft)
        if (draft.recurringEventId != null) {
            Text("Repeating event — changes apply to this occurrence only.", style = AppType.small, color = colors.textSecondary)
        }
        FieldBox("Location", draft.location ?: "", editable, singleLine = true) { v -> controller.updateDraft { it.copy(location = v.ifEmpty { null }) } }
        FieldBox("Description", draft.description ?: "", editable, singleLine = false) { v -> controller.updateDraft { it.copy(description = v.ifEmpty { null }) } }
    }
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!isNew && editable) {
            IconBtn(Icons.Default.Delete, "Delete event", { controller.delete(controller.editor!!.original!!) }, tint = colors.today)
        }
        if (!editable) Text("This calendar is view only", style = AppType.small, color = colors.textSecondary)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { controller.closeEditor() }) { Text(if (editable) "Cancel" else "Close", color = colors.textSecondary) }
        if (editable) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.accent)
                    .clickable { controller.saveEditor() }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    if (isNew) "Create" else "Save",
                    style = AppType.bodyMedium,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun CalendarPicker(controller: CalendarController, draft: CalEvent, editable: Boolean) {
    val colors = LocalAppColors.current
    var open by remember { mutableStateOf(false) }
    val cal = controller.calendarOf(draft)
    val account = controller.accounts.firstOrNull { it.id == draft.accountId }
    Box {
        HoverRow(onClick = { if (editable) open = true }, modifier = Modifier.fillMaxWidth().height(32.dp)) {
            Box(Modifier.padding(start = 6.dp, end = 8.dp).size(10.dp).clip(CircleShape).background(Color((cal?.color ?: 0xFF9B9B9BL).toInt())))
            Text(cal?.name ?: "Calendar", style = AppType.body, color = colors.text)
            Text("  ${account?.email ?: ""}", style = AppType.small, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (c in controller.writableCalendars()) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.padding(end = 8.dp).size(10.dp).clip(CircleShape).background(Color(c.color.toInt())))
                            Column {
                                Text(c.name, style = AppType.body)
                                Text(controller.accounts.firstOrNull { it.id == c.accountId }?.email ?: "", style = AppType.tiny, color = colors.textSecondary)
                            }
                        }
                    },
                    onClick = {
                        open = false
                        controller.updateDraft { it.copy(accountId = c.accountId, calendarId = c.id) }
                    },
                )
            }
        }
    }
}

/** Google's event colours plus "calendar colour" (no colour of its own). */
@Composable
private fun ColorPicker(controller: CalendarController, draft: CalEvent) {
    val colors = LocalAppColors.current
    val calendarColor = Color((controller.calendarOf(draft)?.color ?: 0xFF9B9B9BL).toInt())
    val options: List<Pair<String?, Color>> = listOf<Pair<String?, Color>>(null to calendarColor) +
        EventColors.all.map { it.id to Color(it.argb.toInt()) }
    val selectedName = EventColors.all.firstOrNull { it.id == draft.colorId }?.name ?: "Calendar colour"
    Text("Colour · $selectedName", style = AppType.small, color = colors.textSecondary, modifier = Modifier.padding(start = 6.dp))
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 6.dp),
    ) {
        for ((id, color) in options) {
            val selected = draft.colorId == id || (id == null && EventColors.argbFor(draft.colorId) == null)
            val name = id?.let { i -> EventColors.all.first { it.id == i }.name } ?: "Calendar colour"
            run {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(color)
                        .then(if (selected) Modifier.border(2.dp, colors.text.copy(alpha = 0.55f), CircleShape) else Modifier)
                        .clickable { controller.updateDraft { it.copy(colorId = id) } },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        selected -> androidx.compose.material3.Icon(Icons.Default.Check, name, tint = Color.White, modifier = Modifier.size(14.dp))
                        id == null -> Box(
                            Modifier.size(width = 2.dp, height = 18.dp)
                                .graphicsLayer { rotationZ = 45f }
                                .background(Color.White.copy(alpha = 0.85f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimingEditor(timing: EventTiming, zone: TimeZone, editable: Boolean, onChange: (EventTiming) -> Unit) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("All day", style = AppType.body, color = colors.textSecondary, modifier = Modifier.width(64.dp))
            Toggle(timing is EventTiming.AllDay, { on -> onChange(toggleAllDay(timing, on, zone)) }, enabled = editable)
        }
        when (timing) {
            is EventTiming.AllDay -> {
                val lastDay = timing.endExclusive.minusDays(1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Starts", style = AppType.body, color = colors.textSecondary, modifier = Modifier.width(64.dp))
                    DateChip(timing.start, editable) { d ->
                        val len = timing.start.daysUntil(timing.endExclusive)
                        onChange(EventTiming.AllDay(d, d.plusDays(len)))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ends", style = AppType.body, color = colors.textSecondary, modifier = Modifier.width(64.dp))
                    DateChip(lastDay, editable) { d -> onChange(EventTiming.AllDay(timing.start, maxOf(d, timing.start).plusDays(1))) }
                }
            }
            is EventTiming.Timed -> {
                val s = timing.start.toLocalDateTime(zone)
                val e = timing.end.toLocalDateTime(zone)
                val duration = timing.end - timing.start
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Starts", style = AppType.body, color = colors.textSecondary, modifier = Modifier.width(64.dp))
                    DateChip(s.date, editable) { d ->
                        val ns = instantAt(d, minuteOfDay(timing.start, zone), zone)
                        onChange(EventTiming.Timed(ns, ns + duration))
                    }
                    Spacer(Modifier.width(6.dp))
                    TimeChip(minuteOfDay(timing.start, zone), editable) { m ->
                        val ns = instantAt(s.date, m, zone)
                        onChange(EventTiming.Timed(ns, ns + duration))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ends", style = AppType.body, color = colors.textSecondary, modifier = Modifier.width(64.dp))
                    DateChip(e.date, editable) { d ->
                        val ne = instantAt(d, minuteOfDay(timing.end, zone), zone)
                        onChange(EventTiming.Timed(timing.start, if (ne > timing.start) ne else timing.start + 15.minutes))
                    }
                    Spacer(Modifier.width(6.dp))
                    TimeChip(minuteOfDay(timing.end, zone), editable) { m ->
                        val ne = instantAt(e.date, m, zone)
                        onChange(EventTiming.Timed(timing.start, if (ne > timing.start) ne else timing.start + 15.minutes))
                    }
                    Text("  ${formatDuration(timing.start, timing.end)}", style = AppType.small, color = colors.textTertiary)
                }
            }
        }
    }
}

private fun toggleAllDay(timing: EventTiming, allDay: Boolean, zone: TimeZone): EventTiming = when {
    allDay && timing is EventTiming.Timed -> {
        val s = timing.start.toLocalDateTime(zone).date
        val e = (timing.end - 1.minutes).toLocalDateTime(zone).date
        EventTiming.AllDay(s, maxOf(s, e).plusDays(1))
    }
    !allDay && timing is EventTiming.AllDay -> {
        val start = instantAt(timing.start, 9 * 60, zone)
        EventTiming.Timed(start, start + 1.hours)
    }
    else -> timing
}

private fun formatDuration(a: Instant, b: Instant): String {
    val m = (b - a).inWholeMinutes
    return when {
        m < 60 -> "${m}m"
        m % 60 == 0L -> "${m / 60}h"
        else -> "${m / 60}h ${m % 60}m"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateChip(date: LocalDate, editable: Boolean, onPick: (LocalDate) -> Unit) {
    val colors = LocalAppColors.current
    var open by remember { mutableStateOf(false) }
    Chip(
        "${date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}, ${date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${date.day}, ${date.year}",
        editable,
    ) { open = true }
    if (open) {
        val utcMillis = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = utcMillis)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    pickerState.selectedDateMillis?.let { ms ->
                        onPick(Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date)
                    }
                }) { Text("OK", color = colors.accent) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel", color = colors.textSecondary) } },
        ) {
            DatePicker(pickerState)
        }
    }
}

@Composable
private fun TimeChip(minute: Int, editable: Boolean, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip(formatTime(minute), editable) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            for (m in 0 until 24 * 60 step 15) {
                DropdownMenuItem(
                    text = { Text(formatTime(m), style = AppType.body, fontWeight = if (m == minute) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { open = false; onPick(m) },
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    HoverRow(onClick, Modifier.height(30.dp).background(colors.hover, RoundedCornerShape(6.dp)), enabled = enabled) {
        Text(text, style = AppType.body, color = colors.text, modifier = Modifier.padding(horizontal = 9.dp), maxLines = 1)
    }
}

@Composable
private fun SecondaryZoneTimes(controller: CalendarController, draft: CalEvent) {
    val colors = LocalAppColors.current
    val t = draft.timing as? EventTiming.Timed ?: return
    for (entry in controller.zones.drop(1)) {
        val z = zoneOrNull(entry.id) ?: continue
        val s = minuteOfDay(t.start, z)
        val e = minuteOfDay(t.end, z)
        val label = entry.label ?: zoneAbbreviation(entry.id, t.start)
        val startDate = t.start.toLocalDateTime(z).date
        val primaryDate = t.start.toLocalDateTime(controller.primaryZone).date
        val shift = primaryDate.daysUntil(startDate)
        Text(
            "${formatTime(s)} – ${formatTime(e)} $label" + when {
                shift > 0 -> " (next day)"
                shift < 0 -> " (previous day)"
                else -> ""
            },
            style = AppType.small,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 64.dp),
        )
    }
}

@Composable
private fun FieldBox(placeholder: String, value: String, editable: Boolean, singleLine: Boolean, onChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    if (!editable && value.isEmpty()) return
    BasicTextField(
        value = value,
        onValueChange = onChange,
        readOnly = !editable,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        textStyle = AppType.body.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = AppType.body, color = colors.textTertiary)
                inner()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.hover, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
