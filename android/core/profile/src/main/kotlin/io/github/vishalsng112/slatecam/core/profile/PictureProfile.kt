package io.github.vishalsng112.slatecam.core.profile

enum class ProfileKind { STANDARD, SLATE_LOG, MONO }

/**
 * A picture profile is a small, explicit set of GPU parameters plus an optional
 * log encoding. It is deliberately data, not behaviour: the shader applies it,
 * so preview and recording see the same transform.
 */
data class PictureProfile(
    val id: String,
    val kind: ProfileKind,
    val exposure: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val lutId: String? = null,
) {
    val logEnabled: Boolean get() = kind == ProfileKind.SLATE_LOG
    val monochrome: Boolean get() = kind == ProfileKind.MONO

    companion object {
        val STANDARD = PictureProfile(
            id = "standard",
            kind = ProfileKind.STANDARD,
        )

        /** Flat, wide-latitude look intended for grading. */
        val SLATE_LOG = PictureProfile(
            id = "slatelog",
            kind = ProfileKind.SLATE_LOG,
            contrast = 0.9f,
            saturation = 0.85f,
        )

        val MONO = PictureProfile(
            id = "mono",
            kind = ProfileKind.MONO,
            saturation = 0f,
        )

        /** Demonstrates the GPU LUT path with a built-in warm/cool look. */
        val WARM = PictureProfile(
            id = "warm",
            kind = ProfileKind.STANDARD,
            lutId = BuiltInLuts.WARM_COOL,
        )

        val all: List<PictureProfile> = listOf(STANDARD, SLATE_LOG, MONO, WARM)

        fun byId(id: String): PictureProfile = all.firstOrNull { it.id == id } ?: STANDARD
    }
}
