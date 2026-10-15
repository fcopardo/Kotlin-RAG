package rag.embed

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A vector stored two ways (SPEC §5.6): int8 with a per-vector scale (v[i] ≈ int8[i] * scale),
 * for rescoring, and sign bits packed in 64-bit words, for the Hamming first pass.
 */
class QuantizedVector(val scale: Float, val int8: ByteArray, val bits: LongArray) {
    val dim: Int get() = int8.size
}

/** Matryoshka truncation, int8 and binary quantization (SPEC §5.6, §8.3). Pure. */
object Quantizer {

    /** First [dim] dimensions, normalized to unit length again. [dim] must be a multiple of 64. */
    fun truncate(v: FloatArray, dim: Int): FloatArray {
        require(dim > 0 && dim % 64 == 0) { "embedding dimension must be a positive multiple of 64, was $dim" }
        require(dim <= v.size) { "can't truncate a ${v.size}-dimensional vector to $dim" }
        return Vectors.l2Normalize(v.copyOf(dim))
    }

    fun quantize(v: FloatArray): QuantizedVector {
        val max = v.maxOfOrNull { abs(it) } ?: 0f
        val scale = if (max == 0f) 1f else max / 127f
        val int8 = ByteArray(v.size) { (v[it] / scale).roundToInt().coerceIn(-127, 127).toByte() }
        return QuantizedVector(scale, int8, bits(v))
    }

    /** Bit i is set when v[i] > 0. */
    fun bits(v: FloatArray): LongArray {
        val words = LongArray((v.size + 63) / 64)
        for (i in v.indices) if (v[i] > 0f) words[i / 64] = words[i / 64] or (1L shl (i % 64))
        return words
    }

    fun hamming(a: LongArray, b: LongArray): Int {
        var d = 0
        for (i in a.indices) d += java.lang.Long.bitCount(a[i] xor b[i])
        return d
    }

    /** Dot product of a float query with an int8 vector. */
    fun dot(query: FloatArray, int8: ByteArray, scale: Float): Float {
        require(query.size == int8.size) { "dimension mismatch: ${query.size} vs ${int8.size}" }
        var sum = 0f
        for (i in query.indices) sum += query[i] * int8[i]
        return sum * scale
    }

    fun bitsToBlob(bits: LongArray): ByteArray =
        ByteBuffer.allocate(bits.size * 8).order(ByteOrder.LITTLE_ENDIAN).apply { bits.forEach(::putLong) }.array()

    fun blobToBits(blob: ByteArray): LongArray {
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return LongArray(blob.size / 8) { buffer.long }
    }
}
