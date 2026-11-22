package io.github.vishalsng112.slatecam.core.profile

/**
 * Creative LUTs shipped with the app. The warm/cool look exists mainly to prove
 * the GPU LUT path end to end; user LUT files come later.
 */
object BuiltInLuts {

    const val IDENTITY = "identity"
    const val WARM_COOL = "warm-cool"

    const val SIZE = 17

    val identity: Lut3D by lazy {
        Lut3D.fromFunction(SIZE) { r, g, b -> floatArrayOf(r, g, b) }
    }

    /**
     * Warm highlights, cool shadows. Subtle on purpose: a demo look should not
     * destroy the image it is meant to demonstrate.
     */
    val warmCool: Lut3D by lazy {
        Lut3D.fromFunction(SIZE) { r, g, b ->
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val shadow = (1f - luma).coerceIn(0f, 1f)
            val highlight = luma.coerceIn(0f, 1f)
            floatArrayOf(
                (r + highlight * 0.10f - shadow * 0.04f).coerceIn(0f, 1f),
                (g + highlight * 0.02f).coerceIn(0f, 1f),
                (b + shadow * 0.12f - highlight * 0.06f).coerceIn(0f, 1f),
            )
        }
    }

    fun byId(id: String): Lut3D? = when (id) {
        IDENTITY -> identity
        WARM_COOL -> warmCool
        else -> null
    }
}
