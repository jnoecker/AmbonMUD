package dev.ambon.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImagesConfigTest {
    @Test
    fun `arcanum folio assets are registered under the canonical keys`() {
        assertEquals(
            "global_assets/arcanum_bg.png",
            ImagesConfig.DEFAULT_GLOBAL_ASSETS["arcanum_bg"],
        )
        assertEquals(
            "global_assets/arcanum_bg_portrait.png",
            ImagesConfig.DEFAULT_GLOBAL_ASSETS["arcanum_bg_portrait"],
        )
    }

    @Test
    fun `akathavae shrine assets are registered under the canonical keys`() {
        assertEquals(
            "global_assets/shrine_bg.png",
            ImagesConfig.DEFAULT_GLOBAL_ASSETS["shrine_bg"],
        )
        assertEquals(
            "global_assets/shrine_bg_portrait.png",
            ImagesConfig.DEFAULT_GLOBAL_ASSETS["shrine_bg_portrait"],
        )
    }
}
