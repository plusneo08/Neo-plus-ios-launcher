package com.neo.plus.iosluancher

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GlassSearchField(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onSearch: () -> Unit = {}
) {
    Row(
        modifier.glass(RoundedCornerShape(16.dp), 0.22f).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.weight(1f).then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
            ),
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(hint, color = Color.White.copy(alpha = 0.65f), fontSize = 17.sp)
                    inner()
                }
            }
        )
        if (value.isNotEmpty()) {
            Icon(Icons.Filled.Close, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(20.dp).clickable { onValue("") })
        }
    }
}

@Composable
fun AppRow(app: AppEntry, onDone: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable { ctx.launchApp(app); onDone() }.padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app, 44.dp)
        Spacer(Modifier.width(14.dp))
        Text(app.shownLabel, color = Color.White, fontSize = 17.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = ShadowStyle)
    }
}

@Composable
private fun CategoryCard(group: AppGroup, onOpen: (String) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).glass(RoundedCornerShape(26.dp), 0.22f)
                .clickable { onOpen(group.name) }.padding(12.dp)
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                group.apps.take(4).chunked(2).forEach { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        r.forEach { a ->
                            AppIcon(a, 50.dp, Modifier.clickable { ctx.launchApp(a) })
                        }
                        if (r.size == 1) Spacer(Modifier.size(50.dp))
                    }
                }
            }
        }
        Text(group.name, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp), maxLines = 1, style = ShadowStyle)
    }
}

@Composable
fun LibraryPage(apps: List<AppEntry>, groups: List<AppGroup>, onOpenCategory: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("App Library", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
            style = ShadowStyle, modifier = Modifier.padding(vertical = 8.dp))
        GlassSearchField(q, { q = it }, "App Library", Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        if (q.isBlank()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(groups.chunked(2)) { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        pair.forEach { g -> CategoryCard(g, onOpenCategory, Modifier.weight(1f)) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            val results = apps.filter { it.matches(q) }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(results, key = { it.pkg + "/" + it.activity }) { a -> AppRow(a) { q = "" } }
            }
        }
    }
}

@Composable
fun CategorySheet(name: String, apps: List<AppEntry>, vm: LauncherViewModel, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).consumeTouches().noRipple(onClose),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth().heightIn(max = 560.dp)
                .glass(RoundedCornerShape(32.dp), 0.30f).noRipple { }.padding(16.dp)
        ) {
            Text(name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 10.dp))
            LazyVerticalGrid(GridCells.Fixed(4), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(apps, key = { it.pkg + "/" + it.activity }) { a ->
                    Box(contentAlignment = Alignment.TopCenter) { AppCell(a, vm, 54.dp) }
                }
            }
        }
    }
}

@Composable
fun SearchOverlay(apps: List<AppEntry>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { fr.requestFocus() } catch (e: Exception) { } }
    val results = remember(q, apps) {
        if (q.isBlank()) emptyList()
        else apps.filter { it.matches(q) }.sortedBy { !it.label.startsWith(q, ignoreCase = true) }
    }
    fun webSearch() {
        ctx.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        onClose()
    }
    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).consumeTouches()
            .windowInsetsPadding(safeInsets()).imePadding().padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassSearchField(q, { q = it }, "Search apps & web", Modifier.weight(1f), fr) {
                val first = results.firstOrNull()
                if (first != null) { ctx.launchApp(first); onClose() } else if (q.isNotBlank()) webSearch()
            }
            Text("Cancel", color = Color(0xFF7DB8FF), fontSize = 16.sp,
                modifier = Modifier.clickable { onClose() }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn {
            items(results, key = { it.pkg + "/" + it.activity }) { a -> AppRow(a, onClose) }
            if (q.isNotBlank()) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { webSearch() }.padding(vertical = 14.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(14.dp))
                        Text("Search the web for \"$q\"", color = Color.White, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
