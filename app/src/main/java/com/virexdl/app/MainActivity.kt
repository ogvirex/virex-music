package com.virexdl.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.work.WorkInfo.State
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import java.io.File

private val Ink = Color(0xFF050605)
private val Panel = Color(0xFF1B2119)
private val Muted = Color(0xFFC0C8BC)
private val Soft = RoundedCornerShape(24.dp)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VirexRoot() }
    }
}

@Composable fun VirexRoot(vm: MusicViewModel = viewModel()) {
    val library by vm.library.collectAsStateWithLifecycle()
    val downloadJobs by vm.work.collectAsStateWithLifecycle()
    val song by vm.current.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val buffering by vm.buffering.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val duration by vm.duration.collectAsStateWithLifecycle()
    val accent = Color(library.settings.accent)
    var tab by rememberSaveable { mutableStateOf(0) }
    var full by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var selectedMix by rememberSaveable { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf<Song?>(null) }
    var addSong by remember { mutableStateOf<Song?>(null) }
    var newMix by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Mix?>(null) }
    var exportSong by remember { mutableStateOf<Download?>(null) }
    val snack = remember { SnackbarHostState() }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(full) { if (full) { focus.clearFocus(); keyboard?.hide() } }
    val saveAudio = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/*")) { uri -> if (uri != null) exportSong?.let { vm.exportAudio(it, uri) }; exportSong = null }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.store.edit { it.copy(settings = it.settings.copy(visualizer = granted)) }
        if (!granted) vm.message("Audio visualizer needs Android's audio capture permission")
    }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    BackHandler(enabled = !full && (selectedMix != null || tab != 0)) { if (selectedMix != null) selectedMix = null else tab = 0 }
    fun share(s: Song) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${s.title} — ${s.artist}\nListening on Virex Music") }, "Share song"))
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = accent, onPrimary = Ink, background = Ink, surface = Panel, onSurface = Color(0xFFF3F5ED), onBackground = Color(0xFFF3F5ED), surfaceVariant = Color(0xFF222820), onSurfaceVariant = Muted), typography = Typography()) {
        Scaffold(containerColor = Ink, snackbarHost = { if (!full) SnackbarHost(snack) },
            topBar = {
                Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(Modifier.size(32.dp))
                    Spacer(Modifier.width(10.dp)); Text("Virex", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, letterSpacing = (-1).sp)
                    Spacer(Modifier.weight(1f)); Action(Icons.Rounded.Tune, "Settings", onClick = { settings = true })
                }
            }, bottomBar = {
                Column {
                    AnimatedVisibility(song != null && !full) {
                        song?.let { s -> MiniPlayer(s, playing, buffering, position, duration, accent, { full = true }, vm::togglePlay, vm::next) }
                    }
                    NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
                        listOf("Discover" to Icons.Rounded.Explore, "Search" to Icons.Rounded.Search, "Library" to Icons.AutoMirrored.Rounded.QueueMusic, "Offline" to Icons.Rounded.OfflinePin).forEachIndexed { i, (label, icon) ->
                            NavigationBarItem(selected = tab == i, onClick = { tab = i; selectedMix = null }, icon = { Icon(icon, label) }, label = { Text(label, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(indicatorColor = accent.copy(alpha = .16f), selectedIconColor = accent, selectedTextColor = accent, unselectedIconColor = Muted, unselectedTextColor = Muted))
                        }
                    }
                }
            }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    0 -> Discover(vm, accent, vm::radio, { options = it }, { tab = 3; selectedMix = null })
                    1 -> SearchScreen(vm, library, vm::radio, { options = it })
                    2 -> {
                        val mix = library.mixes.find { it.id == selectedMix }
                        val collection = collectionSongs(library, selectedMix)
                        if (collection != null) CollectionScreen(collection.first, collection.second, { selectedMix = null }, { index -> vm.play(collection.second, index, label = collection.first) }, { options = it })
                        else if (mix == null) PlaylistScreen(library, { selectedMix = it }, { newMix = true }, { importing = true })
                        else MixScreen(mix, library.downloads, downloadJobs, { vm.downloadPlaylist(mix) }, { selectedMix = null }, { vm.play(mix.songs, label = mix.name) }, { deleting = mix }, { index -> vm.play(mix.songs, index, label = mix.name) }, { options = it }, { s -> vm.store.edit { l -> l.copy(mixes = l.mixes.map { if (it.id == mix.id) it.copy(songs = it.songs.filterNot { x -> x.id == s.id }) else it }) } })
                    }
                    3 -> OfflineScreen(vm, library, { vm.play(library.downloads.map(Download::song), it, label = "Offline library") }, { d -> exportSong = d; saveAudio.launch("${d.song.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")}.m4a") })
                }
            }
        }
        if (full && song != null) FullPlayer(vm, song!!, accent, { full = false }, { addSong = song }, { share(song!!) }, snack)
        if (settings) SettingsDialog(library, { settings = false }, { enabled -> if (enabled) audioPermission.launch(Manifest.permission.RECORD_AUDIO) else vm.store.edit { it.copy(settings = it.settings.copy(visualizer = false)) } }, { enabled -> vm.store.edit { it.copy(settings = it.settings.copy(crossfade = enabled)) } }, { c -> vm.store.edit { it.copy(settings = it.settings.copy(accent = c)) } }, { value -> vm.store.edit { it.copy(settings = it.settings.copy(hq = value)) } })
        if (newMix) NameDialog("New playlist", "Give your mix a name", { newMix = false }) { name ->
            vm.store.create(name, addSong?.let { listOf(it) }.orEmpty()); newMix = false; addSong = null
        }
        if (importing) ImportDialog(vm) { importing = false }
        options?.let { s ->
            AlertDialog(onDismissRequest = { options = null }, title = { Text(s.title, maxLines = 2) }, text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Option(if (library.liked.any { it.id == s.id }) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (library.liked.any { it.id == s.id }) "Unlike song" else "Like song") { vm.store.toggleLike(s); options = null }
                    Option(Icons.Rounded.SkipNext, "Play next") { vm.queueSong(s, true); options = null }
                    Option(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { vm.queueSong(s, false); options = null }
                    Option(Icons.Rounded.PlayArrow, "Start radio") { vm.radio(s); options = null }
                    Option(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") { addSong = s; options = null }
                    Option(Icons.Rounded.Download, "Save offline") { vm.download(s); options = null }
                    Option(Icons.Rounded.Share, "Share song") { share(s); options = null }
                }
            }, confirmButton = { TextButton(onClick = { options = null }) { Text("Close") } })
        }
        addSong?.let { s ->
            if (!newMix) AlertDialog(onDismissRequest = { addSong = null }, title = { Text("Add to playlist") }, text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Option(Icons.Rounded.Add, "New playlist") { newMix = true }
                    library.mixes.forEach { m -> Option(Icons.AutoMirrored.Rounded.QueueMusic, m.name) { vm.store.add(m.id, s); addSong = null; vm.message("Added to ${m.name}") } }
                }
            }, confirmButton = { TextButton(onClick = { addSong = null }) { Text("Cancel") } })
        }
        deleting?.let { m -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${m.name}?") }, text = { Text("This removes the playlist from your library.") }, confirmButton = {
            TextButton(onClick = { vm.store.edit { it.copy(mixes = it.mixes.filterNot { p -> p.id == m.id }) }; deleting = null; selectedMix = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    }
}

@Composable private fun Action(icon: ImageVector, label: String, tint: Color = LocalContentColor.current, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = tint) }
}
@Composable private fun Option(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 15.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(16.dp)); Text(label)
    }
}
@Composable private fun Eyebrow(text: String, color: Color = Muted) { Text(text.uppercase(), color = color, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
@Composable private fun Art(song: Song?, modifier: Modifier = Modifier, local: String? = null) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Color(0xFF344332), Color(0xFF1A221B)))), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.GraphicEq, null, tint = Color(0xFF6C8261), modifier = Modifier.size(40.dp))
        AsyncImage(model = local?.takeIf(String::isNotBlank)?.let(::File) ?: song?.image, contentDescription = song?.let { "${it.title} artwork" }, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun SongCard(song: Song, play: () -> Unit, options: () -> Unit) {
    Column(Modifier.clip(Soft).background(Panel).combinedClickable(onClick = play, onLongClick = options).padding(10.dp)) {
        Box {
            Art(song, Modifier.fillMaxWidth().aspectRatio(1f))
            Surface(color = Ink.copy(alpha = .7f), shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(34.dp)) {
                IconButton(onClick = play) { Icon(Icons.Rounded.PlayArrow, "Play ${song.title}", modifier = Modifier.size(22.dp)) }
            }
        }
        Text(song.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp, start = 3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(song.artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 3.dp))
            IconButton(onClick = options, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.MoreHoriz, "Options for ${song.title}", modifier = Modifier.size(20.dp)) }
        }
    }
}
@Composable private fun Discover(vm: MusicViewModel, accent: Color, play: (Song) -> Unit, options: (Song) -> Unit, offlineLibrary: () -> Unit) {
    val songs by vm.home.collectAsStateWithLifecycle()
    val category by vm.category.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val loading by vm.homeLoading.collectAsStateWithLifecycle()
    val error by vm.homeError.collectAsStateWithLifecycle()
    LazyVerticalGrid(columns = GridCells.Adaptive(148.dp), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                Text("Listen now", fontSize = 36.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, modifier = Modifier.padding(top = 10.dp))
                Text(if (online) "Pick a song. Virex Radio keeps it going." else "Listen to your saved music.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 12.dp))
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            if (online) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(categories.keys.toList()) { c -> FilterChip(selected = category == c, onClick = { vm.refreshHome(c) }, label = { Text(c) }, shape = CircleShape, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Ink)) } }
        }
        if (online && songs.isNotEmpty() && !loading && error == null) item(span = { GridItemSpan(maxLineSpan) }) {
            val featured = songs.first()
            GlassPanel(Modifier.fillMaxWidth().height(190.dp), art = featured, onClick = { play(featured) }) {
                Art(featured, Modifier.size(158.dp).align(Alignment.CenterEnd).padding(12.dp))
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .62f), Color.Black.copy(alpha = .38f), Color.Transparent))))
                Column(Modifier.padding(20.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow("The daily frequency", accent)
                    Text("$category\nRadio", fontSize = 27.sp, lineHeight = 29.sp, fontWeight = FontWeight.ExtraBold)
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.PlayCircle, null, tint = accent); Spacer(Modifier.width(8.dp)); Text("Press play. Follow the feeling.", fontSize = 10.sp, color = Muted) }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            if (online) Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$category mix", fontWeight = FontWeight.Medium, fontSize = 22.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.refreshHome() }) { Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Refresh", fontSize = 12.sp) }
            }
        }
        if (!online) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(Icons.Rounded.CloudOff, "You're offline", "Your saved music is ready to play.", "Open offline library", offlineLibrary) }
        else if (loading) items(6) { Skeleton() }
        else if (error != null) item(span = { GridItemSpan(maxLineSpan) }) { Column {
            EmptyState(Icons.Rounded.CloudOff, "Couldn't load music", "Try again or listen to your saved songs.", "Try again") { vm.refreshHome() }
            TextButton(onClick = offlineLibrary, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Open offline library") }
        } }
        else items(songs, key = Song::id) { s -> SongCard(s, { play(s) }, { options(s) }) }
    }
}
@Composable private fun Skeleton() {
    Column(Modifier.clip(Soft).background(Panel).padding(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .04f)))
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 18.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .3f), trackColor = Panel)
        Spacer(Modifier.height(24.dp))
    }
}
@Composable private fun SearchScreen(vm: MusicViewModel, library: Library, play: (Song) -> Unit, options: (Song) -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle()
    val songs by vm.search.collectAsStateWithLifecycle()
    val loading by vm.searchLoading.collectAsStateWithLifecycle()
    val error by vm.searchError.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("Search", fontWeight = FontWeight.ExtraBold, fontSize = 36.sp, modifier = Modifier.padding(vertical = 16.dp))
        OutlinedTextField(query, { vm.searchQuery(it) }, modifier = Modifier.fillMaxWidth(), shape = CircleShape, singleLine = true, placeholder = { Text("Songs, artists, a feeling…", fontSize = 14.sp) }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotEmpty()) Action(Icons.Rounded.Close, "Clear search") { vm.searchQuery("") } }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.searchQuery(query, true) }))
        Spacer(Modifier.height(18.dp))
        if (query.isBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) { Eyebrow("Recent searches"); Spacer(Modifier.weight(1f)); TextButton(onClick = { vm.store.edit { it.copy(history = emptyList()) } }) { Text("Clear all", fontSize = 12.sp) } }
            LazyColumn {
                items(library.history) { q -> Row(Modifier.fillMaxWidth().clickable { vm.searchQuery(q, true) }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.History, null, tint = Muted); Text(q, modifier = Modifier.weight(1f).padding(16.dp)); Action(Icons.Rounded.Close, "Remove $q") { vm.store.edit { it.copy(history = it.history - q) } }
                } }
                item { if (library.history.isEmpty()) EmptyState(Icons.Rounded.Search, "Your next favorite is here", "Search for a song or artist to start listening.") }
            }
        } else if (error != null) EmptyState(Icons.Rounded.CloudOff, "Couldn't search", error!!, "Retry") { vm.searchQuery(query, true) }
        else if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Spacer(Modifier.height(12.dp)); Text("Tuning in…", color = Muted) }
        else if (songs.isEmpty() && query.length >= 2) EmptyState(Icons.Rounded.MusicOff, "No matches", "Try a different song or artist.")
        else LazyVerticalGrid(GridCells.Adaptive(148.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) { items(songs, key = Song::id) { s -> SongCard(s, { play(s) }, { options(s) }) } }
    }
}
@Composable private fun PlaylistScreen(library: Library, openMix: (String) -> Unit, create: () -> Unit, importMix: () -> Unit) {
    LazyVerticalGrid(GridCells.Adaptive(148.dp), contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column { Eyebrow("Made by you"); Text("Your library", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold); Row { FilledTonalButton(onClick = create) { Icon(Icons.Rounded.Add, null); Text("New mix") }; Spacer(Modifier.width(10.dp)); OutlinedButton(onClick = importMix) { Icon(Icons.Rounded.FileUpload, null); Text("Import") } } } }
        item(span = { GridItemSpan(maxLineSpan) }) { Column {
            listOf("Liked songs" to "@liked", "Recently played" to "@recent", "Most played" to "@most").forEach { (name, id) ->
                val count = collectionSongs(library, id)?.second?.size ?: 0
                OutlinedButton(onClick = { openMix(id) }, modifier = Modifier.fillMaxWidth()) { Text("$name · $count", modifier = Modifier.weight(1f)); Icon(Icons.Rounded.ChevronRight, null) }
            }
        } }
        if (library.mixes.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "Every mood needs a mix", "Create a playlist, then add songs from any track's menu.", "Create playlist", create) }
        items(library.mixes, key = Mix::id) { m -> Column(Modifier.clip(Soft).background(Panel).clickable { openMix(m.id) }.padding(12.dp)) {
            Art(m.songs.firstOrNull(), Modifier.fillMaxWidth().aspectRatio(1f)); Text(m.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp)); Text("${m.songs.size} tracks", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        } }
    }
}
@Composable private fun MixScreen(mix: Mix, downloads: List<Download>, jobs: List<WorkInfo>, download: () -> Unit, back: () -> Unit, play: () -> Unit, delete: () -> Unit, playAt: (Int) -> Unit, options: (Song) -> Unit, remove: (Song) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Action(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = back); Spacer(Modifier.weight(1f)); Action(Icons.Rounded.DeleteOutline, "Delete playlist", onClick = delete) } }
        item { Art(mix.songs.firstOrNull(), Modifier.size(180.dp)); Spacer(Modifier.height(20.dp)); Eyebrow("Your own frequency"); Text(mix.name, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold); Text("${mix.songs.size} tracks", color = Muted); Button(onClick = play, enabled = mix.songs.isNotEmpty(), modifier = Modifier.padding(vertical = 12.dp)) { Icon(Icons.Rounded.PlayArrow, null); Text("Play mix") } }
        item {
            val tracks = mix.songs.distinctBy(Song::id)
            val saved = tracks.count { song -> downloads.any { it.song.id == song.id } }
            val pending = tracks.count { song -> downloads.none { it.song.id == song.id } && jobs.any { !it.state.isFinished && "song:${song.id}" in it.tags } }
            val complete = tracks.isNotEmpty() && saved == tracks.size
            OutlinedButton(onClick = download, enabled = tracks.size > saved + pending, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("download-playlist")) {
                Icon(if (complete) Icons.Rounded.CheckCircle else Icons.Rounded.Download, null)
                Spacer(Modifier.width(8.dp))
                Text(if (complete) "Saved offline" else if (pending > 0 && saved + pending == tracks.size) "Downloads queued" else "Download all")
            }
            if (tracks.isNotEmpty()) Text("$saved of ${tracks.size} saved offline" + if (pending > 0) " · $pending downloading or queued" else "", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        }
        itemsIndexed(mix.songs, key = { _, s -> s.id }) { i, s -> SongRow(s, { playAt(i) }, { options(s) }, trailing = { Action(Icons.Rounded.RemoveCircleOutline, "Remove ${s.title}") { remove(s) } }) }
    }
}
@Composable private fun OfflineScreen(vm: MusicViewModel, library: Library, play: (Int) -> Unit, export: (Download) -> Unit) {
    val jobs by vm.work.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Eyebrow("No signal. No problem."); Text("Offline vault", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold); Text("${library.downloads.size} tracks · %.1f MB".format(library.downloads.sumOf(Download::bytes) / 1048576.0), color = Muted, fontSize = 12.sp); if (library.downloads.isNotEmpty()) Button(onClick = { play(0) }, modifier = Modifier.padding(vertical = 12.dp)) { Icon(Icons.Rounded.PlayArrow, null); Text("Play all") } }
        items(jobs.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.FAILED }, key = { it.id }) { job ->
            Surface(shape = RoundedCornerShape(16.dp), color = Panel) { Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when (job.state) { WorkInfo.State.RUNNING -> "Downloading · ${job.progress.getInt("percent", 0)}%"; WorkInfo.State.FAILED -> "Download failed: ${job.outputData.getString("error") ?: "Try again from the song menu"}"; else -> "Waiting for connection…" }, modifier = Modifier.weight(1f), fontSize = 12.sp)
                    Action(Icons.Rounded.Close, "Dismiss download") { if (!job.state.isFinished) vm.workManager.cancelWorkById(job.id) else vm.workManager.pruneWork() }
                }
                if (job.state == WorkInfo.State.RUNNING) LinearProgressIndicator(progress = { job.progress.getInt("percent", 0) / 100f }, modifier = Modifier.fillMaxWidth())
            } }
        }
        if (library.downloads.isEmpty()) item { EmptyState(Icons.Rounded.OfflinePin, "Keep the feeling", "Save songs from the player or song menu. They'll stay playable here without internet.") }
        itemsIndexed(library.downloads, key = { _, d -> d.song.id }) { i, d ->
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Panel).padding(6.dp)) {
                SongRow(d.song, { play(i) }, { export(d) }, local = d.artwork, trailing = { Action(Icons.Rounded.DeleteOutline, "Delete offline copy") { vm.store.removeDownload(d.song.id) } })
                TextButton(onClick = { export(d) }, modifier = Modifier.padding(start = 56.dp)) { Text("Save audio to device", fontSize = 11.sp) }
            }
        }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun SongRow(song: Song, play: () -> Unit, options: () -> Unit, local: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = play, onLongClick = options).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(song, Modifier.size(52.dp), local); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(song.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp); Text(song.artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
        if (trailing != null) trailing() else Action(Icons.Rounded.MoreHoriz, "Song options", onClick = options)
    }
}
@Composable private fun EmptyState(icon: ImageVector, title: String, body: String, button: String? = null, action: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 42.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp)); Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp)); Text(body, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)); if (button != null) TextButton(onClick = action) { Text(button) }
    }
}
@Composable private fun MiniPlayer(song: Song, playing: Boolean, buffering: Boolean, position: Long, duration: Long, accent: Color, open: () -> Unit, toggle: () -> Unit, next: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), art = song, onClick = open) {
        Column {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(song, Modifier.size(44.dp)); Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(song.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(song.artist, fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (buffering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Action(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", onClick = toggle)
                Action(Icons.Rounded.SkipNext, "Next", onClick = next)
            }
            LinearProgressIndicator(progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = accent, trackColor = Color.Transparent)
        }
    }
}

@Composable private fun BrandMark(modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.virex_logo), "Virex logo", Modifier.fillMaxSize().graphicsLayer(scaleX = 1.65f, scaleY = 1.65f), contentScale = ContentScale.Crop)
    }
}

@Composable private fun SignalHalo(song: Song, progress: Float, local: String?, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        val accent = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f - 7.dp.toPx()
            repeat(72) { i ->
                val angle = Math.toRadians((i * 5 - 90).toDouble())
                val inner = radius - if (i % 6 == 0) 9.dp.toPx() else 4.dp.toPx()
                drawLine(if (i / 72f < progress) accent.copy(alpha = .8f) else Color.White.copy(alpha = .14f),
                    Offset(center.x + cos(angle).toFloat() * inner, center.y + sin(angle).toFloat() * inner),
                    Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
            }
            drawArc(accent.copy(alpha = .6f), -90f, progress * 360f, false, topLeft = Offset(19.dp.toPx(), 19.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width - 38.dp.toPx(), size.height - 38.dp.toPx()), style = Stroke(1.dp.toPx()))
        }
        Art(song, Modifier.fillMaxSize(.76f), local)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FullPlayer(vm: MusicViewModel, song: Song, accent: Color, close: () -> Unit, add: () -> Unit, share: () -> Unit, snack: SnackbarHostState) {
    val playing by vm.playing.collectAsStateWithLifecycle()
    val buffering by vm.buffering.collectAsStateWithLifecycle()
    val pos by vm.position.collectAsStateWithLifecycle()
    val duration by vm.duration.collectAsStateWithLifecycle()
    val repeat by vm.repeat.collectAsStateWithLifecycle()
    val shuffle by vm.shuffled.collectAsStateWithLifecycle()
    val label by vm.contextLabel.collectAsStateWithLifecycle()
    val audioSession by vm.audioSession.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val work by vm.work.collectAsStateWithLifecycle()
    val activeDownload = work.firstOrNull { "song:${song.id}" in it.tags && !it.state.isFinished }
    val failedDownload = work.firstOrNull { "song:${song.id}" in it.tags && it.state == WorkInfo.State.FAILED }
    val offline = library.downloads.find { it.song.id == song.id }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember(song.id) { mutableStateOf<Float?>(null) }
    val clipboard = LocalClipboardManager.current
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val radioStatus by vm.radioStatus.collectAsStateWithLifecycle()
    LaunchedEffect(page, song.id) { if (page == 1 && lyrics.lines.isEmpty() && !vm.lyricsLoading.value) vm.retryLyrics() }
    Box(Modifier.fillMaxSize().zIndex(100f)) {
        val dismissGesture = Modifier.pointerInput(Unit) {
            detectVerticalDragGestures(onDragEnd = { if (swipeOffset > 80.dp.toPx()) close(); swipeOffset = 0f }, onDragCancel = { swipeOffset = 0f }, onVerticalDrag = { change, amount ->
                if (amount > 0 || swipeOffset > 0) { swipeOffset = (swipeOffset + amount).coerceAtLeast(0f); change.consume() }
            })
        }
        BackHandler { if (page != 0) page = 0 else close() }
        Surface(Modifier.fillMaxSize().graphicsLayer(translationY = swipeOffset).then(if (page == 0) dismissGesture else Modifier).testTag("full-player"), color = Ink, contentColor = Color(0xFFF3F5ED)) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(song.image, null, Modifier.matchParentSize().blur(48.dp).graphicsLayer(alpha = .16f, scaleX = 1.2f, scaleY = 1.2f), contentScale = ContentScale.Crop)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0xE61B1D24), Color(0xF207080B), Ink))))
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = if (page == 0) 88.dp else 0.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).then(if (page != 0) dismissGesture else Modifier).testTag("player-dismiss-handle").padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Action(Icons.Rounded.KeyboardArrowDown, "Close player", onClick = close)
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Eyebrow("VIREX / ${when (page) { 1 -> "LYRICS"; 2 -> "UP NEXT"; else -> "NOW PLAYING" }}", accent)
                            Text(label, fontSize = 11.sp, lineHeight = 14.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                        }
                        Action(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", onClick = add)
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).height(54.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        listOf("Player" to Icons.Rounded.Album, "Lyrics" to Icons.Rounded.Mic, "Queue" to Icons.AutoMirrored.Rounded.QueueMusic).forEachIndexed { i, (name, icon) ->
                            TextButton(onClick = { page = i }, modifier = Modifier.weight(1f).height(48.dp).testTag("player-tab-$i"), contentPadding = PaddingValues(horizontal = 4.dp), colors = ButtonDefaults.textButtonColors(contentColor = if (page == i) accent else Muted)) {
                                Icon(icon, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(name, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    when (page) {
                        0 -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
                            val compact = maxHeight < 420.dp || LocalDensity.current.fontScale > 1.4f
                            val compactArtHeight = minOf(190.dp, (maxHeight - 260.dp).coerceAtLeast(72.dp))
                            val scroll = rememberScrollState()
                            Column(if (compact) Modifier.fillMaxSize().verticalScroll(scroll) else Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                                val artModifier = if (compact) Modifier.height(compactArtHeight).fillMaxWidth() else Modifier.weight(1f).fillMaxWidth()
                                Box(artModifier, contentAlignment = Alignment.Center) {
                                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        val diameter = minOf(maxWidth, maxHeight, 380.dp)
                                        SignalHalo(song, if (duration > 0) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f, offline?.artwork,
                                            Modifier.size(diameter).pointerInput(song.id) {
                                                var distance = 0f
                                                detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragEnd = {
                                                    if (distance > 120f) vm.previous() else if (distance < -120f) vm.next()
                                                }, onHorizontalDrag = { change, amount -> change.consume(); distance += amount })
                                            })
                                    }
                                }
                                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title, color = Color.White, fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("player-title"))
                                        Text(song.artist, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                                    }
                                    Action(if (library.liked.any { it.id == song.id }) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (library.liked.any { it.id == song.id }) "Unlike song" else "Like song", tint = accent) { vm.store.toggleLike(song) }
                                    Action(Icons.Rounded.Share, "Share song", tint = Muted, onClick = share)
                                }
                                Slider(value = dragging ?: pos.toFloat(), onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { vm.seek(it.toLong()) }; dragging = null }, valueRange = 0f..duration.toFloat().coerceAtLeast(1f), enabled = duration > 0, modifier = Modifier.fillMaxWidth().height(42.dp).testTag("player-seek"), thumb = { Box(Modifier.size(12.dp).background(accent, CircleShape)) }, track = {
                                    val ratio = if (duration > 0) ((dragging ?: pos.toFloat()) / duration).coerceIn(0f, 1f) else 0f
                                    Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = .14f))) { Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().background(accent)) }
                                }, colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color.White.copy(alpha = .12f)))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(clock(dragging?.toLong() ?: pos), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted)
                                    Text(clock(duration), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted)
                                }
                                Row(Modifier.fillMaxWidth().height(88.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Action(Icons.Rounded.Shuffle, "Shuffle", if (shuffle) accent else Muted, vm::shuffle)
                                    Action(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, onClick = vm::previous)
                                    FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.size(66.dp).testTag("player-play"), colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = Ink)) {
                                        if (buffering) CircularProgressIndicator(Modifier.size(25.dp), color = Ink) else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(32.dp))
                                    }
                                    Action(Icons.Rounded.SkipNext, "Next", tint = Color.White, onClick = vm::next)
                                    Action(if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", if (repeat != Player.REPEAT_MODE_OFF) accent else Muted, vm::cycleRepeat)
                                }
                                if (library.settings.visualizer) AudioVisualizer(audioSession, accent, playing)
                            }
                        }
                        1 -> Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(enabled = lyrics.lines.isNotEmpty(), onClick = { clipboard.setText(AnnotatedString(lyrics.lines.joinToString("\n") { it.text })); vm.message("Lyrics copied") }) { Text("Copy lyrics") }
                                TextButton(onClick = share) { Text("Share song") }
                            }
                            LyricsView(vm, pos, accent)
                        }
                        2 -> {
                            val queue by vm.queue.collectAsStateWithLifecycle()
                            val index by vm.queueIndex.collectAsStateWithLifecycle()
                            val keys by vm.queueKeys.collectAsStateWithLifecycle()
                            QueueEditor(queue, keys, index, vm::jump, vm::moveQueued, vm::removeQueued, Modifier.weight(1f)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("Virex Radio", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent)
                                    if (shuffle) { Text("Shuffle is on. Manual ordering turns it off.", color = Muted, fontSize = 11.sp); TextButton(onClick = vm::shuffle) { Text("Use queue order") } }
                                    Text(radioStatus.ifBlank { "Start radio to automatically add more songs." }, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
                                    TextButton(onClick = { vm.radio(song) }) { Text("${if (label == "Virex Radio") "Refresh" else "Start"} Virex Radio") }
                                }
                            }
                        }
                    }


                }
                    if (page == 0) {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).padding(horizontal = 24.dp, vertical = 12.dp)) {
                            GlassPanel {
                                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { vm.download(song) }, enabled = offline == null && activeDownload == null, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("save-offline"), colors = ButtonDefaults.textButtonColors(contentColor = accent, disabledContentColor = if (offline != null) accent else Muted)) {
                                        Icon(if (offline != null) Icons.Rounded.CheckCircle else Icons.Rounded.Download, null, Modifier.size(19.dp)); Spacer(Modifier.width(10.dp))
                                        Text(when { offline != null -> "Saved to offline vault"; activeDownload?.state == WorkInfo.State.RUNNING -> "Saving · ${activeDownload.progress.getInt("percent", 0)}%"; activeDownload != null -> "Queued · waiting for connection"; failedDownload != null -> "Retry offline save"; else -> "Save offline" }, fontSize = 13.sp)
                                    }
                                    activeDownload?.let { job -> Action(Icons.Rounded.Close, "Cancel download", tint = Muted) { vm.workManager.cancelWorkById(job.id) } }
                                }
                            }
                            if (failedDownload != null && activeDownload == null && offline == null) Text(failedDownload.outputData.getString("error") ?: "Couldn't save. Check your connection and retry.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 130.dp))
            }
        }
    }
}
@Composable private fun LyricsView(vm: MusicViewModel, pos: Long, accent: Color) {
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val loading by vm.lyricsLoading.collectAsStateWithLifecycle()
    val error by vm.lyricsError.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    val synced = lyrics.lines.firstOrNull()?.millis?.let { it >= 0 } == true
    val active = if (synced) lyrics.lines.indexOfLast { it.millis <= pos } else -1
    LaunchedEffect(active) { if (active >= 0 && !state.isScrollInProgress) state.animateScrollToItem((active - 2).coerceAtLeast(0)) }
    if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("Finding lyrics…", color = Muted, modifier = Modifier.padding(top = 16.dp)) } }
    else if (lyrics.lines.isEmpty()) EmptyState(Icons.Rounded.MicOff, if (lyrics.instrumental) "Instrumental" else "Lyrics", error ?: if (lyrics.instrumental) "This track has no vocals." else "No lyrics found for this song yet.", if (lyrics.instrumental) null else "Find lyrics", vm::retryLyrics)
    else LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        itemsIndexed(lyrics.lines) { i, line -> Text(line.text, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = if (!synced || active == i) Color.White else if (i < active) Color.White.copy(alpha = .22f) else Muted, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (synced && active == i) accent.copy(alpha = .12f) else Color.Transparent).clickable(enabled = synced) { vm.seek(line.millis) }.padding(12.dp)) }
    }
}
private fun clock(ms: Long): String { val seconds = ms.coerceAtLeast(0) / 1000; return "%d:%02d".format(seconds / 60, seconds % 60) }
@Composable private fun NameDialog(title: String, placeholder: String, close: () -> Unit, submit: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { OutlinedTextField(name, { name = it.take(120) }, placeholder = { Text(placeholder) }, singleLine = true) }, confirmButton = { TextButton(onClick = { submit(name.trim().ifBlank { "My mix" }) }) { Text("Create") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}
@Composable private fun ImportDialog(vm: MusicViewModel, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    val progress by vm.importProgress.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest = { if (progress == null) close() }, title = { Text("Bring your music") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Paste one song per line. We’ll find the matching tracks.", color = Muted, fontSize = 13.sp)
            OutlinedTextField(name, { name = it.take(120) }, label = { Text("Playlist name") }, enabled = progress == null, modifier = Modifier.padding(top = 12.dp))
            OutlinedTextField(text, { text = it.take(200_000) }, label = { Text("Artist — Song") }, minLines = 5, maxLines = 8, enabled = progress == null)
            if (progress != null) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 14.dp)); Text(progress!!, fontSize = 12.sp) }
        }
    }, confirmButton = { TextButton(enabled = progress == null && text.isNotBlank(), onClick = { vm.importLines(name, text) }) { Text("Import") } }, dismissButton = { TextButton(onClick = { vm.cancelImport(); close() }) { Text(if (progress != null) "Cancel import" else "Close") } })
}
@Composable private fun SettingsDialog(library: Library, close: () -> Unit, visualizer: (Boolean) -> Unit, crossfade: (Boolean) -> Unit, accent: (Long) -> Unit, quality: (Boolean) -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 700.dp), shape = RoundedCornerShape(28.dp), color = Panel) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Make it yours", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Action(Icons.Rounded.Close, "Close settings", onClick = close) }
                Spacer(Modifier.height(26.dp)); Eyebrow("Accent color")
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(0xFFB8FF3D, 0xFF55B9FF, 0xFFFF65BD, 0xFFB18AFF, 0xFFFFBF45, 0xFF42F5D7).forEach { c ->
                        Surface(onClick = { accent(c) }, shape = CircleShape, color = Color(c), border = if (library.settings.accent == c) BorderStroke(3.dp, Color.White) else null, modifier = Modifier.size(34.dp)) { if (library.settings.accent == c) Icon(Icons.Rounded.Check, "Selected accent", tint = Ink, modifier = Modifier.padding(7.dp)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("High quality", fontWeight = FontWeight.Bold); Text("Prefer 320 kbps when available", fontSize = 11.sp, color = Muted) }; Switch(checked = library.settings.hq, onCheckedChange = quality) }
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Crossfade", fontWeight = FontWeight.Bold); Text("Smooth transitions between songs", fontSize = 11.sp, color = Muted) }; Switch(library.settings.crossfade, crossfade) }
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Audio visualizer", fontWeight = FontWeight.Bold); Text("Uses Android audio capture permission", fontSize = 11.sp, color = Muted) }; Switch(library.settings.visualizer, visualizer) }
                Text("Quality changes apply to newly started songs and downloads.", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 12.dp))
                HorizontalDivider(Modifier.padding(vertical = 22.dp))
                Text("Virex Music", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Your music, online and offline.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

internal fun collectionSongs(library: Library, id: String?): Pair<String, List<Song>>? = when (id) {
    "@liked" -> "Liked songs" to library.liked
    "@recent" -> "Recently played" to library.listening.sortedByDescending(ListeningRecord::lastPlayed).map(ListeningRecord::song)
    "@most" -> "Most played" to library.listening.filter { it.plays > 0 }.sortedWith(compareByDescending<ListeningRecord> { it.plays }.thenByDescending { it.lastPlayed }).map(ListeningRecord::song)
    else -> null
}
@Composable private fun CollectionScreen(name: String, songs: List<Song>, back: () -> Unit, play: (Int) -> Unit, options: (Song) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Action(Icons.AutoMirrored.Rounded.ArrowBack, "Back to library", onClick = back) }
        item {
            Icon(if (name == "Liked songs") Icons.Rounded.Favorite else if (name == "Most played") Icons.Rounded.BarChart else Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text(name, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
            Text("${songs.size} songs", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 8.dp))
            Button(onClick = { play(0) }, enabled = songs.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Text("Play all") }
        }
        if (songs.isEmpty()) item { EmptyState(Icons.Rounded.MusicNote, "Nothing here yet", when (name) { "Liked songs" -> "Tap the heart on a song to save it here."; "Most played" -> "Your most-listened songs will appear here as you listen."; else -> "Songs you play will appear here." }) }
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song -> SongRow(song, { play(index) }, { options(song) }) }
    }
}
