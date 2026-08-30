package com.apyfz.lutty.data

import android.content.Context
import android.util.Log
import com.apyfz.lutty.model.GradeState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Preset(val name: String, val grade: GradeState)

/** Named grades, stored as one inspectable JSON file. */
class PresetStore(context: Context) {

    companion object { const val TAG = "LuttyPresets" }

    private val file = File(context.filesDir, "presets.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun list(): List<Preset> = try {
        if (file.isFile) json.decodeFromString<List<Preset>>(file.readText()) else emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "preset file unreadable, ignoring", e); emptyList()
    }

    fun save(name: String, grade: GradeState) {
        val kept = list().filterNot { it.name.equals(name, ignoreCase = true) }
        write(kept + Preset(name, grade))
    }

    fun delete(name: String) = write(list().filterNot { it.name == name })

    private fun write(presets: List<Preset>) {
        try {
            file.writeText(json.encodeToString(presets))
        } catch (e: Exception) {
            Log.e(TAG, "could not save presets", e)
        }
    }
}
