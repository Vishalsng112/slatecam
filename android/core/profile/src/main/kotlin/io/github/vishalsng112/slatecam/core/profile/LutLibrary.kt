package io.github.vishalsng112.slatecam.core.profile

import java.io.File

/**
 * Loads user `.cube` LUTs from a directory. Files are read once and cached by
 * filename; dropping a new file in and reloading is enough to get a new profile.
 */
object LutLibrary {

    data class Entry(val id: String, val name: String, val lut: Lut3D)

    fun scan(directory: File): List<Entry> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("cube", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.mapNotNull { file ->
                val lut = runCatching { Lut3D.parse(file.readText()) }.getOrNull()
                    ?: return@mapNotNull null
                Entry(
                    id = "lut:" + file.nameWithoutExtension,
                    name = file.nameWithoutExtension,
                    lut = lut,
                )
            }
            ?: emptyList()
    }
}
