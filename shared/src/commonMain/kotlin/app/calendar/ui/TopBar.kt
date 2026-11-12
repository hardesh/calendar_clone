package app.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.calendar.model.ViewMode
import app.calendar.state.CalendarController
import app.calendar.ui.theme.AppType
import app.calendar.ui.theme.LocalAppColors

@Composable
fun TopBar(controller: CalendarController, compact: Boolean, onMenu: (() -> Unit)?) {
    val colors = LocalAppColors.current
    val days = controller.visibleDays()
    val ref = if (controller.viewMode == ViewMode.MONTH) controller.anchor else days.first()
    val monthName = ref.month.name.lowercase().replaceFirstChar { it.uppercase() }
    val last = days.last()
    val spansMonths = controller.viewMode != ViewMode.MONTH && last.month != ref.month
    fun short(m: kotlinx.datetime.Month) = m.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    val title = when {
        spansMonths && last.year != ref.year -> "${short(ref.month)} ${ref.year} – ${short(last.month)} ${last.year}"
        spansMonths -> "${short(ref.month)} – ${short(last.month)} ${ref.year}"
        compact -> "${short(ref.month)} ${ref.year}"
        else -> "$monthName ${ref.year}"
    }
    Row(
        Modifier.fillMaxWidth().height(if (compact) 52.dp else 56.dp).padding(horizontal = if (compact) 6.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onMenu != null) IconBtn(Icons.Default.Menu, "Menu", onMenu, size = 40.dp, tint = colors.text)
        Text(title, style = AppType.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
        if (controller.offline) {
            Text(
                when {
                    controller.pendingChanges > 0 && compact -> "Offline · ${controller.pendingChanges}"
                    controller.pendingChanges == 1 -> "Offline · 1 change waiting to sync"
                    controller.pendingChanges > 1 -> "Offline · ${controller.pendingChanges} changes waiting to sync"
                    compact -> "Offline"
                    else -> "Offline · showing saved events"
                },
                style = AppType.small,
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.hover)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        } else if (controller.loadingCount > 0) {
            CircularProgressIndicator(Modifier.padding(start = 10.dp).size(14.dp), strokeWidth = 2.dp, color = colors.textTertiary)
        }
        Spacer(Modifier.weight(1f))
        if (!compact) {
            IconBtn(Icons.Default.Refresh, "Refresh", { controller.refresh() })
            Spacer(Modifier.width(8.dp))
        }
        OutlineButton("Today", { controller.goToday() })
        if (!compact) {
            Spacer(Modifier.width(4.dp))
            IconBtn(Icons.Default.KeyboardArrowLeft, "Previous", { controller.page(-1) }, tint = colors.text)
            IconBtn(Icons.Default.KeyboardArrowRight, "Next", { controller.page(1) }, tint = colors.text)
        }
        Spacer(Modifier.width(8.dp))
        Segmented(
            options = viewOptions(compact),
            selected = controller.viewMode,
            label = { if (compact) it.shortLabel else it.label },
            onSelect = { controller.setView(it) },
        )
    }
}

/** Phones get a 3-day view; wider screens (Mac, Linux) get a full week instead. */
fun viewOptions(compact: Boolean): List<ViewMode> =
    if (compact) listOf(ViewMode.DAY, ViewMode.THREE_DAY, ViewMode.MONTH)
    else listOf(ViewMode.DAY, ViewMode.WEEK, ViewMode.MONTH)
