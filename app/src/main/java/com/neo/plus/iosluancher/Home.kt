@file:OptIn(ExperimentalFoundationApi::class)

package com.neo.plus.iosluancher

import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

enum class Overlay { None, Control, Notifs, Search, Assistant }

@Composable
fun LauncherRoot(
    vm: LauncherViewModel, assistant: AssistantViewModel, widgets: WidgetController,
    homeTick: Int, ccTick: Int, assistTick: Int, onBlur: (Int) -> Unit
) {
    var overlay by remember { mutableStateOf(Overlay.None) }
    var category by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SecureSetupDialog()
    val groups = remember(vm.apps) { buildGroups(vm.apps) }
    val blurDp by animateDpAsState(if (overlay == Overlay.Control || overlay == Overlay.Notifs || overlay == Overlay.Assistant) 24.dp else 0.dp, label = "blur")

    LaunchedEffect(ccTick) { if (ccTick > 0) { category = null; showPicker = false; overlay = Overlay.Control } }
    LaunchedEffect(assistTick) { if (assistTick > 0) { category = null; showPicker = false; overlay = Overlay.Assistant } }

    LaunchedEffect(overlay, category, showPicker) {
        onBlur(if (overlay != Overlay.None || category != null || showPicker) 30 else 0)
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent, Color.Black.copy(alpha = 0.28f))))
            .pointerInput(Unit) {
                // Swipe UP from the bottom: CENTER -> Control Center, left / right sides -> Notifications.
                // Swipe DOWN in the middle of the screen -> Spotlight. Nothing is triggered from the top: that edge belongs to Android's own notification shade / quick settings.
                val h = size.height
                val w = size.width
                var startX = 0f
                var startY = 0f
                var total = 0f
                var fired = false
                detectVerticalDragGestures(
                    onDragStart = { startX = it.x; startY = it.y; total = 0f; fired = false },
                    onDragEnd = { total = 0f; fired = false },
                    onDragCancel = { total = 0f; fired = false },
                    onVerticalDrag = { _, dy ->
                        total += dy
                        if (!fired && overlay == Overlay.None) {
                            if (total < -90f && startY > h * 0.72f) {
                                fired = true
                                overlay = if (startX > w * 0.30f && startX < w * 0.70f) Overlay.Control else Overlay.Notifs
                            } else if (total > 100f && startY > h * 0.30f && startY < h * 0.72f) {
                                fired = true
                                overlay = Overlay.Search
                            }
                        }
                    }
                )
            }
    ) {
        Column(
            Modifier.fillMaxSize()
                .then(if (blurDp > 0.dp) Modifier.blur(blurDp) else Modifier)
                .windowInsetsPadding(safeInsets())
        ) {
            Spacer(Modifier.height(6.dp))

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val iconSize = ((maxWidth - 28.dp) / 4 * 0.76f).coerceIn(56.dp, 84.dp)
                val rowH = iconSize + 36.dp
                val rows = ((maxHeight - 72.dp) / rowH).toInt().coerceIn(3, 7)
                val perPage = rows * 4
                val firstCap = ((rows - 2) * 4).coerceAtLeast(4)
                val homeList = vm.homeApps
                val homePages = remember(homeList, perPage, firstCap) {
                    listOf(homeList.take(firstCap)) + homeList.drop(firstCap).chunked(perPage)
                }
                val pager = rememberPagerState(initialPage = 1) { homePages.size + 2 }

                LaunchedEffect(homeTick) {
                    if (homeTick > 0) {
                        overlay = Overlay.None; category = null; showPicker = false
                        pager.animateScrollToPage(1)
                    }
                }
                BackHandler(enabled = true) {
                    when {
                        overlay != Overlay.None -> overlay = Overlay.None
                        category != null -> category = null
                        showPicker -> showPicker = false
                        pager.currentPage != 1 -> scope.launch { pager.animateScrollToPage(1) }
                    }
                }

                Column(Modifier.fillMaxSize()) {
                    HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { page ->
                        when {
                            page == 0 -> TodayPage(widgets) { showPicker = true }
                            page == homePages.size + 1 -> LibraryPage(vm.apps, groups) { category = it }
                            else -> HomeGrid(homePages[page - 1], vm, iconSize, rowH, withWidgets = page == 1)
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(28.dp), Arrangement.Center, Alignment.CenterVertically) {
                        repeat(pager.pageCount) { i ->
                            val sel = pager.currentPage == i
                            Box(
                                Modifier.padding(horizontal = 4.dp).size(if (sel) 8.dp else 6.dp).clip(CircleShape)
                                    .background(Color.White.copy(alpha = if (sel) 0.95f else 0.45f))
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(44.dp), Arrangement.Center, Alignment.CenterVertically) {
                        Row(
                            Modifier.glass(RoundedCornerShape(50), 0.22f)
                                .clickable { overlay = Overlay.Search }
                                .padding(horizontal = 26.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Search, null, tint = Color.White, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Search", color = Color.White, fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        AssistantOrb(34.dp, Modifier.noRipple { overlay = Overlay.Assistant })
                    }
                }
            }
            Dock(vm)
        }

        AnimatedVisibility(
            overlay == Overlay.Control,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 }
        ) { ControlCenter { overlay = Overlay.None } }

        AnimatedVisibility(
            overlay == Overlay.Notifs,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 }
        ) { NotificationPanel { overlay = Overlay.None } }

        AnimatedVisibility(overlay == Overlay.Search, enter = fadeIn(), exit = fadeOut()) {
            SearchOverlay(vm.apps) { overlay = Overlay.None }
        }

        AnimatedVisibility(overlay == Overlay.Assistant, enter = fadeIn() + slideInVertically { it / 6 }, exit = fadeOut()) {
            AssistantOverlay(assistant, vm.apps) { overlay = Overlay.None }
        }

        val cat = category
        AnimatedVisibility(cat != null, enter = fadeIn(), exit = fadeOut()) {
            CategorySheet(cat ?: "", groups.firstOrNull { it.name == cat }?.apps ?: emptyList(), vm) { category = null }
        }

        AnimatedVisibility(showPicker, enter = fadeIn(), exit = fadeOut()) {
            WidgetPicker(widgets) { showPicker = false }
        }

        // iOS status bar: time, SIM signal + data type, Wi-Fi, battery %
        IosStatusBar(Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun GlassPill(text: String, onClick: () -> Unit) {
    Box(
        Modifier.glass(RoundedCornerShape(50), 0.26f).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) { Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun TodayPage(widgets: WidgetController, onAddWidget: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Today View", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, style = ShadowStyle)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(if (editing) "Done" else "Edit") { editing = !editing }
                GlassPill("+ Widget", onAddWidget)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ClockWidget(Modifier.weight(1f).aspectRatio(1f))
            BatteryWidget(Modifier.weight(1f).aspectRatio(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CalendarWidget(Modifier.weight(1f).aspectRatio(1f))
            StorageWidget(Modifier.weight(1f).aspectRatio(1f))
        }
        HostedWidgets(widgets, editing)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun HomeGrid(apps: List<AppEntry>, vm: LauncherViewModel, iconSize: Dp, rowH: Dp, withWidgets: Boolean = false) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        if (withWidgets) {
            HomePanel(Modifier.fillMaxWidth().height((rowH * 2 - 12.dp).coerceAtLeast(160.dp)).padding(horizontal = 4.dp))
            Spacer(Modifier.height(12.dp))
        }
        apps.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().height(rowH)) {
                row.forEach { app ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { AppCell(app, vm, iconSize) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun AppCell(app: AppEntry, vm: LauncherViewModel, size: Dp = 60.dp, showLabel: Boolean = true) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val coordsRef = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var menu by remember { mutableStateOf(false) }
    var shortcuts by remember { mutableStateOf<List<ShortcutInfo>>(emptyList()) }
    val pinned = app.pkg in vm.dock
    val removed = app.pkg in vm.hidden
    Box {
        Column(
            Modifier.width(size + 16.dp)
                .combinedClickable(
                    onClick = {
                        // iOS-style: the app zooms out of the icon you tapped
                        val c = coordsRef[0]
                        val opts = if (c != null && c.isAttached) {
                            val p = c.positionInRoot()
                            ActivityOptions.makeScaleUpAnimation(view, p.x.toInt(), p.y.toInt(), c.size.width, c.size.height).toBundle()
                        } else null
                        ctx.launchApp(app, opts)
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        shortcuts = ctx.shortcutsFor(app.pkg)
                        menu = true
                    }
                )
                .padding(top = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppIcon(app, size, Modifier.onGloballyPositioned { coordsRef[0] = it })
            if (showLabel) {
                Text(app.shownLabel, color = Color.White, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 5.dp), style = ShadowStyle)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            shortcuts.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.shortLabel?.toString() ?: "Shortcut") },
                    onClick = { menu = false; ctx.startShortcut(s) }
                )
            }
            if (shortcuts.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (pinned) "Remove from Dock" else "Add to Dock") },
                onClick = { menu = false; if (pinned) vm.unpin(app.pkg) else vm.pin(app.pkg) }
            )
            DropdownMenuItem(
                text = { Text("App Info") },
                onClick = {
                    menu = false
                    ctx.safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null)))
                }
            )
            DropdownMenuItem(
                text = { Text(if (removed) "Add to Home Screen" else "Remove from Home Screen") },
                onClick = { menu = false; if (removed) vm.addToHome(app.pkg) else vm.removeFromHome(app.pkg) }
            )
            if (!app.system) {
                DropdownMenuItem(
                    text = { Text("Uninstall") },
                    onClick = { menu = false; ctx.safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))) }
                )
            }
        }
    }
}

@Composable
fun Dock(vm: LauncherViewModel) {
    val items = vm.dock.mapNotNull { p -> vm.apps.firstOrNull { it.pkg == p } }.take(4)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val icon = ((maxWidth - 28.dp - 24.dp) / 4 * 0.80f).coerceIn(52.dp, 80.dp)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                .glass(RoundedCornerShape(36.dp), 0.28f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { AppCell(it, vm, icon, showLabel = false) }
            if (items.isEmpty()) Spacer(Modifier.height(icon + 6.dp))
        }
    }
}
