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
 * Picks the embedding model for a phone. The library doesn't download models: the app ships or
 * fetches the files [OnnxModel] names (`modelFile`, `vocabFile` from `repository`) and opens them
 * with [OnnxModel.open].
 *
 * An index searched on a phone must have vectors from that phone's model; `rag embed --add` puts
 * both models' vectors in one desktop-built DB. Books without them are searched by keywords.
 */
object RagModels {
    /**
     * 5 GiB: phones sold as 6 GB report about 5.3–5.6 GiB, since the kernel and firmware keep
     * the rest. Measured: the large model peaks at ≈ 600 MB while indexing (SPEC §15.6).
     */
    const val LARGE_TIER_MIN_BYTES = 5L * 1024 * 1024 * 1024

    fun tier(memory: DeviceMemory): DeviceTier =
        if (!memory.lowRam && memory.totalBytes >= LARGE_TIER_MIN_BYTES) DeviceTier.LARGE else DeviceTier.SMALL

    fun model(tier: DeviceTier): OnnxModel = when (tier) {
        DeviceTier.LARGE -> OnnxModels.MXBAI_EMBED_LARGE_INT8
        DeviceTier.SMALL -> OnnxModels.NOMIC_EMBED_TEXT_INT8
    }

    /**
     * ONNX Runtime settings per tier. The small tier turns the CPU memory arena off: on the S25 that
     * kept the indexing peak at 384 MB instead of 425 MB, for 59 s instead of 45 s on a 205-page book.
     */
    fun sessionOptions(tier: DeviceTier): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        if (tier == DeviceTier.SMALL) {
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        }
    }

    /** Opens the tier's model from [dir], which holds the files of [OnnxModel.repository]. */
    fun open(tier: DeviceTier, dir: File): OnnxEmbedder = model(tier).open(dir, sessionOptions(tier))

    /** The model for this phone, unless the app forces a tier. */
    fun forDevice(context: Context, override: DeviceTier? = null): OnnxModel = model(override ?: tier(memory(context)))

    fun memory(context: Context): DeviceMemory {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return DeviceMemory(info.totalMem, manager.isLowRamDevice)
    }
}
