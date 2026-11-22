package io.github.vishalsng112.slatecam.core.probe.eval

/**
 * Classifies physical cameras relative to each other by horizontal field of view.
 *
 * Roles are relative on purpose: a phone's "wide" is another phone's
 * "ultrawide". Nothing here assumes a camera ID or a device model.
 */
object LensRoles {

    fun classify(fovs: List<Double?>): List<String?> {
        val valid = fovs.withIndex().filter { it.value != null }
        if (valid.isEmpty()) return List(fovs.size) { null }

        val roles = MutableList<String?>(fovs.size) { null }
        if (valid.size == 1) {
            roles[valid.first().index] = "wide"
            return roles
        }

        val sorted = valid.sortedByDescending { it.value!! }
        roles[sorted.first().index] = "ultrawide"
        roles[sorted.last().index] = if (valid.size == 2) "wide" else "tele"
        for (entry in valid) {
            if (roles[entry.index] == null) roles[entry.index] = "wide"
        }
        return roles
    }
}
