package app.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.calendar.data.DemoProvider
import app.calendar.data.GoogleCalendarApi
import app.calendar.data.GoogleProvider
import app.calendar.model.ViewMode
import app.calendar.platform.PlatformBackHandler
import app.calendar.platform.PlatformServices
import app.calendar.state.AppSettings
import app.calendar.state.CalendarController
import app.calendar.ui.theme.AppTheme
import app.calendar.ui.theme.AppType
import app.calendar.ui.theme.LocalAppColors
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun defaultHttpClient() = HttpClient {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
    }
}

/** Google accounts for code outside the UI, e.g. Android's background reminder sync. */
fun createGoogleProvider(auth: app.calendar.platform.GoogleAuth, store: app.calendar.platform.KeyValueStore) =
    GoogleProvider(auth, GoogleCalendarApi(defaultHttpClient()), store)

fun createController(platform: PlatformServices, scope: CoroutineScope): CalendarController {
    val settings = AppSettings(platform.store)
    val http = defaultHttpClient()
    lateinit var controller: CalendarController
    val providers = buildList {
        add(GoogleProvider(platform.googleAuth, GoogleCalendarApi(http), platform.store))
        add(DemoProvider(zone = { controller.primaryZone }))
    }
    controller = CalendarController(
        providers, settings, scope,
        cache = app.calendar.data.EventCache(platform.cache),
        forceDemo = platform.forceDemo,
        supportsReminders = platform.supportsReminders,
    )
    return controller
}

@Composable
fun CalendarApp(platform: PlatformServices) {
    val scope = rememberCoroutineScope()
    val controller = remember { createController(platform, scope) }
    LaunchedEffect(Unit) { controller.start() }
    LaunchedEffect(Unit) {
        platform.openEvent = { key -> controller.openEventByKey(key) }
        val listener = platform.onUpcomingEvents ?: return@LaunchedEffect
        // Reading reminderMinutes too makes a change of reminder time reschedule notifications.
        androidx.compose.runtime.snapshotFlow { controller.reminderMinutes; controller.upcomingEvents() }
            .collect { listener(it) }
    }
    AppTheme { CalendarScreen(controller) }
}

@Composable
fun CalendarScreen(controller: CalendarController) {
    val colors = LocalAppColors.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .onPreviewKeyEvent { handleShortcut(controller, it) }
            .focusRequester(focus)
            .focusable(),
    ) {
        val compact = maxWidth < 720.dp
        // Swap 3 days ↔ week to suit the screen (e.g. a setting carried over from another size).
        LaunchedEffect(compact, controller.viewMode) {
            when {
                compact && controller.viewMode == ViewMode.WEEK -> controller.setView(ViewMode.THREE_DAY)
                !compact && controller.viewMode == ViewMode.THREE_DAY -> controller.setView(ViewMode.WEEK)
            }
        }
        // Settings → Text size scales text everywhere (side panel included); grid zoom is separate.
        val textScale = controller.textScale
        val baseDensity = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * textScale)) {
            if (compact) {
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                PlatformBackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
                ModalNavigationDrawer(
                    drawerState = drawer,
                    gesturesEnabled = drawer.isOpen,
                    drawerContent = {
                        ModalDrawerSheet(drawerContainerColor = colors.sidebar, modifier = Modifier.width(300.dp * textScale.coerceAtMost(1.25f))) {
                            Sidebar(controller, Modifier.statusBarsPadding(), compact = true, onNavigate = { scope.launch { drawer.close() } })
                        }
                    },
                ) {
                    Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
                        TopBar(controller, compact = true, onMenu = { scope.launch { drawer.open() } })
                        MainView(controller, compact = true, modifier = Modifier.weight(1f))
                    }
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(controller, Modifier.width(248.dp * textScale))
                    Box(Modifier.fillMaxHeight().width(1.dp).background(colors.border))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        TopBar(controller, compact = false, onMenu = null)
                        MainView(controller, compact = false, modifier = Modifier.weight(1f))
                    }
                }
            }
            EventEditorHost(controller, compact)
            Toasts(controller, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp))
        }
    }
}

@Composable
private fun MainView(controller: CalendarController, compact: Boolean, modifier: Modifier) {
    when (controller.viewMode) {
        ViewMode.MONTH -> MonthGridView(controller, compact, modifier)
        else -> TimeGridView(controller, compact, modifier)
    }
}

@Composable
private fun Toasts(controller: CalendarController, modifier: Modifier) {
    val colors = LocalAppColors.current
    Column(modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (t in controller.toasts) {
            LaunchedEffect(t.id) {
                delay(4_500)
                controller.dismissToast(t.id)
            }
            Text(
                t.message,
                style = AppType.body,
                color = colors.onToast,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.toast)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

private fun handleShortcut(controller: CalendarController, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown || controller.editor != null) return false
    val mod = e.isMetaPressed || e.isCtrlPressed
    when {
        e.key == Key.Escape -> controller.cancelDrag()
        mod && e.key == Key.R -> controller.refresh()
        mod && (e.key == Key.Equals || e.key == Key.Plus || e.key == Key.NumPadAdd) -> controller.zoomBy(CalendarController.ZOOM_STEP, compact = false)
        mod && (e.key == Key.Minus || e.key == Key.NumPadSubtract) -> controller.zoomBy(1 / CalendarController.ZOOM_STEP, compact = false)
        mod && (e.key == Key.Zero || e.key == Key.NumPad0) -> controller.resetZoom()
        mod -> return false
        e.key == Key.D || e.key == Key.One -> controller.setView(ViewMode.DAY)
        e.key == Key.W || e.key == Key.Seven || e.key == Key.Three -> controller.setView(ViewMode.WEEK)
        e.key == Key.M -> controller.setView(ViewMode.MONTH)
        e.key == Key.T -> controller.goToday()
        e.key == Key.DirectionLeft || e.key == Key.J -> controller.page(-1)
        e.key == Key.DirectionRight || e.key == Key.K -> controller.page(1)
        else -> return false
    }
    return true
}
