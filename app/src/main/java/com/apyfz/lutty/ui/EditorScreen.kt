package com.apyfz.lutty.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.apyfz.lutty.export.Exporter
import com.apyfz.lutty.gl.GradeEffect
import com.apyfz.lutty.model.Profile
import kotlin.math.roundToInt

/** One tool per icon. Selecting a tool swaps the single control row above the rail. */
private enum class Tool(val label: String, val icon: ImageVector) {
    CONVERT("Convert", Icons.Default.SwapHoriz),
    LUTS("LUTs", Icons.Default.PhotoFilter),
    EXPOSURE("Exposure", Icons.Default.Exposure),
    TEMPERATURE("Temp", Icons.Default.Thermostat),
    TINT("Tint", Icons.Default.InvertColors),
    CONTRAST("Contrast", Icons.Default.Contrast),
    SATURATION("Saturation", Icons.Default.WaterDrop),
    PRESETS("Presets", Icons.Default.Bookmarks),
}

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val context = LocalContext.current

    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { vm.setVideo(it) } }

    val pickLut = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importLut(it, null) } }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    LaunchedEffect(Unit) {
        player.setVideoEffects(listOf(Presentation.createForHeight(1080), GradeEffect(vm.controller)))
    }
    LaunchedEffect(vm.videoUri) {
        vm.videoUri?.let {
            player.setMediaItem(MediaItem.fromUri(it))
            player.prepare()
            player.play()
        }
    }

    // While paused the shader gets no new frames, so a parameter change would not show until
    // playback resumed. Nudging the position forces the paused frame to be re-rendered through
    // the current grade. Keyed on the grade so rapid drags coalesce into one redraw.
    LaunchedEffect(vm.grade, vm.bypassActive) {
        if (vm.videoUri != null && !player.isPlaying) {
            delay(40)
            val pos = player.currentPosition
            player.seekTo(if (pos > 0) pos - 1 else 1)
            player.seekTo(pos)
        }
    }

    vm.pendingVideo?.let {
        AlertDialog(
            onDismissRequest = { vm.cancelPendingVideo() },
            icon = { Icon(Icons.Default.PhotoFilter, null) },
            title = { Text("Keep the current grade?") },
            text = {
                Text(
                    "Carry your LUTs and adjustments over to the new clip, or start from scratch. " +
                        "The log format is detected for the new clip either way."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.resolvePendingVideo(keepGrade = true) }) { Text("Keep grade") }
            },
            dismissButton = {
                TextButton(onClick = { vm.resolvePendingVideo(keepGrade = false) }) { Text("Start fresh") }
            },
        )
    }

    var tool by remember { mutableStateOf(Tool.CONVERT) }


    // A column, not a sheet: the video owns everything above the controls and is never covered.
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (vm.videoUri != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { if (player.isPlaying) player.pause() else player.play() },
                            onLongPress = { vm.setBypass(true) },
                            onPress = { tryAwaitRelease(); vm.setBypass(false) },
                        )
                    },
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Lutty", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    }) { Text("Choose a clip") }
                }
            }

            FilledTonalIconButton(
                onClick = {
                    pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                },
                modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 44.dp),
            ) { Icon(Icons.Default.VideoLibrary, "Choose a clip") }

            ExportButton(vm, Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 44.dp))

            if (vm.videoUri != null) {
                // Floating rather than docked: the scrubber belongs to the image, not the editor,
                // and keeping it off the control surface leaves that surface purely for the grade.
                Surface(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                        .fillMaxWidth(),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
                ) {
                    Scrubber(player, true, Modifier.padding(horizontal = 14.dp))
                }
            }

            if (vm.bypassActive) {
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(top = 52.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                ) {
                    Text(
                        "Original",
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.navigationBarsPadding().padding(top = 6.dp)) {
                // Exactly one control row, always the same height, so the video never moves.
                val controlHeight by animateDpAsState(
                    when {
                        tool == Tool.LUTS && vm.grade.luts.isNotEmpty() -> 144.dp
                        tool == Tool.LUTS -> 104.dp
                        else -> 56.dp
                    },
                    label = "controlHeight",
                )
                Box(
                    Modifier.fillMaxWidth().height(controlHeight).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    when (tool) {
                        Tool.CONVERT -> ConvertControl(vm)
                        Tool.LUTS -> LutControl(vm) { pickLut.launch(arrayOf("*/*")) }
                        Tool.EXPOSURE -> ValueSlider("Exposure", vm.grade.exposure, -3f..3f, "%+.2f EV", 0f) { vm.setExposure(it) }
                        Tool.TEMPERATURE -> ValueSlider("Temperature", vm.grade.temperature, -1f..1f, "%+.2f", 0f) { vm.setTemperature(it) }
                        Tool.TINT -> ValueSlider("Tint", vm.grade.tint, -1f..1f, "%+.2f", 0f) { vm.setTint(it) }
                        Tool.CONTRAST -> ValueSlider("Contrast", vm.grade.contrast, 0.5f..2f, "%.2f", 1f) { vm.setContrast(it) }
                        Tool.SATURATION -> ValueSlider("Saturation", vm.grade.saturation, 0f..2f, "%.2f", 1f) { vm.setSaturation(it) }
                        Tool.PRESETS -> PresetControl(vm)
                    }
                }
                ToolRail(tool) { tool = it }
            }
        }
    }
}

@Composable
private fun ToolRail(selected: Tool, onSelect: (Tool) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Tool.entries.forEach { t ->
            val active = t == selected
            Column(
                Modifier.width(68.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FilledIconToggleButton(checked = active, onCheckedChange = { onSelect(t) }) {
                    Icon(t.icon, contentDescription = t.label)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    t.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = if (active) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    neutral: Float,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(String.format(format, value), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { onChange(neutral) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Refresh, "Reset $label", Modifier.size(16.dp))
            }
        }
        SlimSlider(value = value, range = range, onChange = onChange)
    }
}

@Composable
private fun ConvertControl(vm: EditorViewModel) {
    // Sized to their labels and centred as a pair, so the row reads as one control rather than
    // two chips adrift in their own halves.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileChip("From", vm.grade.input, !vm.detecting && vm.detection?.confident == false) {
            vm.setInputProfile(it)
        }
        Icon(
            Icons.Default.ArrowForward, null,
            Modifier.padding(horizontal = 8.dp).size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProfileChip("To", vm.grade.target, false) { vm.setTargetProfile(it) }
    }
}

@Composable
private fun ProfileChip(
    prefix: String,
    selected: Profile,
    uncertain: Boolean,
    onSelect: (Profile) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = {
                Text(
                    "$prefix: ${selected.label}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            },
            leadingIcon = if (uncertain) {
                { Icon(Icons.Default.HelpOutline, "Unsure, check this", Modifier.size(14.dp)) }
            } else null,
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Profile.entries.forEach { p ->
                DropdownMenuItem(text = { Text(p.label) }, onClick = { onSelect(p); open = false })
            }
        }
    }
}

@Composable
private fun LutControl(vm: EditorViewModel, onImport: () -> Unit) {
    val luts = vm.grade.luts
    val active = vm.targetSlot.coerceIn(0, maxOf(0, luts.lastIndex))

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Strength sits directly under the scrubber and above the tiles, visible whenever a LUT
        // is applied. No tap-to-reveal: the control is wanted almost every time a LUT is chosen.
        if (luts.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    luts[active].name,
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    "${(luts[active].strength * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SlimSlider(
                value = luts[active].strength,
                range = 0f..1f,
                onChange = { vm.setStrength(active, it) },
            )
        }
        LutLibraryStrip(vm, onImport)
    }
}

/** Library strip. Tap to apply, or to switch which slot the strength slider controls. */
@Composable
private fun LutLibraryStrip(vm: EditorViewModel, onImport: () -> Unit) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val luts = vm.grade.luts

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier.width(60.dp).clickable(onClick = onImport),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(54.dp).clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.FileOpen, "Import a .cube file", Modifier.size(20.dp)) }
            Spacer(Modifier.height(4.dp))
            Text(
                "Import", style = MaterialTheme.typography.labelSmall, maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        vm.baseThumb?.let { base ->
            LutTile(
                bitmap = base,
                label = if (vm.targetSlot > 0) "No 2nd" else "None",
                selected = luts.getOrNull(vm.targetSlot) == null,
                applied = false,
                onClick = { vm.clearTargetSlot() },
                onLongClick = null,
            )
        }

        vm.lutEntries.forEach { entry ->
            val appliedSlot = luts.indexOfFirst { it.lutId == entry.id }
            val isTarget = luts.getOrNull(vm.targetSlot)?.lutId == entry.id
            LutTile(
                bitmap = vm.thumbnails[entry.id],
                label = entry.name,
                selected = isTarget,
                applied = appliedSlot >= 0,
                onClick = {
                    if (appliedSlot >= 0) vm.selectSlot(appliedSlot) else vm.applyLutToSlot(entry)
                },
                onLongClick = { confirmDelete = entry.id },
            )
        }

        // A second LUT is only ever added on purpose, never by tapping another tile.
        if (luts.size == 1) {
            Column(
                Modifier.width(60.dp).clickable { vm.addSecondSlot() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(54.dp).clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Add, "Add a second LUT on top", Modifier.size(22.dp)) }
                Spacer(Modifier.height(4.dp))
                Text(
                    "2nd", style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove from library?") },
            text = { Text("\"$id\" will be deleted from Lutty. The original file is untouched.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteLut(id); confirmDelete = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LutTile(
    bitmap: android.graphics.Bitmap?,
    label: String,
    selected: Boolean,
    applied: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Column(
        Modifier.width(60.dp).pointerInput(label) {
            detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick?.invoke() })
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(54.dp).clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .then(
                    if (selected) Modifier.border(
                        2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium,
                    ) else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium),
                )
            } else {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            if (applied) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(3.dp).size(14.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check, "Applied",
                        Modifier.size(10.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Position bar. Hairline, no labels: the frame itself is the feedback. */
@Composable
private fun Scrubber(player: ExoPlayer, enabled: Boolean, modifier: Modifier = Modifier) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(enabled) {
        while (enabled) {
            if (!dragging) {
                position = player.currentPosition.coerceAtLeast(0L)
                duration = player.duration.coerceAtLeast(0L)
            }
            delay(60)
        }
    }

    SlimSlider(
        value = if (duration > 0) position.toFloat() / duration else 0f,
        range = 0f..1f,
        onChange = { v ->
            if (duration > 0) {
                dragging = true
                position = (v * duration).toLong()
                player.seekTo(position)
            }
        },
        onChangeFinished = { dragging = false },
        modifier = modifier,
    )
}

@Composable
private fun PresetControl(vm: EditorViewModel) {
    var showSave by remember { mutableStateOf(false) }
    var showBake by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var bakeName by remember { mutableStateOf("Lutty grade") }

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { showSave = true }, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Default.Save, "Save current grade", Modifier.size(18.dp))
        }
        IconButton(onClick = { showBake = true }, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Default.FileDownload, "Bake grade to a .cube file", Modifier.size(18.dp))
        }
        Spacer(Modifier.width(6.dp))
        vm.presets.forEach { p ->
            InputChip(
                selected = false,
                onClick = { vm.applyPreset(p) },
                label = { Text(p.name, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                trailingIcon = {
                    Icon(
                        Icons.Default.Close, "Delete ${p.name}",
                        Modifier.size(14.dp).pointerInput(p.name) {
                            detectTapGestures(onTap = { vm.deletePreset(p.name) })
                        },
                    )
                },
            )
            Spacer(Modifier.width(6.dp))
        }
    }

    if (showBake) {
        AlertDialog(
            onDismissRequest = { showBake = false },
            icon = { Icon(Icons.Default.FileDownload, null) },
            title = { Text("Bake to .cube") },
            text = {
                Column {
                    Text(
                        "Collapses the conversion, both LUTs and every slider into one LUT, " +
                            "saved to Downloads/Lutty.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = bakeName, onValueChange = { bakeName = it },
                        label = { Text("File name") }, singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (bakeName.isNotBlank()) { vm.bakeCube(bakeName.trim()); showBake = false }
                }) { Text("Bake") }
            },
            dismissButton = { TextButton(onClick = { showBake = false }) { Text("Cancel") } },
        )
    }

    vm.bakeStatus?.let { status ->
        if (!status.endsWith("…")) {
            AlertDialog(
                onDismissRequest = { vm.clearBakeStatus() },
                title = { Text("Bake") },
                text = { Text(status) },
                confirmButton = { TextButton(onClick = { vm.clearBakeStatus() }) { Text("OK") } },
            )
        }
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save preset") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) { vm.savePreset(name.trim()); name = ""; showSave = false }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ExportButton(vm: EditorViewModel, modifier: Modifier = Modifier) {
    when (val state = vm.exportState) {
        is Exporter.Progress.Running ->
            FilledTonalIconButton(onClick = {}, modifier = modifier, enabled = false) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        is Exporter.Progress.Done -> FilledTonalIconButton(
            onClick = { vm.clearExportState() }, modifier = modifier,
        ) { Icon(Icons.Default.CheckCircle, "Saved to Movies/Lutty") }
        is Exporter.Progress.Failed -> {
            var show by remember { mutableStateOf(true) }
            FilledTonalIconButton(onClick = { show = true }, modifier = modifier) {
                Icon(Icons.Default.ErrorOutline, "Export failed")
            }
            if (show) {
                AlertDialog(
                    onDismissRequest = { show = false; vm.clearExportState() },
                    title = { Text("Export failed") },
                    text = { Text(state.message) },
                    confirmButton = {
                        TextButton(onClick = { show = false; vm.clearExportState() }) { Text("OK") }
                    },
                )
            }
        }
        null -> FilledTonalIconButton(
            onClick = { vm.export() }, modifier = modifier, enabled = vm.videoUri != null,
        ) { Icon(Icons.Default.Download, "Export full resolution") }
    }
}
