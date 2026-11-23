package io.github.vishalsng112.slatecam.bridge

import java.util.Base64
import java.util.UUID
import kotlin.math.roundToInt

/** A lens that can join a shoot set, with an identity that survives reconnects. */
internal data class ShootLensRef(
    val id: String,
    val key: String,
)

/** Stable lens identity: facing, role and rounded 35 mm-equivalent focal length. */
internal fun shootLensKey(
    facing: String,
    role: String,
    focalLength35mmEquiv: Double?,
): String = "$facing:$role:${(focalLength35mmEquiv ?: 0.0).roundToInt()}"

/** One named lens set. [keys] are lens keys, not camera ids. */
internal data class ShootProfileEntry(
    val id: String,
    val name: String,
    val keys: Set<String>,
)

/** Raw persistence; the bridge backs this with SharedPreferences. */
internal interface ShootProfileStorage {
    fun readProfiles(): String?
    fun writeProfiles(raw: String)
    fun readActive(): String?
    fun writeActive(id: String)
}

internal sealed interface DeleteResult {
    data class Deleted(val activeId: String) : DeleteResult
    data object LastProfile : DeleteResult
    data object NotFound : DeleteResult
}

/**
 * Named shoot profiles, persisted as a small line-oriented record.
 *
 * Lens identity is the probe-derived key (`facing:role:focal`), not the camera
 * id, so a profile still applies when ids are renumbered between probes.
 */
internal class ShootProfileStore(
    private val storage: ShootProfileStorage,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun profiles(): MutableList<ShootProfileEntry> = decode(storage.readProfiles())

    fun activeId(): String? = storage.readActive()

    /**
     * Returns the stored profiles, seeding a `Default` profile from [defaultKeys]
     * on first use and repairing an active id that no longer resolves.
     */
    fun ensure(defaultKeys: Set<String>): MutableList<ShootProfileEntry> {
        val profiles = profiles()
        if (profiles.isEmpty()) {
            val default = ShootProfileEntry(
                id = DEFAULT_PROFILE_ID,
                name = DEFAULT_PROFILE_NAME,
                keys = defaultKeys,
            )
            write(listOf(default))
            storage.writeActive(default.id)
            return mutableListOf(default)
        }
        val active = activeId()
        if (active == null || profiles.none { it.id == active }) {
            storage.writeActive(profiles.first().id)
        }
        return profiles
    }

    /** Selected lens ids for a profile, ignoring keys this device no longer has. */
    fun selectionOf(profile: ShootProfileEntry, eligible: List<ShootLensRef>): Set<String> =
        eligible.filter { it.key in profile.keys }.map { it.id }.toSet()

    /** Lens keys for the selected ids. Unknown ids are ignored. */
    fun keysOf(selectedIds: Set<String>, eligible: List<ShootLensRef>): Set<String> =
        eligible.filter { it.id in selectedIds }.map { it.key }.toSet()

    /** Marks [id] active; returns false when the id is unknown. */
    fun setActive(id: String): Boolean {
        if (profiles().none { it.id == id }) return false
        storage.writeActive(id)
        return true
    }

    fun keyCount(profile: ShootProfileEntry, eligible: List<ShootLensRef>): Int {
        val available = eligible.map { it.key }.toSet()
        return profile.keys.count { it in available }
    }

    /** Rewrites the active profile's lens set, e.g. after a chip toggle. */
    fun updateActive(keys: Set<String>) {
        val profiles = ensure(emptySet())
        val active = activeId()
        write(
            profiles.map { profile ->
                if (profile.id == active) profile.copy(keys = keys) else profile
            },
        )
    }

    /**
     * Saves [keys] as [name] and makes it active. A name match (case-insensitive)
     * updates the existing profile instead of creating a duplicate.
     */
    fun save(name: String, keys: Set<String>): ShootProfileEntry? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val profiles = ensure(emptySet())
        val existing = profiles.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        val entry = existing?.copy(name = trimmed, keys = keys)
            ?: ShootProfileEntry(newId(), trimmed, keys)
        val updated = profiles.filterNot { it.id == entry.id }.toMutableList().apply { add(entry) }
        write(updated)
        storage.writeActive(entry.id)
        return entry
    }

    fun rename(id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val profiles = profiles()
        if (profiles.none { it.id == id }) return false
        write(profiles.map { if (it.id == id) it.copy(name = trimmed) else it })
        return true
    }

    /**
     * Deletes [id] unless it is the last profile. The returned active id is the
     * one to apply afterwards (unchanged when deleting an inactive profile).
     */
    fun delete(id: String): DeleteResult {
        val profiles = profiles()
        if (profiles.none { it.id == id }) return DeleteResult.NotFound
        if (profiles.size <= 1) return DeleteResult.LastProfile

        val remaining = profiles.filterNot { it.id == id }
        write(remaining)
        val active = activeId()
        val next = if (active == id || active == null || remaining.none { it.id == active }) {
            remaining.first().id
        } else {
            active
        }
        storage.writeActive(next)
        return DeleteResult.Deleted(next)
    }

    private fun write(profiles: List<ShootProfileEntry>) {
        storage.writeProfiles(encode(profiles))
    }

    private fun encode(profiles: List<ShootProfileEntry>): String = buildString {
        append(FORMAT_HEADER)
        profiles.forEach { profile ->
            append('\n')
            append(encodeField(profile.id))
            append('\t')
            append(encodeField(profile.name))
            append('\t')
            append(profile.keys.sorted().joinToString(",") { encodeField(it) })
        }
    }

    private fun decode(raw: String?): MutableList<ShootProfileEntry> {
        if (raw.isNullOrBlank()) return mutableListOf()
        val lines = raw.lines().filter { it.isNotBlank() }
        if (lines.firstOrNull()?.trim() != FORMAT_HEADER) return mutableListOf()
        return lines.drop(1).mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3) return@mapNotNull null
            val id = decodeField(parts[0]) ?: return@mapNotNull null
            val name = decodeField(parts[1]) ?: return@mapNotNull null
            val keys = parts[2]
                .split(',')
                .filter { it.isNotBlank() }
                .mapNotNull(::decodeField)
                .toSet()
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            ShootProfileEntry(id, name, keys)
        }.toMutableList()
    }

    private fun encodeField(value: String): String =
        encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeField(value: String): String? = runCatching {
        String(decoder.decode(value), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        const val DEFAULT_PROFILE_ID = "default"
        const val DEFAULT_PROFILE_NAME = "Default"
        private const val FORMAT_HEADER = "slatecam-profiles-v1"
    }
}
