package io.github.vishalsng112.slatecam.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShootProfileStoreTest {
    private class FakeStorage : ShootProfileStorage {
        var profilesRaw: String? = null
        var active: String? = null
        var profileWrites = 0
        var activeWrites = 0

        override fun readProfiles(): String? = profilesRaw
        override fun writeProfiles(raw: String) {
            profilesRaw = raw
            profileWrites++
        }

        override fun readActive(): String? = active
        override fun writeActive(id: String) {
            active = id
            activeWrites++
        }
    }

    private val ids = ArrayDeque(listOf("id-1", "id-2", "id-3"))
    private val storage = FakeStorage()
    private val store = ShootProfileStore(storage) { ids.removeFirst() }

    private val back25 = ShootLensRef("0", shootLensKey("back", "default", 25.0))
    private val front26 = ShootLensRef("1", shootLensKey("front", "default", 26.0))
    private val ultra16 = ShootLensRef("2", shootLensKey("back", "ultrawide", 16.0))
    private val eligible = listOf(back25, front26, ultra16)

    @Test
    fun `first use seeds Default from the current selection`() {
        val profiles = store.ensure(setOf(back25.key, front26.key))

        assertEquals(1, profiles.size)
        val default = profiles.single()
        assertEquals(ShootProfileStore.DEFAULT_PROFILE_ID, default.id)
        assertEquals("Default", default.name)
        assertEquals(setOf(back25.key, front26.key), default.keys)
        assertEquals(default.id, store.activeId())
    }

    @Test
    fun `ensure does not duplicate Default on later calls`() {
        store.ensure(setOf(back25.key))
        val writesAfterSeed = storage.profileWrites

        val profiles = store.ensure(setOf(front26.key))

        assertEquals(1, profiles.size)
        assertEquals(writesAfterSeed, storage.profileWrites)
    }

    @Test
    fun `ensure repairs an active id that no longer exists`() {
        store.ensure(setOf(back25.key))
        storage.active = "missing"

        store.ensure(setOf(back25.key))

        assertEquals(ShootProfileStore.DEFAULT_PROFILE_ID, store.activeId())
    }

    @Test
    fun `profiles round-trip ids, names and keys`() {
        val written = ShootProfileStore(storage) { "a" }
        written.ensure(setOf(back25.key))
        written.save("Pad · Movie / v2", setOf(back25.key, front26.key, ultra16.key))

        val reopened = ShootProfileStore(storage) { "unused" }
        val profiles = reopened.ensure(emptySet())

        assertEquals(2, profiles.size)
        val saved = profiles.last()
        assertEquals("a", saved.id)
        assertEquals("Pad · Movie / v2", saved.name)
        assertEquals(setOf(back25.key, front26.key, ultra16.key), saved.keys)
        assertEquals("a", reopened.activeId())
    }

    @Test
    fun `unknown storage format is discarded instead of crashing`() {
        storage.profilesRaw = "{\"profiles\":[]}"

        val profiles = store.ensure(setOf(back25.key))

        assertEquals(1, profiles.size)
        assertEquals(ShootProfileStore.DEFAULT_PROFILE_ID, profiles.single().id)
    }

    @Test
    fun `save creates a new active profile`() {
        store.ensure(setOf(back25.key))

        val saved = store.save("Two lenses", setOf(back25.key, front26.key))

        assertEquals("id-1", saved?.id)
        assertEquals("id-1", store.activeId())
        assertEquals(2, store.profiles().size)
    }

    @Test
    fun `save matches names case-insensitively and updates in place`() {
        store.ensure(setOf(back25.key))
        val first = store.save("pad movie", setOf(back25.key))
        val second = store.save("PAD MOVIE", setOf(back25.key, front26.key))

        assertEquals(first?.id, second?.id)
        assertEquals(2, store.profiles().size)
        val updated = store.profiles().first { it.id == first?.id }
        assertEquals("PAD MOVIE", updated.name)
        assertEquals(setOf(back25.key, front26.key), updated.keys)
    }

    @Test
    fun `save rejects a blank name`() {
        store.ensure(setOf(back25.key))

        assertNull(store.save("   ", setOf(front26.key)))
        assertEquals(1, store.profiles().size)
    }

    @Test
    fun `updateActive rewrites only the active profile`() {
        store.ensure(setOf(back25.key))
        store.save("Wide", setOf(ultra16.key))
        val wideId = store.activeId()
        store.setActive(ShootProfileStore.DEFAULT_PROFILE_ID)

        store.updateActive(setOf(back25.key, front26.key))

        val profiles = store.profiles()
        val default = profiles.first { it.id == ShootProfileStore.DEFAULT_PROFILE_ID }
        val wide = profiles.first { it.id == wideId }
        assertEquals(setOf(back25.key, front26.key), default.keys)
        assertEquals(setOf(ultra16.key), wide.keys)
    }

    @Test
    fun `rename only touches the matching profile`() {
        store.ensure(setOf(back25.key))
        val saved = store.save("Wide", setOf(ultra16.key))

        assertTrue(store.rename(saved!!.id, "Tele"))
        assertFalse(store.rename("missing", "Nope"))

        val names = store.profiles().associate { it.id to it.name }
        assertEquals("Default", names[ShootProfileStore.DEFAULT_PROFILE_ID])
        assertEquals("Tele", names[saved.id])
    }

    @Test
    fun `deleting the active profile falls back to the remaining one`() {
        store.ensure(setOf(back25.key))
        val saved = store.save("Wide", setOf(ultra16.key))
        store.setActive(saved!!.id)

        val result = store.delete(saved.id)

        assertEquals(DeleteResult.Deleted(ShootProfileStore.DEFAULT_PROFILE_ID), result)
        assertEquals(ShootProfileStore.DEFAULT_PROFILE_ID, store.activeId())
        assertEquals(1, store.profiles().size)
    }

    @Test
    fun `deleting an inactive profile keeps the active one`() {
        store.ensure(setOf(back25.key))
        val saved = store.save("Wide", setOf(ultra16.key))
        store.setActive(ShootProfileStore.DEFAULT_PROFILE_ID)

        assertEquals(DeleteResult.Deleted(ShootProfileStore.DEFAULT_PROFILE_ID), store.delete(saved!!.id))
        assertEquals(ShootProfileStore.DEFAULT_PROFILE_ID, store.activeId())
    }

    @Test
    fun `the last profile cannot be deleted`() {
        store.ensure(setOf(back25.key))

        assertEquals(
            DeleteResult.LastProfile,
            store.delete(ShootProfileStore.DEFAULT_PROFILE_ID),
        )
        assertEquals(1, store.profiles().size)
    }

    @Test
    fun `deleting an unknown profile is a no-op`() {
        store.ensure(setOf(back25.key))

        assertEquals(DeleteResult.NotFound, store.delete("missing"))
        assertEquals(1, store.profiles().size)
    }

    @Test
    fun `selection ignores keys the device no longer has`() {
        store.ensure(setOf(ultra16.key, front26.key))
        val default = store.profiles().single()

        val selected = store.selectionOf(default, listOf(back25, front26))

        assertEquals(setOf(front26.id), selected)
        assertEquals(1, store.keyCount(default, listOf(back25, front26)))
    }

    @Test
    fun `keysOf maps selected ids and ignores unknown ids`() {
        val keys = store.keysOf(setOf("0", "2", "99"), eligible)

        assertEquals(setOf(back25.key, ultra16.key), keys)
    }

    @Test
    fun `lens keys round the focal length and tolerate a missing value`() {
        assertEquals("back:default:25", shootLensKey("back", "default", 24.6))
        assertEquals("front:default:0", shootLensKey("front", "default", null))
        assertEquals("back:tele:75", shootLensKey("back", "tele", 75.0))
    }
}
