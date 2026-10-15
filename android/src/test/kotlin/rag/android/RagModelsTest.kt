package rag.android

import rag.embed.OnnxModels
import kotlin.test.Test
import kotlin.test.assertEquals

class RagModelsTest {
    private val gib = 1024L * 1024 * 1024

    @Test
    fun `phones sold as 6 GB or more get the large model`() {
        // What phones report: the kernel keeps part of the advertised RAM.
        assertEquals(DeviceTier.LARGE, RagModels.tier(DeviceMemory((5.4 * gib).toLong(), lowRam = false))) // "6 GB"
        assertEquals(DeviceTier.LARGE, RagModels.tier(DeviceMemory((10.9 * gib).toLong(), lowRam = false))) // "12 GB" (S25)
        assertEquals(OnnxModels.MXBAI_EMBED_LARGE_INT8, RagModels.model(DeviceTier.LARGE))
    }

    @Test
    fun `smaller or low-RAM phones get the small model`() {
        assertEquals(DeviceTier.SMALL, RagModels.tier(DeviceMemory((3.6 * gib).toLong(), lowRam = false))) // "4 GB"
        assertEquals(DeviceTier.SMALL, RagModels.tier(DeviceMemory((7.5 * gib).toLong(), lowRam = true)))
        assertEquals(OnnxModels.NOMIC_EMBED_TEXT_INT8, RagModels.model(DeviceTier.SMALL))
    }
}
