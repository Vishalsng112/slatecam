package io.github.vishalsng112.slatecam.core.profile

/** The four built-in picture profiles. */
enum class ProfileKind { STANDARD, SLATE_LOOK, SLATE_LOG, CUSTOM }

/**
 * Look gamma presets. These are creative curves in the delivery domain, not the
 * container transfer: the encoder still writes HLG or Rec.709. `STANDARD` is an
 * identity curve so the Standard profile does no creative processing.
 */
enum class GammaPreset(val wire: String) {
    STANDARD("standard"),
    MOVIE("movie"),
    STILL("still"),
    CINE1("cine1"),
    CINE2("cine2"),
    CINE3("cine3"),
    CINE4("cine4"),
    ITU709("itu709"),
    ITU709_800("itu709_800"),
    SCINETONE("scinetone"),
    ;

    companion object {
        fun fromWire(value: String): GammaPreset =
            entries.firstOrNull { it.wire == value } ?: STANDARD
    }
}

enum class BlackGammaRange(val wire: String) {
    WIDE("wide"),
    MIDDLE("middle"),
    NARROW("narrow"),
    ;

    companion object {
        fun fromWire(value: String): BlackGammaRange =
            entries.firstOrNull { it.wire == value } ?: MIDDLE
    }
}

enum class KneeMode(val wire: String) {
    AUTO("auto"),
    MANUAL("manual"),
    ;

    companion object {
        fun fromWire(value: String): KneeMode =
            entries.firstOrNull { it.wire == value } ?: MANUAL
    }
}

enum class KneeSensitivity(val wire: String) {
    HIGH("high"),
    MID("mid"),
    LOW("low"),
    ;

    companion object {
        fun fromWire(value: String): KneeSensitivity =
            entries.firstOrNull { it.wire == value } ?: MID
    }
}

/**
 * Creative colour matrices in the linear BT.2020 working space. `709`/`BT2020`
 * are identity because the working space already is BT.2020; the other modes are
 * look matrices, not container primaries.
 */
enum class ColorMode(val wire: String) {
    MOVIE("movie"),
    STILL("still"),
    CINEMA("cinema"),
    PRO("pro"),
    ITU709_MATRIX("itu709_matrix"),
    MONO("mono"),
    BT2020("bt2020"),
    BT709("bt709"),
    ;

    val monochrome: Boolean get() = this == MONO

    companion object {
        fun fromWire(value: String): ColorMode =
            entries.firstOrNull { it.wire == value } ?: BT709
    }
}

/**
 * Complete editable settings for a picture profile. Ranges match the UI and are
 * clamped in [normalized], so persisted or Pigeon values can never escape.
 */
data class ProfileSettings(
    /** -15..+15, a creative pedestal in the linear working space. */
    val blackLevel: Int = 0,
    val gamma: GammaPreset = GammaPreset.STANDARD,
    val blackGammaRange: BlackGammaRange = BlackGammaRange.MIDDLE,
    /** -7..+7. */
    val blackGammaLevel: Int = 0,
    val kneeMode: KneeMode = KneeMode.MANUAL,
    /** 90..100. */
    val kneeAutoMaxPoint: Int = 95,
    val kneeAutoSensitivity: KneeSensitivity = KneeSensitivity.MID,
    /** 75..105. */
    val kneePoint: Int = 100,
    /** -5..+5. */
    val kneeSlope: Int = 0,
    val colorMode: ColorMode = ColorMode.BT709,
    /** -32..+32. */
    val saturation: Int = 0,
    /** -7..+7. */
    val colorPhase: Int = 0,
    /** -7..+7 per hue axis. */
    val colorDepthR: Int = 0,
    val colorDepthG: Int = 0,
    val colorDepthB: Int = 0,
    val colorDepthC: Int = 0,
    val colorDepthM: Int = 0,
    val colorDepthY: Int = 0,
) {
    fun normalized(): ProfileSettings = copy(
        blackLevel = blackLevel.coerceIn(-15, 15),
        blackGammaLevel = blackGammaLevel.coerceIn(-7, 7),
        kneeAutoMaxPoint = kneeAutoMaxPoint.coerceIn(90, 100),
        kneePoint = kneePoint.coerceIn(75, 105),
        kneeSlope = kneeSlope.coerceIn(-5, 5),
        saturation = saturation.coerceIn(-32, 32),
        colorPhase = colorPhase.coerceIn(-7, 7),
        colorDepthR = colorDepthR.coerceIn(-7, 7),
        colorDepthG = colorDepthG.coerceIn(-7, 7),
        colorDepthB = colorDepthB.coerceIn(-7, 7),
        colorDepthC = colorDepthC.coerceIn(-7, 7),
        colorDepthM = colorDepthM.coerceIn(-7, 7),
        colorDepthY = colorDepthY.coerceIn(-7, 7),
    )

    val hasColorOps: Boolean
        get() = (
            colorMode != ColorMode.BT709 &&
                colorMode != ColorMode.BT2020 &&
                colorMode != ColorMode.ITU709_MATRIX
            ) ||
            colorPhase != 0 ||
            colorDepthR != 0 || colorDepthG != 0 || colorDepthB != 0 ||
            colorDepthC != 0 || colorDepthM != 0 || colorDepthY != 0

    companion object {
        val IDENTITY = ProfileSettings()
    }
}

/** A resolved picture profile ready for the renderer. */
data class PictureProfile(
    val id: String,
    val kind: ProfileKind,
    val settings: ProfileSettings = ProfileSettings.IDENTITY,
) {
    val logEnabled: Boolean get() = kind == ProfileKind.SLATE_LOG
    val monochrome: Boolean get() = settings.colorMode.monochrome
    val editable: Boolean get() = kind == ProfileKind.CUSTOM

    /** CPU-side look parameters. The GLSL mirrors these mappings. */
    fun render(): ProfileRender {
        val curve = settings.gamma.curve()
        val auto = settings.kneeMode == KneeMode.AUTO
        val autoKnee = settings.kneeAutoMaxPoint / 100f
        val autoSlope = when (settings.kneeAutoSensitivity) {
            KneeSensitivity.HIGH -> 0.35f
            KneeSensitivity.MID -> 0.20f
            KneeSensitivity.LOW -> 0.05f
        }
        return ProfileRender(
            blackLevel = settings.blackLevel / 15f * 0.04f,
            gammaPower = curve.power,
            gammaContrast = curve.contrast,
            gammaToe = curve.toe,
            gammaShoulder = curve.shoulder,
            blackGammaWidth = settings.blackGammaRange.width,
            blackGammaLevel = settings.blackGammaLevel / 7f * 0.5f,
            kneePoint = (if (auto) autoKnee else settings.kneePoint / 100f)
                .coerceIn(0.5f, 1f),
            kneeSlope = (if (auto) autoSlope else settings.kneeSlope / 5f)
                .coerceIn(-1f, 1f),
            colorMatrix = settings.colorMode.matrix(),
            colorOps = settings.hasColorOps,
            colorPhase = settings.colorPhase / 7f * 0.08f,
            colorDepths = floatArrayOf(
                settings.colorDepthR / 7f * 0.6f,
                settings.colorDepthG / 7f * 0.6f,
                settings.colorDepthB / 7f * 0.6f,
                settings.colorDepthC / 7f * 0.6f,
                settings.colorDepthM / 7f * 0.6f,
                settings.colorDepthY / 7f * 0.6f,
            ),
            saturation = 1f + settings.saturation / 32f,
            monochrome = monochrome,
            logPreview = logEnabled,
        )
    }

    companion object {
        private val STANDARD_SETTINGS = ProfileSettings()

        /** A gentle filmic look: soft shoulder, a touch of warmth and colour. */
        private val SLATE_LOOK_SETTINGS = ProfileSettings(
            blackLevel = 1,
            gamma = GammaPreset.CINE2,
            blackGammaRange = BlackGammaRange.MIDDLE,
            blackGammaLevel = 1,
            kneeMode = KneeMode.MANUAL,
            kneePoint = 95,
            kneeSlope = 1,
            colorMode = ColorMode.CINEMA,
            saturation = 4,
        )

        val STANDARD = PictureProfile(
            id = "standard",
            kind = ProfileKind.STANDARD,
            settings = STANDARD_SETTINGS,
        )

        val SLATE_LOOK = PictureProfile(
            id = "slatelook",
            kind = ProfileKind.SLATE_LOOK,
            settings = SLATE_LOOK_SETTINGS,
        )

        /** Flat preview; the encoder still receives HLG or Rec.709. */
        val SLATE_LOG = PictureProfile(
            id = "slatelog",
            kind = ProfileKind.SLATE_LOG,
            settings = ProfileSettings.IDENTITY,
        )

        val CUSTOM = PictureProfile(
            id = "custom",
            kind = ProfileKind.CUSTOM,
            settings = ProfileSettings.IDENTITY,
        )

        val all: List<PictureProfile> =
            listOf(STANDARD, SLATE_LOOK, SLATE_LOG, CUSTOM)

        fun custom(settings: ProfileSettings): PictureProfile =
            CUSTOM.copy(settings = settings.normalized())

        fun byId(id: String, customSettings: ProfileSettings = ProfileSettings.IDENTITY): PictureProfile =
            when (id) {
                SLATE_LOOK.id -> SLATE_LOOK
                SLATE_LOG.id -> SLATE_LOG
                CUSTOM.id -> custom(customSettings)
                else -> STANDARD
            }
    }
}

/** Primitive look values the renderer applies. */
data class ProfileRender(
    val blackLevel: Float,
    val gammaPower: Float,
    val gammaContrast: Float,
    val gammaToe: Float,
    val gammaShoulder: Float,
    val blackGammaWidth: Float,
    val blackGammaLevel: Float,
    val kneePoint: Float,
    val kneeSlope: Float,
    val colorMatrix: FloatArray,
    val colorOps: Boolean,
    val colorPhase: Float,
    val colorDepths: FloatArray,
    val saturation: Float,
    val monochrome: Boolean,
    val logPreview: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProfileRender) return false
        return blackLevel == other.blackLevel &&
            gammaPower == other.gammaPower &&
            gammaContrast == other.gammaContrast &&
            gammaToe == other.gammaToe &&
            gammaShoulder == other.gammaShoulder &&
            blackGammaWidth == other.blackGammaWidth &&
            blackGammaLevel == other.blackGammaLevel &&
            kneePoint == other.kneePoint &&
            kneeSlope == other.kneeSlope &&
            colorMatrix.contentEquals(other.colorMatrix) &&
            colorOps == other.colorOps &&
            colorPhase == other.colorPhase &&
            colorDepths.contentEquals(other.colorDepths) &&
            saturation == other.saturation &&
            monochrome == other.monochrome &&
            logPreview == other.logPreview
    }

    override fun hashCode(): Int {
        var result = blackLevel.hashCode()
        result = 31 * result + gammaPower.hashCode()
        result = 31 * result + gammaContrast.hashCode()
        result = 31 * result + gammaToe.hashCode()
        result = 31 * result + gammaShoulder.hashCode()
        result = 31 * result + blackGammaWidth.hashCode()
        result = 31 * result + blackGammaLevel.hashCode()
        result = 31 * result + kneePoint.hashCode()
        result = 31 * result + kneeSlope.hashCode()
        result = 31 * result + colorMatrix.contentHashCode()
        result = 31 * result + colorOps.hashCode()
        result = 31 * result + colorPhase.hashCode()
        result = 31 * result + colorDepths.contentHashCode()
        result = 31 * result + saturation.hashCode()
        result = 31 * result + monochrome.hashCode()
        result = 31 * result + logPreview.hashCode()
        return result
    }
}

/** Parameter set for one look-gamma preset. Values are deliberately modest. */
data class GammaCurve(
    val power: Float,
    val contrast: Float,
    val toe: Float,
    val shoulder: Float,
)

private fun GammaPreset.curve(): GammaCurve = when (this) {
    GammaPreset.STANDARD -> GammaCurve(1f, 1f, 0f, 0f)
    GammaPreset.ITU709 -> GammaCurve(1f, 1f, 0f, 0f)
    GammaPreset.MOVIE -> GammaCurve(0.96f, 1.04f, 0.01f, 0.15f)
    GammaPreset.STILL -> GammaCurve(0.92f, 1.08f, 0f, 0.10f)
    GammaPreset.CINE1 -> GammaCurve(1.04f, 0.96f, 0.03f, 0.25f)
    GammaPreset.CINE2 -> GammaCurve(1.00f, 1.00f, 0.03f, 0.30f)
    GammaPreset.CINE3 -> GammaCurve(1.08f, 0.93f, 0.04f, 0.35f)
    GammaPreset.CINE4 -> GammaCurve(1.14f, 0.88f, 0.05f, 0.40f)
    GammaPreset.ITU709_800 -> GammaCurve(0.88f, 1.12f, 0f, 0.05f)
    GammaPreset.SCINETONE -> GammaCurve(1.00f, 1.02f, 0.02f, 0.32f)
}

private fun ColorMode.matrix(): FloatArray = when (this) {
    ColorMode.MOVIE -> floatArrayOf(
        1.02f, 0.01f, -0.01f,
        0.00f, 1.00f, 0.00f,
        -0.01f, 0.01f, 1.01f,
    )
    ColorMode.STILL -> floatArrayOf(
        1.05f, 0.00f, -0.01f,
        0.00f, 1.03f, 0.00f,
        -0.01f, 0.00f, 1.02f,
    )
    ColorMode.CINEMA -> floatArrayOf(
        1.01f, 0.02f, -0.01f,
        0.01f, 1.00f, 0.01f,
        -0.02f, 0.01f, 1.02f,
    )
    ColorMode.PRO -> floatArrayOf(
        0.98f, 0.01f, 0.01f,
        0.01f, 0.98f, 0.01f,
        0.01f, 0.01f, 0.98f,
    )
    ColorMode.ITU709_MATRIX -> floatArrayOf(
        1.00f, 0.00f, 0.00f,
        0.00f, 1.00f, 0.00f,
        0.00f, 0.00f, 1.00f,
    )
    ColorMode.MONO, ColorMode.BT2020, ColorMode.BT709 -> floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )
}

private val BlackGammaRange.width: Float
    get() = when (this) {
        BlackGammaRange.WIDE -> 0.30f
        BlackGammaRange.MIDDLE -> 0.18f
        BlackGammaRange.NARROW -> 0.10f
    }
