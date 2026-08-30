package com.apyfz.lutty.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log

/**
 * Reports what this device can actually decode and encode.
 *
 * Profile constant names are resolved by reflection over MediaCodecInfo.CodecProfileLevel rather
 * than hardcoded, so the output is correct without assuming any particular numeric value.
 */
object CodecProbe {
    const val TAG = "LuttyCodec"

    data class CodecReport(
        val name: String,
        val isEncoder: Boolean,
        val isHardware: Boolean,
        val profiles: List<String>,
        val maxWidth: Int,
        val maxHeight: Int,
        val bitrateRange: String,
    )

    private val hevcProfileNames: Map<Int, String> by lazy {
        MediaCodecInfo.CodecProfileLevel::class.java.fields
            .filter { it.name.startsWith("HEVCProfile") && it.type == Int::class.javaPrimitiveType }
            .associate { (it.getInt(null)) to it.name }
    }

    fun report(mime: String = "video/hevc"): List<CodecReport> {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        val out = mutableListOf<CodecReport>()
        for (info in list.codecInfos) {
            if (!info.supportedTypes.any { it.equals(mime, true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            val profiles = caps.profileLevels
                .map { hevcProfileNames[it.profile] ?: "profile:0x%x".format(it.profile) }
                .distinct()
                .sorted()
            val v = caps.videoCapabilities
            out += CodecReport(
                name = info.name,
                isEncoder = info.isEncoder,
                isHardware = info.isHardwareAccelerated,
                profiles = profiles,
                maxWidth = v?.supportedWidths?.upper ?: -1,
                maxHeight = v?.supportedHeights?.upper ?: -1,
                bitrateRange = v?.bitrateRange?.toString() ?: "?",
            )
        }
        return out
    }

    /** True when some hardware decoder advertises 4:2:2 10-bit HEVC, as Blackmagic Camera writes. */
    fun supportsHevc422_10(): Boolean = report().any { r ->
        !r.isEncoder && r.profiles.any { it.contains("422", ignoreCase = true) }
    }

    fun supportsHevcMain10Encode(): Boolean = report().any { r ->
        r.isEncoder && r.profiles.any { it == "HEVCProfileMain10" }
    }

    fun logAll() {
        for (mime in listOf("video/hevc", "video/avc")) {
            Log.i(TAG, "=== $mime ===")
            for (r in report(mime)) {
                val kind = if (r.isEncoder) "ENCODER" else "decoder"
                val hw = if (r.isHardware) "hw" else "sw"
                Log.i(TAG, "$kind $hw ${r.name}  max ${r.maxWidth}x${r.maxHeight}  bitrate=${r.bitrateRange}")
                Log.i(TAG, "    profiles: ${r.profiles.joinToString(", ")}")
            }
        }
        Log.i(TAG, "SUMMARY hevc422_10_decode=${supportsHevc422_10()} hevcMain10_encode=${supportsHevcMain10Encode()}")
    }
}
