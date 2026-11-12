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
import app.calendar.model.meetingLink
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
    val original = controller.editor?.original
    // Invitations: time and details belong to the organiser; your reply, colour and reminders are yours.
    val editable = isNew || (original != null && controller.canEdit(original))
    val personal = isNew || (original != null && controller.canEditPersonal(original))
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
        if (personal) ColorPicker(controller, draft)
        TimingEditor(draft.timing, zone, editable) { t -> controller.updateDraft { it.copy(timing = t) } }
        SecondaryZoneTimes(controller, draft)
        if (draft.recurringEventId != null) {
            Text("Repeating event — changes apply to this occurrence only.", style = AppType.small, color = colors.textSecondary)
        }
        VideoCallRow(controller, draft, editable)
        if (!isNew && original != null && original.selfAttendee != null && !original.organizerSelf && personal) {
            RsvpRow(controller, original)
        }
        GuestsSection(controller, draft, original, editable)
        RemindersSection(controller, draft, personal)
        FieldBox("Location", draft.location ?: "", editable, singleLine = true) { v -> controller.updateDraft { it.copy(location = v.ifEmpty { null }) } }
        FieldBox("Description", draft.description ?: "", editable, singleLine = false) { v -> controller.updateDraft { it.copy(description = v.ifEmpty { null }) } }
    }
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!isNew && editable) {
            IconBtn(Icons.Default.Delete, "Delete event", { controller.delete(controller.editor!!.original!!) }, tint = colors.today)
        }
        if (!personal) Text("This calendar is view only", style = AppType.small, color = colors.textSecondary)
        else if (!editable) Text("Organised by someone else", style = AppType.small, color = colors.textSecondary)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { controller.closeEditor() }) { Text(if (personal) "Cancel" else "Close", color = colors.textSecondary) }
        if (personal) {
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


// ---- video call -------------------------------------------------------------------

@Composable
private fun VideoCallRow(controller: CalendarController, draft: CalEvent, editable: Boolean) {
    val colors = LocalAppColors.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val link = draft.meetingLink()
    val isGoogle = controller.accounts.firstOrNull { it.id == draft.accountId }?.provider == "google"
    when {
        link != null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.accent)
                    .clickable { runCatching { uri.openUri(link.url) } }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("Join ${link.name}", style = AppType.bodyMedium, color = Color.White)
            }
            Text(
                link.url.removePrefix("https://"),
                style = AppType.small,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        draft.requestMeet -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
            Text("A Google Meet link will be added when you save", style = AppType.small, color = colors.textSecondary, modifier = Modifier.weight(1f))
            TextButton(onClick = { controller.updateDraft { it.copy(requestMeet = false) } }) { Text("Remove", color = colors.textSecondary) }
        }
        editable && isGoogle -> HoverRow(
            onClick = { controller.updateDraft { it.copy(requestMeet = true) } },
            modifier = Modifier.fillMaxWidth().height(30.dp),
        ) {
            Text("+ Add Google Meet video call", style = AppType.body, color = colors.accent, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

// ---- reply to an invitation -----------------------------------------------------------

@Composable
private fun RsvpRow(controller: CalendarController, event: CalEvent) {
    val colors = LocalAppColors.current
    val current = event.selfAttendee?.status
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
        Text("Going?", style = AppType.body, color = colors.textSecondary, modifier = Modifier.width(64.dp))
        for ((status, label) in listOf("accepted" to "Yes", "declined" to "No", "tentative" to "Maybe")) {
            val selected = current == status
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) colors.accent else colors.hover)
                    .clickable { controller.respond(event, status) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(label, style = AppType.bodyMedium, color = if (selected) Color.White else colors.text)
            }
        }
    }
}

// ---- guests -------------------------------------------------------------------------

private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

@Composable
private fun GuestsSection(controller: CalendarController, draft: CalEvent, original: CalEvent?, editable: Boolean) {
    val colors = LocalAppColors.current
    var input by remember(draft.key) { mutableStateOf("") }
    val guests = draft.attendees
    if (guests.isEmpty() && !editable) return
    fun setGuests(list: List<app.calendar.model.Attendee>) =
        controller.updateDraft { it.copy(attendees = list, notifyGuests = list != original?.attendees.orEmpty()) }
    fun addFromInput() {
        val emails = input.split(',', ';', ' ', '\n').map { it.trim() }.filter { emailRegex.matches(it) }
        val existing = guests.map { it.email.lowercase() }.toSet()
        val added = emails.filter { it.lowercase() !in existing }.distinct().map { app.calendar.model.Attendee(it) }
        if (added.isNotEmpty()) setGuests(guests + added)
        input = ""
    }
    Column(Modifier.padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val going = guests.count { it.status == "accepted" }
        Text(
            if (guests.isEmpty()) "Guests" else "Guests · ${guests.size}" + if (going > 0) ", $going going" else "",
            style = AppType.small, color = colors.textSecondary,
        )
        for (g in guests.sortedWith(compareByDescending<app.calendar.model.Attendee> { it.organizer }.thenBy { it.displayName.lowercase() })) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(colors.hover),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(g.displayName.take(1).uppercase(), style = AppType.small, color = colors.text)
                }
                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                    Text(
                        g.displayName + (if (g.self) " (you)" else ""),
                        style = AppType.body, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val details = listOfNotNull(
                        if (g.organizer) "Organiser" else null,
                        when (g.status) {
                            "accepted" -> "Going"
                            "declined" -> "Not going"
                            "tentative" -> "Maybe"
                            else -> "Awaiting reply"
                        },
                        if (g.optional) "Optional" else null,
                        g.email.takeIf { g.name != null },
                    )
                    Text(details.joinToString(" · "), style = AppType.tiny, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (editable && !g.self && !g.organizer) {
                    IconBtn(Icons.Default.Close, "Remove ${g.email}", { setGuests(guests.filterNot { it.email == g.email }) }, size = 24.dp)
                }
            }
        }
        if (editable) {
            BasicTextField(
                value = input,
                onValueChange = { v -> if (v.endsWith(",") || v.endsWith(" ")) { input = v; addFromInput() } else input = v },
                singleLine = true,
                textStyle = AppType.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = androidx.compose.ui.text.input.KeyboardType.Email),
                keyboardActions = KeyboardActions(onDone = { addFromInput() }),
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) Text("Add guests by email", style = AppType.body, color = colors.textTertiary)
                        inner()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.hover, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && input.isNotBlank()) { addFromInput(); true } else false
                    },
            )
            if (guests.isNotEmpty() || original?.attendees?.isNotEmpty() == true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Email guests about changes", style = AppType.body, color = colors.text, modifier = Modifier.weight(1f))
                    Toggle(draft.notifyGuests, { on -> controller.updateDraft { it.copy(notifyGuests = on) } })
                }
            }
        }
    }
}

// ---- reminders ------------------------------------------------------------------------

private fun reminderLabel(m: Int): String = when {
    m == 0 -> "At start"
    m < 60 -> "$m min before"
    m % 1440 == 0 -> (m / 1440).let { if (it == 1) "1 day before" else "$it days before" }
    m % 60 == 0 -> (m / 60).let { if (it == 1) "1 hour before" else "$it hours before" }
    else -> "${m / 60} h ${m % 60} min before"
}

@Composable
private fun RemindersSection(controller: CalendarController, draft: CalEvent, editable: Boolean) {
    val colors = LocalAppColors.current
    if (draft.isAllDay && draft.reminders == null) return
    val defaults = controller.calendarOf(draft)?.defaultReminders.orEmpty()
    val usesDefault = draft.reminders == null || draft.reminders.useDefault
    val effective = if (usesDefault) defaults else draft.reminders!!.minutes
    var open by remember { mutableStateOf(false) }
    fun set(list: List<Int>) = controller.updateDraft { it.copy(reminders = app.calendar.model.EventReminders(false, list.distinct().sorted())) }
    Column(Modifier.padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Reminders" + if (usesDefault) " · calendar default" else "", style = AppType.small, color = colors.textSecondary)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (effective.isEmpty()) Text("None", style = AppType.body, color = colors.textTertiary, modifier = Modifier.padding(vertical = 4.dp))
            for (m in effective) {
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(colors.hover).padding(start = 9.dp, end = if (editable) 2.dp else 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(reminderLabel(m), style = AppType.body, color = colors.text, modifier = Modifier.padding(vertical = 5.dp))
                    if (editable) IconBtn(Icons.Default.Close, "Remove reminder", { set(effective - m) }, size = 22.dp)
                }
            }
            if (editable && effective.size < 5) {
                Box {
                    HoverRow(onClick = { open = true }, modifier = Modifier.height(30.dp)) {
                        Text("+ Add", style = AppType.body, color = colors.accent, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for (m in listOf(0, 5, 10, 15, 30, 60, 120, 1440).filter { it !in effective }) {
                            DropdownMenuItem(text = { Text(reminderLabel(m)) }, onClick = { open = false; set(effective + m) })
                        }
                    }
                }
            }
        }
        if (editable && !usesDefault && defaults.isNotEmpty()) {
            Text(
                "Use calendar default (${defaults.joinToString { reminderLabel(it) }})",
                style = AppType.small,
                color = colors.accent,
                modifier = Modifier.clickable { controller.updateDraft { it.copy(reminders = app.calendar.model.EventReminders(true)) } },
            )
        }
    }
}
