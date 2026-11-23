package io.github.vishalsng112.slatecam.core.ai

/**
 * Safe default until the Phase 6 implementation lands.
 *
 * It reports no capabilities, accepts configuration and frames without doing
 * work, and never calls a listener. Wiring this by default keeps feature code
 * compilable while guaranteeing that AI stays disabled on every device.
 */
class NoOpAiEngine : AiEngine {

    override val capabilities: AiCapabilities = AiCapabilities()

    @Volatile
    private var config: AiConfig = AiConfig()

    override fun configure(config: AiConfig) {
        this.config = config
    }

    fun currentConfig(): AiConfig = config

    override fun submit(frame: AiFrame) = Unit

    override fun setListener(listener: AiListener?) = Unit

    override fun reset() = Unit

    override fun close() = Unit
}
