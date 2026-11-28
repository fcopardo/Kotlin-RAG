package rag.android

import rag.embed.Embedder
import rag.embed.OnnxModel
import java.io.Closeable

/**
 * Loads and unloads embedding models for a [RagEngine] (SPEC §19.2). The engine hands it its [ModelPolicy]
 * with [bind], then asks it for what each operation needs. It owns what it loads; which model, and how many
 * at once, the policy decides.
 */
interface ModelProvider : Closeable {
    /** The policy to follow; the engine calls this once, before anything else. */
    fun bind(policy: ModelPolicy)

    /** The embedder for indexing and searching on this device, loading it if needed; null means keywords only. */
    fun embedder(): Embedder?
}

/** One embedder chosen by the caller, or none: what `RagEngine(context, dbFile, embedder)` uses. Never closes it. */
class FixedModelProvider(private val embedder: Embedder?) : ModelProvider {
    override fun bind(policy: ModelPolicy) {}

    override fun embedder(): Embedder? = embedder

    /** The embedder belongs to the caller. */
    override fun close() {}
}

/**
 * Opens models through [open] (which returns null when a model's files are missing), keeps them loaded, and never
 * holds more than the bound policy's [ModelPolicy.maxLoadedModels] for [memory] at once: asking for one more
 * closes the least recently used first. [embedder] is the bound policy's model for this device's tier.
 *
 * @param open opens a model for a tier under a policy (whose [ModelPolicy.sessionOptions] it applies); the result
 *   is closed when evicted if it is [AutoCloseable].
 */
class CachingModelProvider(
    private val memory: DeviceMemory,
    private val open: (OnnxModel, DeviceTier, ModelPolicy) -> Embedder?,
) : ModelProvider {
    private var policy: ModelPolicy? = null

    /** Loaded embedders in access order: least recently used first. */
    private val loaded = LinkedHashMap<OnnxModel, Embedder>(0, 0.75f, true)

    /**
     * Rules:
     * 1. [bind] stores the policy. [embedder] and [embedderFor] before [bind] fail with IllegalStateException.
     * 2. [embedder] = `embedderFor(policy.model(policy.tier(memory)))`.
     * 3. [embedderFor] returns the loaded embedder for [model] if there is one (and marks it most recently used).
     *    Otherwise it first closes least recently used embedders until fewer than `policy.maxLoadedModels(memory)`
     *    remain (BEFORE opening: loading takes the memory, so the limit holds while the new one loads), then opens
     *    it with `open(model, policy.tier(memory), policy)` and keeps it as the most recently used. A null result
     *    is returned and not remembered (the files may arrive later); what was closed to make room stays closed.
     * 4. Closing means calling `close()` when the embedder is [AutoCloseable], and forgetting it.
     * 5. [loaded] lists the loaded models' `storedName`s, least recently used first.
     * 6. [close] closes every loaded embedder; the provider can be used again afterwards (it reopens on demand).
     */
    override fun bind(policy: ModelPolicy) {
        this.policy = policy
    }

    private fun requireBound() {
        check(policy != null) { "bind() must be called before using CachingModelProvider" }
    }

    override fun embedder(): Embedder? {
        requireBound()
        val p = policy!!
        return embedderFor(p.model(p.tier(memory)))
    }

    /** The embedder for [model], loading it (and unloading others) as the policy allows. */
    fun embedderFor(model: OnnxModel): Embedder? {
        requireBound()
        // A read moves it to the newest position (access order).
        loaded[model]?.let { return it }
        // Need to open: make room first by evicting least recently used
        val max = policy!!.maxLoadedModels(memory)
        while (loaded.size >= max) {
            val oldest = loaded.entries.first()
            loaded.remove(oldest.key)
            oldest.value.closeSilent()
        }
        // Open the model
        val embedder = open(model, policy!!.tier(memory), policy!!)
        if (embedder != null) {
            loaded[model] = embedder
        }
        return embedder
    }

    /** Stored names of the loaded models, least recently used first. */
    fun loaded(): List<String> = loaded.keys.map { it.storedName }

    override fun close() {
        loaded.values.forEach { it.closeSilent() }
        loaded.clear()
    }
}

private fun Embedder.closeSilent() {
    if (this is AutoCloseable) close()
}
