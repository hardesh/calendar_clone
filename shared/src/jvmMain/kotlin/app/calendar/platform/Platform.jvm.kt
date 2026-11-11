package app.calendar.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

actual val ResizeVerticalPointerIcon: PointerIcon = PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
}
