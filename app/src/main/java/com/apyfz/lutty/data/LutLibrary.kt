package com.apyfz.lutty.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.apyfz.lutty.color.CubeParser
import com.apyfz.lutty.color.CubeResult
import com.apyfz.lutty.color.LutData
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What kind of footage a LUT is built for, which decides the conversion applied before it. */
enum class LutCategory { LOG, PROCESSED }

data class LutEntry(val id: String, val name: String, val category: LutCategory = LutCategory.LOG)

/**
 * Imported .cube files, with a parsed binary cache.
 *
 * A 65-cube is 274,625 rows and about 7 MB of text, which takes roughly 90 ms to parse. Parsing
 * once at import and caching the floats keeps LUT switching instant.
 */
class LutLibrary(context: Context) {

    companion object {
        const val TAG = "LuttyLib"
        private const val CACHE_MAGIC = 0x4C55_5431 // "LUT1"
    }

    private val dir = File(context.filesDir, "luts").apply { mkdirs() }

    // id -> category, persisted as a small properties file alongside the .cube files. Anything not
    // listed defaults to LOG, so LUTs imported before categories existed keep working unchanged.
    private val categoryFile = File(dir, "categories.properties")
    private val categories: MutableMap<String, LutCategory> = loadCategories()

    private fun loadCategories(): MutableMap<String, LutCategory> {
        val map = mutableMapOf<String, LutCategory>()
        if (!categoryFile.isFile) return map
        runCatching {
            categoryFile.readLines().forEach { line ->
                val i = line.indexOf('=')
                if (i > 0) {
                    val id = line.substring(0, i)
                    runCatching { LutCategory.valueOf(line.substring(i + 1).trim()) }
                        .getOrNull()?.let { map[id] = it }
                }
            }
        }
        return map
    }

    private fun saveCategories() {
        runCatching {
            categoryFile.writeText(categories.entries.joinToString("\n") { "${it.key}=${it.value.name}" })
        }.onFailure { Log.w(TAG, "could not save LUT categories", it) }
    }

    fun categoryOf(id: String): LutCategory = categories[id] ?: LutCategory.LOG

    fun list(): List<LutEntry> = dir.listFiles { f -> f.extension == "cube" }
        ?.sortedBy { it.nameWithoutExtension.lowercase() }
        ?.map { LutEntry(it.nameWithoutExtension, it.nameWithoutExtension, categoryOf(it.nameWithoutExtension)) }
        ?: emptyList()

    fun displayName(id: String): String = id

    /** Copies, parses and caches. Returns the entry, or null with the reason logged. */
    fun import(context: Context, uri: Uri, suggestedName: String?, category: LutCategory = LutCategory.LOG): LutEntry? {
        val base = (suggestedName ?: queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "lut")
            .substringAfterLast('/')
            .removeSuffix(".cube")
            .replace(Regex("[^A-Za-z0-9 _.-]"), "_")
            .ifBlank { "lut" }
        var name = base
        var n = 2
        while (File(dir, "$name.cube").exists()) { name = "$base $n"; n++ }

        val dest = File(dir, "$name.cube")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: run { Log.e(TAG, "could not open $uri"); return null }
        } catch (e: Exception) {
            Log.e(TAG, "copy failed for $uri", e); return null
        }

        return when (val r = dest.inputStream().use { CubeParser.parse(it) }) {
            is CubeResult.Ok -> {
                writeCache(name, r.lut)
                categories[name] = category
                saveCategories()
                Log.i(TAG, "imported $name size=${r.lut.size} category=$category")
                LutEntry(name, name, category)
            }
            is CubeResult.Error -> {
                Log.e(TAG, "rejected $name: ${r.message} (line ${r.line})")
                dest.delete()
                null
            }
        }
    }

    /** Content URIs rarely carry a usable filename in the path; ask the provider instead. */
    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (e: Exception) {
        Log.w(TAG, "could not read display name for $uri", e); null
    }

    fun load(id: String): LutData? {
        readCache(id)?.let { return it }
        val f = File(dir, "$id.cube")
        if (!f.isFile) return null
        return when (val r = f.inputStream().use { CubeParser.parse(it) }) {
            is CubeResult.Ok -> r.lut.also { writeCache(id, it) }
            is CubeResult.Error -> { Log.e(TAG, "parse failed $id: ${r.message}"); null }
        }
    }

    fun delete(id: String) {
        File(dir, "$id.cube").delete()
        File(dir, "$id.bin").delete()
        if (categories.remove(id) != null) saveCategories()
    }

    private fun cacheFile(id: String) = File(dir, "$id.bin")

    private fun writeCache(id: String, lut: LutData) {
        try {
            DataOutputStream(cacheFile(id).outputStream().buffered()).use { out ->
                out.writeInt(CACHE_MAGIC)
                out.writeInt(lut.size)
                for (k in 0..2) out.writeFloat(lut.domainMin[k])
                for (k in 0..2) out.writeFloat(lut.domainMax[k])
                val bb = ByteBuffer.allocate(lut.rgb.size * 4).order(ByteOrder.BIG_ENDIAN)
                bb.asFloatBuffer().put(lut.rgb)
                out.write(bb.array())
            }
        } catch (e: Exception) {
            Log.w(TAG, "cache write failed for $id", e)
            cacheFile(id).delete()
        }
    }

    private fun readCache(id: String): LutData? {
        val f = cacheFile(id)
        if (!f.isFile) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { input ->
                if (input.readInt() != CACHE_MAGIC) return null
                val size = input.readInt()
                val dMin = FloatArray(3) { input.readFloat() }
                val dMax = FloatArray(3) { input.readFloat() }
                val count = size * size * size * 3
                val bytes = ByteArray(count * 4)
                input.readFully(bytes)
                val floats = FloatArray(count)
                ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asFloatBuffer().get(floats)
                LutData(size, floats, dMin, dMax, id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cache read failed for $id, will reparse", e)
            f.delete()
            null
        }
    }
}
