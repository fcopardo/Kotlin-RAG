package rag.android

import rag.embed.OnnxModels
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** The model rules (SPEC §19.2): the default policy, forced policies, and forcing one for the process. */
class ModelPolicyTest {
    private val gib = 1024L * 1024 * 1024
    private val phone4 = DeviceMemory((3.6 * gib).toLong(), lowRam = false) // sold as 4 GB
    private val phone6 = DeviceMemory((5.4 * gib).toLong(), lowRam = false) // sold as 6 GB
    private val phone12 = DeviceMemory((10.9 * gib).toLong(), lowRam = false) // sold as 12 GB (S25)
    private val lowRam8 = DeviceMemory((7.5 * gib).toLong(), lowRam = true)
    private val default = DefaultModelPolicy()

    @AfterTest
    fun resetPolicy() = ModelPolicies.reset()

    @Test
    fun `the default policy keeps today's tiers and models`() {
        assertEquals(DeviceTier.SMALL, default.tier(phone4))
        assertEquals(DeviceTier.LARGE, default.tier(phone6))
        assertEquals(DeviceTier.LARGE, default.tier(phone12))
        assertEquals(DeviceTier.SMALL, default.tier(lowRam8))
        assertEquals(DeviceTier.LARGE, default.tier(DeviceMemory(DefaultModelPolicy.LARGE_TIER_MIN_BYTES, lowRam = false)))
        assertEquals(DeviceTier.SMALL, default.tier(DeviceMemory(DefaultModelPolicy.LARGE_TIER_MIN_BYTES - 1, lowRam = false)))
        assertEquals(OnnxModels.MXBAI_EMBED_LARGE_INT8, default.model(DeviceTier.LARGE))
        assertEquals(OnnxModels.NOMIC_EMBED_TEXT_INT8, default.model(DeviceTier.SMALL))
    }

    @Test
    fun `a large-tier device may hold two models, a small one only one`() {
        assertEquals(2, default.maxLoadedModels(phone6))
        assertEquals(2, default.maxLoadedModels(phone12))
        assertEquals(1, default.maxLoadedModels(phone4))
        assertEquals(1, default.maxLoadedModels(lowRam8))
    }

    @Test
    fun `a forced tier wins over the device's memory`() {
        val small = ForcedModelPolicy(tier = DeviceTier.SMALL)
        assertEquals(DeviceTier.SMALL, small.tier(phone12))
        assertEquals(OnnxModels.NOMIC_EMBED_TEXT_INT8, small.model(small.tier(phone12)))
        val large = ForcedModelPolicy(tier = DeviceTier.LARGE)
        assertEquals(DeviceTier.LARGE, large.tier(phone4))
        assertEquals(OnnxModels.MXBAI_EMBED_LARGE_INT8, large.model(large.tier(phone4)))
    }

    @Test
    fun `a forced model count wins, and the rest stays the base policy's`() {
        val swap = ForcedModelPolicy(maxLoadedModels = 1)
        assertEquals(1, swap.maxLoadedModels(phone12))
        assertEquals(DeviceTier.LARGE, swap.tier(phone12))
        assertEquals(OnnxModels.MXBAI_EMBED_LARGE_INT8, swap.model(DeviceTier.LARGE))
        // Nothing forced: the base policy throughout.
        val none = ForcedModelPolicy()
        listOf(phone4, phone6, phone12, lowRam8).forEach { m ->
            assertEquals(default.tier(m), none.tier(m))
            assertEquals(default.maxLoadedModels(m), none.maxLoadedModels(m))
        }
    }

    @Test
    fun `a forced policy can sit on another base`() {
        val base = ForcedModelPolicy(tier = DeviceTier.SMALL)
        val both = ForcedModelPolicy(maxLoadedModels = 1, base = base)
        assertEquals(DeviceTier.SMALL, both.tier(phone12))
        assertEquals(1, both.maxLoadedModels(phone12))
    }

    @Test
    fun `fewer than one loaded model is refused`() {
        assertFailsWith<IllegalArgumentException> { ForcedModelPolicy(maxLoadedModels = 0) }
    }

    @Test
    fun `forcing a policy changes what RagModels answers until reset`() {
        assertSame(ModelPolicies.DEFAULT, ModelPolicies.current)
        assertEquals(DeviceTier.LARGE, RagModels.tier(phone12))

        ModelPolicies.force(ForcedModelPolicy(tier = DeviceTier.SMALL))
        assertEquals(DeviceTier.SMALL, RagModels.tier(phone12))
        assertEquals(OnnxModels.NOMIC_EMBED_TEXT_INT8, RagModels.model(RagModels.tier(phone12)))

        ModelPolicies.reset()
        assertSame(ModelPolicies.DEFAULT, ModelPolicies.current)
        assertEquals(DeviceTier.LARGE, RagModels.tier(phone12))
    }
}
