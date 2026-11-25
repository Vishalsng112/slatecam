package io.github.vishalsng112.slatecam.core.profile

/** A user-created picture profile. Built-ins stay in [PictureProfile]. */
data class UserPictureProfile(
    val id: String,
    val name: String,
    val settings: ProfileSettings = ProfileSettings.DEFAULTS,
)

/**
 * In-memory library of user-created picture profiles. The bridge owns
 * persistence ordering and IDs; this class only enforces the editing rules, so
 * it is pure JVM code and testable without Android.
 */
class PictureProfileLibrary {
    private val profiles = LinkedHashMap<String, UserPictureProfile>()

    fun restore(entries: Iterable<UserPictureProfile>) {
        profiles.clear()
        entries.forEach { profile ->
            profiles[profile.id] = profile.copy(
                name = profile.name.trim().ifBlank { DEFAULT_NAME },
                settings = profile.settings.normalized(),
            )
        }
    }

    fun all(): List<UserPictureProfile> = profiles.values.toList()

    fun get(id: String): UserPictureProfile? = profiles[id]

    fun add(id: String, name: String, settings: ProfileSettings): UserPictureProfile {
        val profile = UserPictureProfile(
            id = id,
            name = name.trim().ifBlank { DEFAULT_NAME },
            settings = settings.normalized(),
        )
        profiles[id] = profile
        return profile
    }

    fun rename(id: String, name: String): Boolean {
        val profile = profiles[id] ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        profiles[id] = profile.copy(name = trimmed)
        return true
    }

    fun updateSettings(id: String, settings: ProfileSettings): Boolean {
        val profile = profiles[id] ?: return false
        profiles[id] = profile.copy(settings = settings.normalized())
        return true
    }

    fun resetSettings(id: String): Boolean = updateSettings(id, ProfileSettings.DEFAULTS)

    /** Resets one named parameter to its library default. */
    fun resetParameter(id: String, key: String): Boolean {
        if (key !in ProfileSettings.EDITOR_KEYS) return false
        val profile = profiles[id] ?: return false
        profiles[id] = profile.copy(settings = profile.settings.reset(key))
        return true
    }

    fun delete(id: String): Boolean = profiles.remove(id) != null

    private companion object {
        const val DEFAULT_NAME = "Custom"
    }
}
