package com.apyfz.lutty.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.apyfz.lutty.color.LutData
import com.apyfz.lutty.data.LutEntry
import com.apyfz.lutty.data.LutLibrary
import com.apyfz.lutty.data.Preset
import com.apyfz.lutty.data.PresetStore
import com.apyfz.lutty.export.Exporter
import com.apyfz.lutty.gl.GradeController
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.apyfz.lutty.color.GradePipeline
import com.apyfz.lutty.media.LutSwatch
import com.apyfz.lutty.media.ProfileDetector
import kotlinx.coroutines.Dispatchers
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
    var grade by mutableStateOf(GradeState.NEUTRAL); private set
    var lutEntries by mutableStateOf(library.list()); private set
    var presets by mutableStateOf(presetStore.list()); private set
    var exportState by mutableStateOf<Exporter.Progress?>(null); private set
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
        viewModelScope.launch {
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
    }

    /** LUT set changed, so textures must be rebuilt. */
    private fun applyWithLuts(newGrade: GradeState) {
        grade = newGrade
        controller.updateLuts(newGrade, resolvedLuts())
        refreshThumbnails()
    }

    fun importLut(uri: Uri, name: String?) {
        library.import(getApplication(), uri, name)?.let { entry ->
            lutEntries = library.list()
            applyLutToSlot(entry)
        }
    }

    /** Puts [entry] in [targetSlot], replacing whatever was there. */
    fun applyLutToSlot(entry: LutEntry) {
        val slot = LutSlot(entry.id, entry.name, 1f)
        val list = grade.luts.toMutableList()
        if (targetSlot < list.size) list[targetSlot] = slot else list.add(slot)
        applyWithLuts(grade.copy(luts = list.take(2)))
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
        val uri = videoUri ?: return
        exportState = Exporter.Progress.Running(0)
        exporter.start(uri, grade, resolvedLuts()) { exportState = it }
    }

    fun clearExportState() { exportState = null }

    var bypassActive by mutableStateOf(false); private set

    fun setBypass(on: Boolean) {
        controller.bypass = on
        bypassActive = on
    }
}
