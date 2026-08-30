package com.apyfz.lutty.export

import android.content.Context
import android.media.MediaCodecInfo
import android.net.Uri
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.apyfz.lutty.color.CubeParser
import com.apyfz.lutty.color.CubeResult
import com.apyfz.lutty.color.LutCube
import com.apyfz.lutty.color.LutData
import com.apyfz.lutty.gl.GradeEffect
import com.apyfz.lutty.model.GradeState
import com.google.common.collect.ImmutableList
import java.io.File

/**
 * Headless end-to-end check. Reads clips from the app's external files dir, runs them through
 * Transformer with a LUT effect, and logs the outcome of each job.
 *
 * Deliberately not a UI: the point is to prove decode, LUT application, encode and mux before any
 * interface exists.
 */
class PipelineTest(private val context: Context) {

    companion object { const val TAG = "LuttyPipe" }

    /** Mirrors every log line somewhere adb can read it, since MagicOS filters logcat. */
    var reportSink: ((String) -> Unit)? = null

    private fun report(line: String) {
        Log.i(TAG, line)
        reportSink?.invoke(line)
    }

    data class Job(
        val label: String,
        val input: File,
        val output: File,
        val lut: LutData?,
        val clipMs: Long = 2_000L,
        /** Composition.HDR_MODE_*; KEEP_HDR is what routes Media3 onto the 16-bit float path. */
        val hdrMode: Int = Composition.HDR_MODE_KEEP_HDR,
        /** Requested encoder bitrate in bits/sec, or null to let Media3 choose. */
        val bitrate: Int? = null,
        /** Requested encoder profile, e.g. HEVCProfileMain10, or null for the default. */
        val profile: Int? = null,
        /** When set, the custom GradeShaderProgram is used instead of Media3's SingleColorLut. */
        val grade: GradeState? = null,
        /**
         * Media3 clamps a requested bitrate to the encoder's advertised range when fallback is on
         * (DefaultEncoderFactory: getSupportedBitrateRange(..).clamp(..)). Turning fallback off
         * uses the requested value verbatim.
         */
        val disableEncoderFallback: Boolean = false,
        val gradeLuts: List<LutData> = emptyList(),
    )

    fun run(jobs: List<Job>, onAllDone: () -> Unit) {
        val queue = ArrayDeque(jobs)
        fun next() {
            val job = queue.removeFirstOrNull()
            if (job == null) { report("ALL JOBS FINISHED"); onAllDone(); return }
            runJob(job) { next() }
        }
        next()
    }

    private fun runJob(job: Job, done: () -> Unit) {
        if (!job.input.isFile) {
            report("[${job.label}] MISSING INPUT ${job.input}")
            done(); return
        }
        job.output.delete()
        report("[${job.label}] start  in=${job.input.name} (${job.input.length()} bytes)  lut=${job.lut?.title ?: "none"} grade=${job.grade != null}")

        val effects: List<Effect> = when {
            job.grade != null -> listOf(GradeEffect(job.grade, job.gradeLuts))
            job.lut != null -> listOf(SingleColorLut.createFromCube(LutCube.toMedia3Cube(job.lut)))
            else -> emptyList()
        }

        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(job.input))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setEndPositionMs(job.clipMs).build()
            )
            .build()

        val edited = EditedMediaItem.Builder(item)
            .setEffects(Effects(ImmutableList.of(), ImmutableList.copyOf(effects)))
            .build()

        val encoderSettings = VideoEncoderSettings.Builder().apply {
            job.bitrate?.let {
                setBitrate(it)
                setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            job.profile?.let {
                setEncodingProfileLevel(it, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51)
            }
        }.build()

        val composition = Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
            .setHdrMode(job.hdrMode)
            .build()

        val startNs = System.nanoTime()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(encoderSettings)
                    .setEnableFallback(!job.disableEncoderFallback)
                    .build()
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    val ms = (System.nanoTime() - startNs) / 1_000_000
                    report("[${job.label}] OK in ${ms}ms  ${result.width}x${result.height} " +
                        "codec=${result.videoMimeType} bytes=${result.fileSizeBytes} " +
                        "frames=${result.videoFrameCount} avgBitrate=${result.averageVideoBitrate}")
                    report("[${job.label}] wrote ${job.output.absolutePath} (${job.output.length()} bytes)")
                    done()
                }

                override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                    report("[${job.label}] FAILED errorCode=${e.errorCode} (${ExportException.getErrorCodeName(e.errorCode)}) " +
                        "msg=${e.message} cause=${e.cause}")
                    done()
                }
            })
            .build()

        transformer.start(composition, job.output.absolutePath)
    }
}

/** Loads a .cube from the app's files dir, or returns null and logs. */
fun loadLut(file: File): LutData? {
    if (!file.isFile) { Log.e(PipelineTest.TAG, "LUT missing: $file"); return null }
    return when (val r = file.inputStream().use { CubeParser.parse(it) }) {
        is CubeResult.Ok -> r.lut.also { Log.i(PipelineTest.TAG, "loaded LUT ${file.name} size=${it.size}") }
        is CubeResult.Error -> { Log.e(PipelineTest.TAG, "LUT parse failed ${file.name}: ${r.message}"); null }
    }
}
