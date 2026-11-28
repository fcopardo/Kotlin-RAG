package rag.android

import ai.onnxruntime.OrtSession
import rag.embed.OnnxModel
import rag.embed.OnnxModels

/**
 * The rules for which embedding model a device uses and how many models it may keep loaded (SPEC §19.2).
 * Pure decisions over plain inputs: no loading and no I/O, so a ruleset is tested with made-up devices.
 * [RagModels] and [RagEngine]'s providers ask [ModelPolicies.current]; [ModelPolicies.force] replaces it.
 */
interface ModelPolicy {
    /** The device's tier, from its memory. */
    fun tier(memory: DeviceMemory): DeviceTier

    /** The embedding model for a tier. */
    fun model(tier: DeviceTier): OnnxModel

    /** ONNX Runtime settings for a tier's model. */
    fun sessionOptions(tier: DeviceTier): OrtSession.SessionOptions

    /** How many embedding models may be loaded at the same time on this device (at least 1). */
    fun maxLoadedModels(memory: DeviceMemory): Int

    /**
     * The expert model for documents and queries in [language] (ISO 639) on a [tier] device, or null when the language
     * has none (SPEC §19.4). Default: the tier's model, for every language, as before languages existed.
     */
    fun expert(language: String, tier: DeviceTier): OnnxModel? = model(tier)

    /**
     * The shared model, for searches across languages and for languages without an expert, or null when there is none.
     * Default: the tier's model.
     */
    fun shared(tier: DeviceTier): OnnxModel? = model(tier)
}

/**
 * Which models a document is embedded with and which one a search uses (SPEC §19.4), from a policy's experts and
 * shared model. Code ("zxx") and undetermined text ("und") count as English: their words are English identifiers.
 */
object ModelRoutes {
    private fun routed(language: String): String =
        if (language == "zxx" || language == "und") "en" else language.lowercase()

    /** Every model a document in [language] needs vectors from, its expert first; duplicates once. */
    fun indexModels(policy: ModelPolicy, tier: DeviceTier, language: String): List<OnnxModel> =
        listOfNotNull(policy.expert(routed(language), tier), policy.shared(tier)).distinct()

    /**
     * The model a search over documents in [languages] uses: the expert when they are one language (or, without one,
     * the shared model); the shared model across languages (or, without one, the experts' model if they all share it).
     */
    fun searchModel(policy: ModelPolicy, tier: DeviceTier, languages: Set<String>): OnnxModel? {
        val routed = languages.map(::routed).toSet()
        if (routed.size == 1) return policy.expert(routed.single(), tier) ?: policy.shared(tier)
        return policy.shared(tier) ?: routed.mapNotNull { policy.expert(it, tier) }.distinct().singleOrNull()
            ?: if (routed.isEmpty()) policy.expert("en", tier) else null
    }
}

/**
 * Today's rules (SPEC §15.9): about 6 GB of RAM or more and not a low-RAM device is the large tier,
 * `mxbai-embed-large` int8; anything else the small tier, `nomic-embed-text` int8 with the CPU memory
 * arena off. A large-tier device may keep two models loaded; a small one, one at a time.
 */
open class DefaultModelPolicy : ModelPolicy {
    override fun tier(memory: DeviceMemory): DeviceTier =
        if (!memory.lowRam && memory.totalBytes >= LARGE_TIER_MIN_BYTES) DeviceTier.LARGE else DeviceTier.SMALL

    override fun model(tier: DeviceTier): OnnxModel = when (tier) {
        DeviceTier.LARGE -> OnnxModels.MXBAI_EMBED_LARGE_INT8
        DeviceTier.SMALL -> OnnxModels.NOMIC_EMBED_TEXT_INT8
    }

    /**
     * The small tier turns the CPU memory arena off: on the S25 that kept the indexing peak at 384 MB
     * instead of 425 MB, for 59 s instead of 45 s on a 205-page book.
     */
    override fun sessionOptions(tier: DeviceTier): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        if (tier == DeviceTier.SMALL) {
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        }
    }

    override fun maxLoadedModels(memory: DeviceMemory): Int = if (tier(memory) == DeviceTier.LARGE) 2 else 1

    companion object {
        /**
         * 5 GiB: phones sold as 6 GB report about 5.3–5.6 GiB, since the kernel and firmware keep
         * the rest. Measured: the large model peaks at ≈ 600 MB while indexing (SPEC §15.6).
         */
        const val LARGE_TIER_MIN_BYTES = 5L * 1024 * 1024 * 1024
    }
}

/**
 * [base]'s rules, except what is forced: a [tier] whatever the device's memory, and at most [maxLoadedModels]
 * models at once (for example 1, to swap models even on a device that could hold two).
 */
class ForcedModelPolicy(
    private val tier: DeviceTier? = null,
    private val maxLoadedModels: Int? = null,
    private val base: ModelPolicy = DefaultModelPolicy(),
) : ModelPolicy by base {
    init {
        require(maxLoadedModels == null || maxLoadedModels >= 1) { "maxLoadedModels must be at least 1, was $maxLoadedModels" }
    }

    override fun tier(memory: DeviceMemory): DeviceTier = tier ?: base.tier(memory)

    override fun maxLoadedModels(memory: DeviceMemory): Int = maxLoadedModels ?: base.maxLoadedModels(memory)
}

/**
 * The policy in force for the process: the default one unless [force]d. Forcing applies to everything that asks
 * afterwards ([RagModels], providers created after the call); an engine keeps the policy it was given.
 */
object ModelPolicies {
    val DEFAULT: ModelPolicy = DefaultModelPolicy()

    @Volatile
    var current: ModelPolicy = DEFAULT
        private set

    /** Uses [policy] from now on, e.g. `force(ForcedModelPolicy(maxLoadedModels = 1))` on a device that should swap. */
    fun force(policy: ModelPolicy) {
        current = policy
    }

    /** Back to [DEFAULT]. */
    fun reset() {
        current = DEFAULT
    }
}
