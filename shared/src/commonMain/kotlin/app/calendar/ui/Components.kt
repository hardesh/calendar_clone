package app.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.calendar.ui.theme.AppType
import app.calendar.ui.theme.LocalAppColors

/** A flat, hover-highlighted clickable area in Notion's style. */
@Composable
fun HoverRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected -> colors.selected
                    hovered && enabled -> colors.hover
                    else -> Color.Transparent
                },
            )
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun IconBtn(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    tint: Color = LocalAppColors.current.textSecondary,
) {
    HoverRow(onClick, modifier.size(size)) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    HoverRow(
        onClick,
        modifier
            .height(30.dp.scaled())
            .border(1.dp, colors.border, RoundedCornerShape(6.dp)),
    ) {
        Text(text, style = AppType.bodyMedium, color = colors.text, modifier = Modifier.padding(horizontal = 10.dp))
    }
}

/** Notion-style segmented control (Day / 3 days / Month). */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier
            .height(30.dp.scaled())
            .clip(RoundedCornerShape(7.dp))
            .background(colors.hover)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (o in options) {
            val isSel = o == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (isSel) colors.background else Color.Transparent)
                    .clickable { onSelect(o) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(o), style = AppType.bodyMedium, color = if (isSel) colors.text else colors.textSecondary)
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    val colors = LocalAppColors.current
    Row(modifier.padding(start = 8.dp, end = 4.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = AppType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = colors.textSecondary, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Small Notion-style on/off switch. */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = LocalAppColors.current
    Box(
        modifier
            .size(width = 30.dp, height = 18.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (checked) colors.accent else colors.selected)
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(14.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.White))
    }
}

/** A height for a row of text that grows with the text size (zoom and the system font size). */
@Composable
fun androidx.compose.ui.unit.Dp.scaled(): androidx.compose.ui.unit.Dp =
    this * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
