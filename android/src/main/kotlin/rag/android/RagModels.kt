package rag.android

import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import rag.embed.OnnxEmbedder
import rag.embed.OnnxModel
import rag.embed.OnnxModels
import java.io.File

/** Phones with about 6 GB of RAM or more run the large model; others the small one (SPEC §15.9). */
enum class DeviceTier { LARGE, SMALL }

/** Total RAM and Android's low-RAM flag. */
data class DeviceMemory(val totalBytes: Long, val lowRam: Boolean)

/**
 * Picks the embedding model for a phone, by the rules of [ModelPolicies.current] (SPEC §19.2). The library doesn't download models: the app ships or
 * fetches the files [OnnxModel] names (`modelFile`, `vocabFile` from `repository`) and opens them
 * with [OnnxModel.open].
 *
 * An index searched on a phone must have vectors from that phone's model; `rag embed --add` puts
 * both models' vectors in one desktop-built DB. Books without them are searched by keywords.
 */
object RagModels {
    /** See [DefaultModelPolicy.LARGE_TIER_MIN_BYTES]. */
    const val LARGE_TIER_MIN_BYTES = DefaultModelPolicy.LARGE_TIER_MIN_BYTES

    /** The tier [ModelPolicies.current] gives this memory. */
    fun tier(memory: DeviceMemory): DeviceTier = ModelPolicies.current.tier(memory)

    /** The model [ModelPolicies.current] gives this tier. */
    fun model(tier: DeviceTier): OnnxModel = ModelPolicies.current.model(tier)

    /** ONNX Runtime settings per tier, from [ModelPolicies.current]. */
    fun sessionOptions(tier: DeviceTier): OrtSession.SessionOptions = ModelPolicies.current.sessionOptions(tier)

    /** Opens the tier's model from [dir], which holds the files of [OnnxModel.repository]. */
    fun open(tier: DeviceTier, dir: File): OnnxEmbedder = model(tier).open(dir, sessionOptions(tier))

    /** The model for this phone, unless the app forces a tier. */
    fun forDevice(context: Context, override: DeviceTier? = null): OnnxModel = model(override ?: tier(memory(context)))

    /**
     * A provider for [RagEngine] that opens models from `modelsRoot/<model.directoryName>` (the layout of
     * docs/ANDROID.md §4) when their files are there, keeping as many loaded as the engine's policy allows.
     */
    fun provider(context: Context, modelsRoot: File): CachingModelProvider =
        CachingModelProvider(memory(context)) { model, tier, policy ->
            val dir = File(modelsRoot, model.directoryName)
            if (dir.resolve(model.modelFile).isFile && dir.resolve(model.vocabFile).isFile) model.open(dir, policy.sessionOptions(tier)) else null
        }

    fun memory(context: Context): DeviceMemory {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return DeviceMemory(info.totalMem, manager.isLowRamDevice)
    }
}
