package com.apyfz.lutty.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.apyfz.lutty.color.LutData
import com.apyfz.lutty.data.LutCategory
import com.apyfz.lutty.data.LutEntry
import com.apyfz.lutty.data.LutLibrary
import com.apyfz.lutty.data.Preset
import com.apyfz.lutty.data.PresetStore
import com.apyfz.lutty.export.Exporter
import com.apyfz.lutty.gl.GradeController
import com.apyfz.lutty.gl.StillGlRenderer
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.apyfz.lutty.color.GradePipeline
import com.apyfz.lutty.media.LutSwatch
import com.apyfz.lutty.media.ProfileDetector
import com.apyfz.lutty.media.RawDecoder
import com.apyfz.lutty.media.StillEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import com.apyfz.lutty.model.GradeState
import com.apyfz.lutty.model.LutSlot
import com.apyfz.lutty.model.Profile

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val library = LutLibrary(app)
    private val presetStore = PresetStore(app)
    private val exporter = Exporter(app)

    var videoUri by mutableStateOf<Uri?>(null); private set
    // Raw stills path. When [stillImage] is set the editor shows a graded bitmap instead of the
    // player; the full-resolution [stillFull] is kept only for export.
    var stillImage by mutableStateOf<Bitmap?>(null); private set
    var loadingRaw by mutableStateOf(false); private set
    var stillRendering by mutableStateOf(false); private set
    var rawError by mutableStateOf<String?>(null); private set
    private var stillPreview: StillEngine.LinearImage? = null
    // A small develop backs the fast interactive preview; export re-develops from [stillRawUri] at
    // full resolution and renders it in strips, so the saved image keeps the sensor's resolution.
    private var stillSource: RawDecoder.Linear? = null
    private var stillRawUri: Uri? = null
    private var stillRenderJob: Job? = null
    var grade by mutableStateOf(GradeState.NEUTRAL); private set
    var lutEntries by mutableStateOf(library.list()); private set
    var presets by mutableStateOf(presetStore.list()); private set
    var exportState by mutableStateOf<Exporter.Progress?>(null); private set
    /** Human-readable stage of a still export ("Developing…", "Rendering…", "Saving…"). */
    var exportPhase by mutableStateOf<String?>(null); private set
    var detection by mutableStateOf<ProfileDetector.Result?>(null); private set
    var detecting by mutableStateOf(false); private set

    /**
     * Shared with the running shader. Slider moves write here and are picked up on the next frame,
     * so the effect chain is never rebuilt mid-drag.
     */
    val controller = GradeController()

    init { refreshThumbnails() }

    /** Parsed LUTs for the current stack, in stack order. Cached so preview does not reparse. */
    private val cache = mutableMapOf<String, LutData>()

    fun resolvedLuts(): List<LutData> = grade.luts.mapNotNull { slot ->
        cache.getOrPut(slot.lutId) { library.load(slot.lutId) ?: return@mapNotNull null }
    }

    /** Set when a new clip is chosen while an existing grade is worth keeping. */
    var pendingVideo by mutableStateOf<Uri?>(null); private set

    /**
     * Which stack slot a library tile replaces. Slot 0 is the normal case; slot 1 only exists
     * after the user explicitly asks for a second LUT, so tapping a tile never silently stacks.
     */
    var targetSlot by mutableStateOf(0); private set

    /**
     * The rebuild in flight, if any. Opening a clip starts one at the old profile and detection
     * starts another moments later; without cancelling, the slower job could finish last and put
     * the stale encoding back on screen.
     */
    private var thumbnailJob: Job? = null

    /** One tile per library LUT, rendered on a fixed synthetic chart. */
    var thumbnails by mutableStateOf<Map<String, Bitmap>>(emptyMap()); private set
    var baseThumb by mutableStateOf<Bitmap?>(null); private set

    fun setVideo(uri: Uri) {
        if (videoUri != null && grade.hasEdits()) {
            pendingVideo = uri
        } else {
            loadVideo(uri, keepGrade = false)
        }
    }

    private val rawExtensions =
        listOf(".dng", ".nef", ".cr2", ".cr3", ".arw", ".raf", ".rw2", ".orf", ".pef", ".srw", ".raw")

    /** Picker entry that decides between the video path and the raw-still path by file type. */
    fun openFile(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val mime = resolver.getType(uri).orEmpty().lowercase()
        val name = (uri.lastPathSegment ?: "").lowercase()
        val isRaw = mime.contains("dng") || mime.contains("x-adobe") ||
            rawExtensions.any { name.endsWith(it) }
        if (isRaw) loadRaw(uri) else setVideo(uri)
    }

    /** Develops a raw stills file to linear and shows it as a graded still. */
    private var rawLoadJob: Job? = null

    private fun loadRaw(uri: Uri) {
        // Cancel any develop already in flight: without this, opening B while A is still decoding
        // lets whichever finishes last win, so the editor could end up showing the wrong clip.
        rawLoadJob?.cancel()
        loadingRaw = true
        rawError = null
        rawLoadJob = viewModelScope.launch {
            val developed = withContext(Dispatchers.IO) {
                RawDecoder.develop(getApplication(), uri, PREVIEW_EDGE)
            }
            ensureActive()   // a newer load may have superseded this one while decoding
            if (developed == null) {
                loadingRaw = false
                rawError = "Could not develop this raw file"
                return@launch
            }
            // Leaving the video path: release any player-bound state so the still owns the surface.
            videoUri = null
            detection = null
            stillSource = developed
            stillRawUri = uri
            stillPreview = withContext(Dispatchers.Default) { StillEngine.fromRaw(developed, PREVIEW_EDGE) }
            cache.clear()
            // Raw is scene-linear BT.2020; default to encoding into Apple Log 2 for the LUT library.
            apply(GradeState.NEUTRAL.copy(
                inputProfile = Profile.RAW_LINEAR.name,
                targetProfile = Profile.APPLE_LOG_2.name,
            ))
            loadingRaw = false
            renderStill()
            refreshThumbnails()
        }
    }

    /** Re-renders the on-screen still from the current grade. No-op when no still is loaded. */
    private fun renderStill() {
        val preview = stillPreview ?: return
        stillRenderJob?.cancel()
        stillRendering = true
        stillRenderJob = viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                // GPU path: one draw of the same shader as video. CPU is a last-resort fallback for
                // a device without a working GL context (it is far slower with a LUT applied).
                StillGlRenderer.render(preview, grade, resolvedLuts())
                    ?: StillEngine.render(preview, grade, resolvedLuts())
            }
            ensureActive()
            stillImage = bmp
            stillRendering = false
        }
    }

    /** Answer to the carry-over prompt. */
    fun resolvePendingVideo(keepGrade: Boolean) {
        pendingVideo?.let { loadVideo(it, keepGrade) }
        pendingVideo = null
    }

    fun cancelPendingVideo() { pendingVideo = null }

    private fun loadVideo(uri: Uri, keepGrade: Boolean) {
        videoUri = uri
        if (!keepGrade) {
            cache.clear()
            applyWithLuts(GradeState.NEUTRAL)
        }
        detectProfile(uri, keepTarget = keepGrade)
        refreshThumbnails()
    }

    /**
     * Rebuilds the library tiles. They use a fixed synthetic chart rather than the clip, so they
     * are comparable between LUTs and exist before any clip is loaded.
     */
    fun refreshThumbnails(regrabFrame: Boolean = false) {
        thumbnailJob?.cancel()
        thumbnailJob = viewModelScope.launch {
            val tiles = withContext(Dispatchers.Default) {
                val target = grade.target
                val stack = resolvedLuts().take(targetSlot)
                val base = LutSwatch.render(target, grade, stack, null)
                val map = library.list().mapNotNull { entry ->
                    val lut = library.load(entry.id) ?: return@mapNotNull null
                    entry.id to LutSwatch.render(target, grade, stack, lut)
                }.toMap()
                base to map
            }
            // A newer rebuild may have started while this one was rendering.
            ensureActive()
            baseThumb = tiles.first
            thumbnails = tiles.second
        }
    }

    var bakeStatus by mutableStateOf<String?>(null); private set
    fun clearBakeStatus() { bakeStatus = null }

    /**
     * Bakes conversion, LUT stack and every slider into one .cube and drops it in Downloads, so
     * the same look can be reused in a desktop grading tool.
     */
    fun bakeCube(name: String, size: Int = 33) {
        viewModelScope.launch {
            bakeStatus = "Baking…"
            val ok = withContext(Dispatchers.Default) {
                val text = GradePipeline.bakeToCube(grade, resolvedLuts(), size, name)
                writeToDownloads("$name.cube", text)
            }
            bakeStatus = if (ok) "Saved $name.cube to Downloads" else "Could not save the .cube"
        }
    }

    private fun writeToDownloads(fileName: String, text: String): Boolean = try {
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Lutty")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) false else {
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }
    } catch (e: Exception) {
        Log.e("LuttyBake", "could not write $fileName", e); false
    }

    /** Clears just the target slot, leaving any other LUT in place. */
    fun clearTargetSlot() {
        val list = grade.luts.toMutableList()
        if (targetSlot < list.size) list.removeAt(targetSlot)
        targetSlot = targetSlot.coerceAtMost(maxOf(0, list.size - 1))
        applyWithLuts(grade.copy(luts = list))
    }

    fun removeLutById(id: String) {
        applyWithLuts(grade.copy(luts = grade.luts.filterNot { it.lutId == id }))
    }

    fun deleteLut(id: String) {
        library.delete(id)
        cache.remove(id)
        lutEntries = library.list()
        applyWithLuts(grade.copy(luts = grade.luts.filterNot { it.lutId == id }))
        refreshThumbnails()
    }

    /**
     * Reads the clip's colour tags and black floor to work out which log curve it was shot in,
     * then applies it. Runs off the main thread because decoding a 4K frame is not instant.
     */
    private fun detectProfile(uri: Uri, keepTarget: Boolean) {
        detecting = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ProfileDetector.detect(getApplication(), uri)
            }
            detection = result
            detecting = false
            // The input profile always describes the new clip. Log footage defaults to converting
            // into Apple Log 2, since that is what the LUT library is built for. Footage that is
            // already graded is left alone: there is no source curve to convert from.
            val defaultTarget =
                if (result.profile == Profile.PASSTHROUGH) Profile.PASSTHROUGH else Profile.APPLE_LOG_2
            apply(
                if (keepTarget) grade.copy(inputProfile = result.profile.name)
                else grade.copy(inputProfile = result.profile.name, targetProfile = defaultTarget.name)
            )
        }
    }

    /** Parameter-only change: no GL work, no texture upload. */
    private fun apply(newGrade: GradeState) {
        // Swatches are drawn in the target encoding, so they go stale when it changes. Slider
        // moves must not trigger this: rebuilding the whole library on every frame would stall.
        val targetChanged = newGrade.targetProfile != grade.targetProfile
        grade = newGrade
        controller.updateGrade(newGrade)
        if (targetChanged) refreshThumbnails()
        renderStill()
    }

    /** LUT set changed, so textures must be rebuilt. */
    private fun applyWithLuts(newGrade: GradeState) {
        grade = newGrade
        controller.updateLuts(newGrade, resolvedLuts())
        refreshThumbnails()
        renderStill()
    }

    /** A picked .cube waiting for the user to say what footage it is built for. */
    var pendingLutUri by mutableStateOf<Uri?>(null); private set

    fun onLutPicked(uri: Uri) { pendingLutUri = uri }
    fun cancelLutImport() { pendingLutUri = null }

    fun confirmLutImport(category: LutCategory) {
        val uri = pendingLutUri ?: return
        pendingLutUri = null
        library.import(getApplication(), uri, null, category)?.let { entry ->
            lutEntries = library.list()
            applyLutToSlot(entry)
        }
    }

    /**
     * Puts [entry] in [targetSlot] and points the conversion at what the LUT expects: LOG LUTs
     * grade in Apple Log 2, "processed" LUTs apply straight onto already-graded footage.
     */
    fun applyLutToSlot(entry: LutEntry) {
        val slot = LutSlot(entry.id, entry.name, 1f)
        val list = grade.luts.toMutableList()
        if (targetSlot < list.size) list[targetSlot] = slot else list.add(slot)
        // Only the base LUT decides the conversion. A single grade has one target, so letting a
        // stacked second LUT re-point it would break whichever LUT expects the other space.
        val base = list.getOrNull(0) ?: slot
        val category = if (base.lutId == entry.id) entry.category else library.categoryOf(base.lutId)
        val target = if (category == LutCategory.PROCESSED) Profile.PASSTHROUGH else Profile.APPLE_LOG_2
        applyWithLuts(grade.copy(luts = list.take(2), targetProfile = target.name))
    }

    /** Opens a second slot so the next tile tap layers on top instead of replacing. */
    fun addSecondSlot() {
        if (grade.luts.isEmpty() || grade.luts.size >= 2) return
        targetSlot = 1
    }

    fun selectSlot(index: Int) {
        targetSlot = index.coerceIn(0, maxOf(0, grade.luts.size - 1))
        refreshThumbnails()
    }

    fun removeLut(index: Int) {
        applyWithLuts(grade.copy(luts = grade.luts.filterIndexed { i, _ -> i != index }))
    }

    fun moveLut(from: Int, to: Int) {
        if (to !in grade.luts.indices) return
        val list = grade.luts.toMutableList()
        list.add(to, list.removeAt(from))
        applyWithLuts(grade.copy(luts = list))
    }

    fun setStrength(index: Int, value: Float) {
        apply(grade.copy(
            luts = grade.luts.mapIndexed { i, s -> if (i == index) s.copy(strength = value) else s }
        ))
    }

    fun setInputProfile(p: Profile) { apply(grade.copy(inputProfile = p.name)) }
    fun setTargetProfile(p: Profile) { apply(grade.copy(targetProfile = p.name)) }
    fun setExposure(v: Float) { apply(grade.copy(exposure = v)) }
    fun setTemperature(v: Float) { apply(grade.copy(temperature = v)) }
    fun setTint(v: Float) { apply(grade.copy(tint = v)) }
    fun setContrast(v: Float) { apply(grade.copy(contrast = v)) }
    fun setSaturation(v: Float) { apply(grade.copy(saturation = v)) }
    fun resetGrade() { apply(GradeState.NEUTRAL.copy(luts = grade.luts)) }

    fun savePreset(name: String) {
        presetStore.save(name, grade)
        presets = presetStore.list()
    }

    /**
     * Applies a saved look. The clip's own input profile is kept: it describes the footage in
     * front of you, not the preset, and it was detected from this clip.
     */
    fun applyPreset(preset: Preset) {
        applyWithLuts(preset.grade.copy(inputProfile = grade.inputProfile))
    }

    fun deletePreset(name: String) {
        presetStore.delete(name)
        presets = presetStore.list()
    }

    fun export() {
        if (stillSource != null) { exportStill(); return }
        val uri = videoUri ?: return
        exportState = Exporter.Progress.Running(0)
        exporter.start(uri, grade, resolvedLuts()) { exportState = it }
    }

    /** Re-develops the raw at full resolution and renders it in strips on the GPU, then saves a
     *  JPEG. Striped rendering keeps memory bounded so the export keeps the sensor's resolution. */
    private fun exportStill() {
        val uri = stillRawUri ?: return
        exportState = Exporter.Progress.Running(0)
        exportPhase = "Developing…"
        viewModelScope.launch {
            val saved = withContext(Dispatchers.Default) {
                val full = RawDecoder.develop(getApplication(), uri) ?: return@withContext null
                exportPhase = "Rendering…"
                val bmp = StillGlRenderer.renderTiled(full, grade, resolvedLuts()) { f ->
                    exportState = Exporter.Progress.Running((f * 100).toInt().coerceIn(1, 99))
                } ?: return@withContext null
                exportPhase = "Saving…"
                exportState = Exporter.Progress.Running(100)
                saveBitmapToGallery(bmp)
            }
            exportPhase = null
            exportState =
                if (saved != null) Exporter.Progress.Done(saved, 0, 0)
                else Exporter.Progress.Failed("Could not save the image")
        }
    }

    private fun saveBitmapToGallery(bmp: Bitmap): Uri? = try {
        val resolver = getApplication<Application>().contentResolver
        val name = "Lutty_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Lutty")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) null else {
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        }
    } catch (e: Exception) {
        Log.e("LuttyExport", "could not save image", e); null
    }

    fun clearExportState() { exportState = null }

    companion object {
        /** Long-edge cap for the interactive still preview; export re-renders at full resolution.
         *  The GPU renders comfortably at this size; the cost that remains is the pixel read-back. */
        // Preview develop resolution; export re-develops at full resolution and renders in strips.
        private const val PREVIEW_EDGE = 2048
    }

    var bypassActive by mutableStateOf(false); private set

    fun setBypass(on: Boolean) {
        controller.bypass = on
        bypassActive = on
    }
}
