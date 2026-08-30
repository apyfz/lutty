package com.apyfz.lutty.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecInfo
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.apyfz.lutty.color.LutData
import com.apyfz.lutty.gl.GradeEffect
import com.apyfz.lutty.model.GradeState
import com.google.common.collect.ImmutableList
import java.io.File

/**
 * Runs the grade over the whole clip at full resolution and publishes the result to the gallery.
 *
 * Transformer writes to a plain file, so the export lands in app storage first and is then copied
 * into MediaStore under Movies/Lutty where the gallery will pick it up.
 */
class Exporter(private val context: Context) {

    companion object {
        const val TAG = "LuttyExport"
        private const val ALBUM = "Lutty"
    }

    sealed interface Progress {
        data class Running(val percent: Int) : Progress
        data class Done(val uri: Uri?, val elapsedMs: Long, val bytes: Long) : Progress
        data class Failed(val message: String) : Progress
    }

    private var transformer: Transformer? = null

    fun cancel() {
        transformer?.cancel()
        transformer = null
    }

    fun start(
        input: Uri,
        grade: GradeState,
        luts: List<LutData>,
        onProgress: (Progress) -> Unit,
    ) {
        val staging = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val effects: List<Effect> = listOf(GradeEffect(grade, luts))

        val edited = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setEffects(Effects(ImmutableList.of(), ImmutableList.copyOf(effects)))
            .build()

        val composition = Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
            .setHdrMode(Composition.HDR_MODE_KEEP_HDR)
            .build()

        // The encoder is quality-limited rather than bitrate-limited on this device, but asking
        // for a source-matching ceiling stops Media3 choosing a conservative default.
        val encoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(120_000_000)
            .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            .build()

        val startNs = System.nanoTime()
        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(encoderSettings)
                    .setEnableFallback(true)
                    .build()
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    val ms = (System.nanoTime() - startNs) / 1_000_000
                    Log.i(TAG, "export ok ${result.width}x${result.height} in ${ms}ms, ${staging.length()} bytes")
                    val uri = publish(staging)
                    transformer = null
                    if (uri == null) {
                        // The graded file exists but is not in the gallery. Keep it rather than
                        // deleting the only copy, and say where it is.
                        Log.e(TAG, "publish failed, keeping ${staging.absolutePath}")
                        onProgress(
                            Progress.Failed(
                                "The clip was graded but could not be added to the gallery. " +
                                    "It is saved at ${staging.absolutePath}"
                            )
                        )
                    } else {
                        staging.delete()
                        onProgress(Progress.Done(uri, ms, result.fileSizeBytes))
                    }
                }

                override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                    Log.e(TAG, "export failed: ${ExportException.getErrorCodeName(e.errorCode)} ${e.message}", e)
                    staging.delete()
                    transformer = null
                    onProgress(Progress.Failed(describe(e)))
                }
            })
            .build()

        transformer = t
        t.start(composition, staging.absolutePath)
        onProgress(Progress.Running(0))
    }

    /** Turns Media3's error codes into something a person can act on. */
    private fun describe(e: ExportException): String = when (e.errorCode) {
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        ExportException.ERROR_CODE_DECODING_FAILED ->
            "This phone cannot decode that file. Footage recorded as HEVC 4:2:2 (for example " +
                "Blackmagic Camera's default for Apple Log) is not supported by this chipset. " +
                "Record in HEVC 4:2:0 10-bit instead."
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED ->
            "The encoder refused these settings. Try a lower resolution."
        else -> e.message ?: "Export failed (${ExportException.getErrorCodeName(e.errorCode)})"
    }

    /**
     * Copies the graded file into the gallery.
     *
     * The MediaStore row is created pending and only finalised once the bytes are fully written.
     * Every failure path removes that row, so a partial or failed export never leaves an orphaned
     * entry behind, and returns null so the caller keeps the file on disk.
     */
    private fun publish(source: File): Uri? {
        val resolver = context.contentResolver
        var pending: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "Lutty_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Log.e(TAG, "MediaStore insert returned null")
                return null
            }
            pending = uri

            val stream = resolver.openOutputStream(uri)
            if (stream == null) {
                Log.e(TAG, "openOutputStream returned null")
                return null
            }
            val copied = stream.use { out -> source.inputStream().use { it.copyTo(out) } }
            if (copied != source.length()) {
                Log.e(TAG, "copied $copied of ${source.length()} bytes")
                return null
            }

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) == 0) {
                // The row was not finalised, so it stays invisible to the gallery. Reporting
                // success here would delete the only usable copy.
                Log.e(TAG, "clearing IS_PENDING updated no rows")
                return null
            }
            pending = null      // finalised, so the cleanup below must leave it alone
            Log.i(TAG, "published $copied bytes to $uri")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "could not publish to gallery", e)
            null
        } finally {
            // Anything still pending here failed or threw part way through.
            pending?.let { orphan ->
                runCatching { resolver.delete(orphan, null, null) }
                    .onFailure { Log.w(TAG, "could not remove the pending entry", it) }
            }
        }
    }

    fun progressPercent(): Int {
        val t = transformer ?: return 0
        val holder = androidx.media3.transformer.ProgressHolder()
        return if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1
    }
}
