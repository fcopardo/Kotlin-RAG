package rag.android

import rag.embed.OnnxModel
import rag.embed.OnnxModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [ModelRoutes]: which models a document is embedded with and which one a search uses (SPEC §19.4). */
class ModelRoutesTest {
    private val mxbai = OnnxModels.MXBAI_EMBED_LARGE_INT8
    private val nomic = OnnxModels.NOMIC_EMBED_TEXT_INT8
    private val tier = DeviceTier.LARGE

    /** An English expert and a shared model, no Spanish expert: the stand-in setup of Phase 10's tests. */
    private val standIn = object : DefaultModelPolicy() {
        override fun expert(language: String, tier: DeviceTier): OnnxModel? = if (language == "en") mxbai else null
        override fun shared(tier: DeviceTier): OnnxModel? = nomic
    }

    /** A Spanish and an English expert, no shared model. */
    private val expertsOnly = object : DefaultModelPolicy() {
        override fun expert(language: String, tier: DeviceTier): OnnxModel? = when (language) {
            "en" -> mxbai
            "es" -> nomic
            else -> null
        }
        override fun shared(tier: DeviceTier): OnnxModel? = null
    }

    @Test
    fun `the default policy routes every language to the device's one model, as before`() {
        val p = DefaultModelPolicy()
        listOf("en", "es", "zxx", "und", "fr").forEach { assertEquals(listOf(mxbai), ModelRoutes.indexModels(p, tier, it), it) }
        assertEquals(listOf(nomic), ModelRoutes.indexModels(p, DeviceTier.SMALL, "es"))
        assertEquals(mxbai, ModelRoutes.searchModel(p, tier, setOf("en")))
        assertEquals(mxbai, ModelRoutes.searchModel(p, tier, setOf("en", "es")))
        assertEquals(mxbai, ModelRoutes.searchModel(p, tier, emptySet()))
    }

    @Test
    fun `a document is embedded with its expert, then the shared model`() {
        assertEquals(listOf(mxbai, nomic), ModelRoutes.indexModels(standIn, tier, "en"))
        assertEquals(listOf(nomic), ModelRoutes.indexModels(standIn, tier, "es"), "no Spanish expert: the shared model only")
        assertEquals(listOf(mxbai, nomic), ModelRoutes.indexModels(standIn, tier, "zxx"), "code counts as English")
        assertEquals(listOf(mxbai, nomic), ModelRoutes.indexModels(standIn, tier, "und"))
        assertEquals(listOf(mxbai), ModelRoutes.indexModels(expertsOnly, tier, "en"))
        assertEquals(emptyList(), ModelRoutes.indexModels(expertsOnly, tier, "fr"), "no expert and no shared model")
    }

    @Test
    fun `one language searches with its expert, several with the shared model`() {
        assertEquals(mxbai, ModelRoutes.searchModel(standIn, tier, setOf("en")))
        assertEquals(mxbai, ModelRoutes.searchModel(standIn, tier, setOf("en", "zxx")), "code beside English is still one language")
        assertEquals(nomic, ModelRoutes.searchModel(standIn, tier, setOf("es")), "no Spanish expert")
        assertEquals(nomic, ModelRoutes.searchModel(standIn, tier, setOf("en", "es")))
        assertEquals(nomic, ModelRoutes.searchModel(standIn, tier, emptySet()))
    }

    @Test
    fun `without a shared model, a search across languages has no common vectors`() {
        assertEquals(nomic, ModelRoutes.searchModel(expertsOnly, tier, setOf("es")))
        assertNull(ModelRoutes.searchModel(expertsOnly, tier, setOf("en", "es")))
        assertEquals(mxbai, ModelRoutes.searchModel(expertsOnly, tier, emptySet()))
    }

    @Test
    fun `a forced policy keeps its base's routes`() {
        val forced = ForcedModelPolicy(maxLoadedModels = 1, base = standIn)
        assertEquals(listOf(mxbai, nomic), ModelRoutes.indexModels(forced, tier, "en"))
        assertEquals(nomic, ModelRoutes.searchModel(forced, tier, setOf("en", "es")))
    }
}
