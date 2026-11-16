package com.virexdl.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable internal fun QueueEditor(songs: List<Song>, keys: List<String>, current: Int,
    onJump: (Int) -> Unit, onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier, header: @Composable () -> Unit = {}) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val latestKeys by rememberUpdatedState(keys)
    val latestIndex by rememberUpdatedState(current)
    val latestMove by rememberUpdatedState(onMove)
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dropKey by remember { mutableStateOf<String?>(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    fun handle(key: String): Modifier = Modifier.testTag("queue-handle-$key").pointerInput(key) {
        detectDragGesturesAfterLongPress(onDragStart = {
            val row = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "track:$key" }
            if (latestKeys.indexOf(key) > latestIndex && row != null) {
                draggingKey = key; dropKey = key; fingerY = row.offset + row.size / 2f
            }
        }, onDragEnd = {
            val from = latestKeys.indexOf(draggingKey)
            val to = latestKeys.indexOf(dropKey)
            draggingKey = null; dropKey = null
            if (from > latestIndex && to > latestIndex && from != to) latestMove(from, to)
        }, onDragCancel = { draggingKey = null; dropKey = null }, onDrag = { change, amount ->
            val selected = draggingKey
            if (selected != null) {
                change.consume(); fingerY += amount.y
                val over = state.layoutInfo.visibleItemsInfo.firstOrNull { fingerY.toInt() in it.offset..(it.offset + it.size) }?.key as? String
                val from = latestKeys.indexOf(selected)
                val to = if (over?.startsWith("track:") == true) latestKeys.indexOf(over.removePrefix("track:")) else -1
                if (from > latestIndex && to > latestIndex) dropKey = latestKeys[to]
                if (fingerY > state.layoutInfo.viewportEndOffset - 48.dp.toPx()) scope.launch { state.scrollBy(24f) }
                else if (fingerY < 48.dp.toPx()) scope.launch { state.scrollBy(-24f) }
            }
        })
    }
    LazyColumn(state = state, modifier = modifier.testTag("editable-queue"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "header") { header() }
        if (songs.isNotEmpty()) item(key = "now-label") { Text("Now playing", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
        songs.getOrNull(current)?.let { song -> item(key = "now-playing") { QueueTrack(song, true, { onJump(current) }, null, null) } }
        item(key = "up-next") { Text("Up next · hold the handle to reorder", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp)) }
        if (current >= songs.lastIndex) item(key = "empty") { Text("No upcoming songs yet. Add a song or start Virex Radio.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        for (i in (current + 1).coerceAtLeast(0) until songs.size) {
            val key = keys.getOrElse(i) { "${songs[i].id}:$i" }
            item(key = "track:$key") {
                QueueTrack(songs[i], draggingKey == key || dropKey == key, { onJump(i) }, { onRemove(i) }, Modifier.testTag("queue-entry-$key").semantics {
                    stateDescription = if (draggingKey == key) "Reordering" else if (dropKey == key) "Drop target" else "Queued"
                    customActions = listOf(CustomAccessibilityAction("Move up") { if (i > current + 1) onMove(i, i - 1); true }, CustomAccessibilityAction("Move down") { if (i < songs.lastIndex) onMove(i, i + 1); true })
                }, handle(key))
            }
        }
    }
}
@Composable private fun QueueTrack(song: Song, selected: Boolean, play: () -> Unit, remove: (() -> Unit)?, extra: Modifier?, handle: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else Color.White.copy(alpha = .03f)) {
        Row((extra ?: Modifier).fillMaxWidth().clickable(onClick = play).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(song.image, "${song.title} artwork", Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.DarkGray), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (remove != null) {
                Icon(Icons.Rounded.DragIndicator, "Hold and drag to reorder ${song.title}", Modifier.size(32.dp).then(handle), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = remove) { Icon(Icons.Rounded.Close, "Remove ${song.title} from queue") }
            } else Icon(Icons.Rounded.GraphicEq, "Now playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
        }
    }
}
