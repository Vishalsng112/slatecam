package io.github.vishalsng112.slatecam.core.gpu

/**
 * One analysis frame produced by the GL thread.
 *
 * The histogram and waveform drive the monitoring UI; the normalised luma grid
 * drives exposure metering. The grid is row-major and follows the upright
 * analysis frame, so centre/spot patterns stay centred on the viewfinder.
 */
data class FrameAnalysis(
    val histogram: IntArray,
    val waveform: IntArray,
    val lumaGrid: FloatArray,
    val gridWidth: Int,
    val gridHeight: Int,
    /** Normalised Laplacian energy in the central focus ROI. */
    val focusScore: Float,
)
