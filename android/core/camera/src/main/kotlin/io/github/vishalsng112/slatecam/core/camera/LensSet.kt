package io.github.vishalsng112.slatecam.core.camera

/**
 * How many lenses the operator is recording with.
 *
 * A single lens records with that camera's own capabilities. A lens set keeps
 * one recording mode across at least two cameras, derived from their common
 * capabilities.
 */
enum class LensSetMode(val wireValue: String, val label: String) {
    SINGLE("single", "Single lens"),
    SET("set", "Lens set"),
    ;

    companion object {
        /** A set needs at least two cameras for a survive-the-switch mode. */
        const val MIN_LENSES = 2

        fun fromWire(value: String): LensSetMode = when (value.lowercase()) {
            SET.wireValue, "movie", "vlog" -> SET
            else -> SINGLE
        }
    }
}

/**
 * The selected lenses and mode, with the validation rules in one place.
 *
 * The selected IDs are ordered as the UI presents them. A set never becomes
 * invalid through [toggle]; the bridge/profile path resolves a short set back
 * to single instead of silently keeping a mode that cannot be recorded.
 */
data class LensSet(
    val mode: LensSetMode,
    val selectedIds: List<String>,
) {
    val valid: Boolean
        get() = mode == LensSetMode.SINGLE ||
            selectedIds.size >= LensSetMode.MIN_LENSES

    val count: Int get() = selectedIds.size

    /** False when removing [id] would leave a set below its minimum. */
    fun canDeselect(id: String): Boolean =
        mode != LensSetMode.SET || id !in selectedIds ||
            selectedIds.size > LensSetMode.MIN_LENSES

    fun toggle(id: String, selected: Boolean): LensSet {
        if (selected) {
            if (id in selectedIds) return this
            return copy(selectedIds = selectedIds + id)
        }
        if (!canDeselect(id)) return this
        return copy(selectedIds = selectedIds - id)
    }

    companion object {
        /** Resolve a requested mode against the current selection. */
        fun resolve(
            requested: LensSetMode,
            selectedIds: List<String>,
        ): LensSetMode = if (
            requested == LensSetMode.SET &&
            selectedIds.size >= LensSetMode.MIN_LENSES
        ) {
            LensSetMode.SET
        } else {
            LensSetMode.SINGLE
        }
    }
}

/**
 * Shared recording modes for the lens set, reusing the recording-mode catalog
 * so the set and single-camera paths cannot diverge.
 */
object LensSetModes {
    fun common(
        cameras: List<Pair<CameraCapabilities, RecordingModeCatalog.TenBitVerifier>>,
    ): List<RecordingMode> = RecordingModeCatalog.commonModes(cameras)
}
