package app.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.calendar.logic.MONTH_GRID_DAYS
import app.calendar.logic.displayLabel
import app.calendar.logic.monthGridStart
import app.calendar.logic.offsetLabel
import app.calendar.logic.plusDays
import app.calendar.logic.searchZones
import app.calendar.logic.zoneOrNull
import app.calendar.model.CalendarInfo
import app.calendar.model.ViewMode
import app.calendar.model.ZoneEntry
import app.calendar.state.CalendarController
import app.calendar.ui.theme.AppType
import app.calendar.ui.theme.LocalAppColors
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.math.roundToInt

@Composable
fun Sidebar(controller: CalendarController, modifier: Modifier = Modifier, compact: Boolean = false, onNavigate: () -> Unit = {}) {
    val colors = LocalAppColors.current
    var showZonePicker by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxHeight()
            .background(colors.sidebar)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        MiniMonth(controller, onPick = { controller.goTo(it); onNavigate() })

        SectionHeader("Calendars")
        if (controller.accounts.isEmpty()) {
            Text(
                "No calendars yet. Connect a Google account, or turn on demo data below.",
                style = AppType.small,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        for (account in controller.accounts) {
            AccountBlock(controller, account)
        }
        val google = controller.googleAuth
        HoverRow(onClick = { controller.addGoogleAccount() }, modifier = Modifier.fillMaxWidth().height(30.dp), enabled = !controller.addingAccount) {
            Icon(Icons.Default.Add, null, tint = colors.textSecondary, modifier = Modifier.padding(start = 6.dp, end = 6.dp).size(16.dp))
            Text(
                if (controller.addingAccount) "Waiting for Google sign-in…" else "Add Google account",
                style = AppType.body,
                color = colors.textSecondary,
            )
        }
        if (google != null && !google.isConfigured) {
            Text(google.setupHint, style = AppType.small, color = colors.textTertiary, modifier = Modifier.padding(8.dp))
        }

        SectionHeader("Time zones", trailing = {
            IconBtn(Icons.Default.Add, "Add time zone", { showZonePicker = true }, size = 24.dp)
        })
        for ((i, z) in controller.zones.withIndex()) ZoneRow(controller, z, i)

        SectionHeader("Settings")
        if (!controller.hasRealAccount() || controller.demoEnabled) {
            ToggleRow("Demo data", controller.demoEnabled) { controller.changeDemoEnabled(it) }
        }
        ZoomRow(controller, compact)
        if (controller.supportsReminders) ReminderRow(controller)
        ToggleRow("Week starts Monday", controller.weekStart == DayOfWeek.MONDAY) {
            controller.changeWeekStart(if (it) DayOfWeek.MONDAY else DayOfWeek.SUNDAY)
        }
        Spacer(Modifier.height(24.dp))
    }
    if (showZonePicker) ZonePickerDialog(controller, onDismiss = { showZonePicker = false })
}

@Composable
private fun MiniMonth(controller: CalendarController, onPick: (LocalDate) -> Unit) {
    val colors = LocalAppColors.current
    var shownMonth by remember(controller.anchor.year, controller.anchor.month) {
        mutableStateOf(LocalDate(controller.anchor.year, controller.anchor.month, 1))
    }
    val start = monthGridStart(shownMonth, controller.weekStart)
    val today = controller.today()
    val visible = controller.visibleDays().toSet()
    Column(Modifier.padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) {
            Text(
                "${shownMonth.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${shownMonth.year}",
                style = AppType.bodyMedium,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            IconBtn(Icons.Default.KeyboardArrowLeft, "Previous month", { shownMonth = shownMonth.plus(-1, DateTimeUnit.MONTH) }, size = 24.dp)
            IconBtn(Icons.Default.KeyboardArrowRight, "Next month", { shownMonth = shownMonth.plus(1, DateTimeUnit.MONTH) }, size = 24.dp)
        }
        Row {
            for (i in 0 until 7) {
                Text(
                    start.plusDays(i).dayOfWeek.name.take(1),
                    style = AppType.tiny,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        for (r in 0 until MONTH_GRID_DAYS / 7) {
            Row {
                for (c in 0 until 7) {
                    val d = start.plusDays(r * 7 + c)
                    val isToday = d == today
                    val inRange = d in visible && controller.viewMode != ViewMode.MONTH
                    Box(Modifier.weight(1f).height(26.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isToday -> colors.today
                                        inRange -> colors.selected
                                        else -> Color.Transparent
                                    },
                                )
                                .clickable { onPick(d) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                d.day.toString(),
                                style = AppType.small.copy(fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal),
                                color = when {
                                    isToday -> Color.White
                                    d.month == shownMonth.month -> colors.text
                                    else -> colors.textTertiary
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountBlock(controller: CalendarController, account: app.calendar.model.Account) {
    val colors = LocalAppColors.current
    var menu by remember { mutableStateOf(false) }
    val error = controller.accountErrors[account.id]
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            account.email,
            style = AppType.small,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (account.provider != "demo") {
            Box {
                IconBtn(Icons.Default.MoreVert, "Account options", { menu = true }, size = 24.dp)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menu = false; controller.addGoogleAccount() })
                    DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; controller.removeAccount(account) })
                }
            }
        }
    }
    if (error != null) {
        HoverRow(onClick = { controller.addGoogleAccount() }, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Sign-in expired — click to reconnect",
                style = AppType.small,
                color = colors.today,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
    for (cal in controller.calendars.filter { it.accountId == account.id }) {
        CalendarRow(cal, visible = cal.key !in controller.hidden) { controller.toggleCalendar(cal) }
    }
}

@Composable
private fun CalendarRow(cal: CalendarInfo, visible: Boolean, onToggle: () -> Unit) {
    val colors = LocalAppColors.current
    val c = Color(cal.color.toInt())
    HoverRow(onClick = onToggle, modifier = Modifier.fillMaxWidth().height(28.dp)) {
        Box(
            Modifier
                .padding(start = 8.dp, end = 8.dp)
                .size(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (visible) c else Color.Transparent)
                .border(1.5.dp, c, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (visible) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(11.dp))
        }
        Text(
            cal.name,
            style = AppType.body,
            color = if (visible) colors.text else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (cal.readOnly) Text("view only", style = AppType.tiny, color = colors.textTertiary, modifier = Modifier.padding(end = 8.dp))
    }
}

@Composable
private fun ZoneRow(controller: CalendarController, entry: ZoneEntry, index: Int) {
    val colors = LocalAppColors.current
    val now = controller.now()
    val zone = zoneOrNull(entry.id)
    var editing by remember { mutableStateOf(false) }
    var labelText by remember(entry) { mutableStateOf(entry.label ?: "") }
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (editing) {
                BasicTextField(
                    value = labelText,
                    onValueChange = { labelText = it.take(8) },
                    singleLine = true,
                    textStyle = AppType.body.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier
                        .border(1.dp, colors.border, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                        .width(80.dp),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.displayLabel(now),
                        style = AppType.bodyMedium,
                        color = colors.text,
                        modifier = Modifier.clickable { editing = true },
                    )
                    if (index == 0) Text("  primary", style = AppType.tiny, color = colors.textTertiary)
                }
            }
            Text(
                "${entry.id.substringAfterLast('/').replace('_', ' ')} · ${zone?.let { offsetLabel(it, now) } ?: "?"}",
                style = AppType.tiny,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (editing) {
            IconBtn(Icons.Default.Check, "Save label", { controller.renameZone(entry.id, labelText); editing = false }, size = 24.dp)
        } else {
            if (index > 0) IconBtn(Icons.Default.KeyboardArrowUp, "Move up (top is primary)", { controller.moveZone(entry.id, -1) }, size = 24.dp)
            if (index < controller.zones.size - 1) IconBtn(Icons.Default.KeyboardArrowDown, "Move down", { controller.moveZone(entry.id, 1) }, size = 24.dp)
            if (controller.zones.size > 1) IconBtn(Icons.Default.Close, "Remove", { controller.removeZone(entry.id) }, size = 24.dp)
        }
    }
}

@Composable
private fun ZonePickerDialog(controller: CalendarController, onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    var query by remember { mutableStateOf("") }
    val now = controller.now()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(360.dp)
                .heightIn(max = 480.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                .padding(12.dp),
        ) {
            Text("Add time zone", style = AppType.bodyMedium, color = colors.text, modifier = Modifier.padding(bottom = 8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = AppType.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("Search city or region…", style = AppType.body, color = colors.textTertiary)
                        inner()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxWidth()) {
                items(searchZones(query), key = { it.id }) { opt ->
                    HoverRow(onClick = { controller.addZone(opt.id); onDismiss() }, modifier = Modifier.fillMaxWidth().height(34.dp)) {
                        Text(opt.city, style = AppType.body, color = colors.text, modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1)
                        Text(
                            zoneOrNull(opt.id)?.let { offsetLabel(it, now) } ?: "",
                            style = AppType.small,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** "Notify before events": Off / 5 / 10 / 15 / 30 / 60 minutes. */
@Composable
private fun ReminderRow(controller: CalendarController) {
    val colors = LocalAppColors.current
    var open by remember { mutableStateOf(false) }
    fun label(m: Int) = if (m == 0) "Off" else if (m == 60) "1 hour" else "$m min"
    Row(
        Modifier.fillMaxWidth().height(34.dp).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Notify before events", style = AppType.body, color = colors.text, modifier = Modifier.weight(1f))
        Box {
            HoverRow(onClick = { open = true }, modifier = Modifier.height(28.dp)) {
                Text(label(controller.reminderMinutes), style = AppType.body, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 8.dp))
                Icon(Icons.Default.KeyboardArrowDown, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (m in listOf(0, 5, 10, 15, 30, 60)) {
                    DropdownMenuItem(text = { Text(label(m)) }, onClick = { open = false; controller.changeReminderMinutes(m) })
                }
            }
        }
    }
}

/** Zoom of the day/week grids: − / percentage (click to reset) / +. */
@Composable
private fun ZoomRow(controller: CalendarController, compact: Boolean) {
    val colors = LocalAppColors.current
    val percent = (controller.hourHeightFor(compact) / controller.defaultHourHeight(compact) * 100).roundToInt()
    Row(
        Modifier.fillMaxWidth().height(34.dp).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Zoom", style = AppType.body, color = colors.text, modifier = Modifier.weight(1f))
        HoverRow(onClick = { controller.zoomBy(1 / CalendarController.ZOOM_STEP, compact) }, modifier = Modifier.size(26.dp)) {
            Text("−", style = AppType.title, color = colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Text(
            "$percent%",
            style = AppType.small,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(44.dp).clickable { controller.resetZoom() },
        )
        IconBtn(Icons.Default.Add, "Zoom in", { controller.zoomBy(CalendarController.ZOOM_STEP, compact) }, size = 26.dp)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().height(34.dp).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = AppType.body, color = colors.text)
        Toggle(checked, onChange, Modifier.padding(end = 6.dp))
    }
}
