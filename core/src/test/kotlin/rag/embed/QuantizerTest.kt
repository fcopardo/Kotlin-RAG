package rag.embed

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuantizerTest {

    private fun randomUnit(dim: Int, seed: Int) =
        Vectors.l2Normalize(Random(seed).let { r -> FloatArray(dim) { r.nextFloat() * 2 - 1 } })

    private fun norm(v: FloatArray) = sqrt(v.sumOf { (it * it).toDouble() })

    @Test
    fun `truncation keeps the leading dimensions and unit length`() {
        val v = randomUnit(768, 1)

        val t = Quantizer.truncate(v, 256)

        assertEquals(256, t.size)
        assertEquals(1.0, norm(t), 1e-5)
        // Same direction as the leading slice.
        assertEquals(1.0, Vectors.dot(t, Vectors.l2Normalize(v.copyOf(256))).toDouble(), 1e-5)
    }

    @Test
    fun `truncation dimension must be a multiple of 64 and not exceed the vector`() {
        assertFailsWith<IllegalArgumentException> { Quantizer.truncate(FloatArray(768), 100) }
        assertFailsWith<IllegalArgumentException> { Quantizer.truncate(FloatArray(128), 256) }
    }

    @Test
    fun `int8 round trip error is at most half a step`() {
        val v = randomUnit(256, 2)

        val q = Quantizer.quantize(v)

        v.indices.forEach { i -> assertTrue(abs(q.int8[i] * q.scale - v[i]) <= q.scale / 2 + 1e-7f) }
        assertEquals(127, q.int8.maxOf { abs(it.toInt()) })
    }

    @Test
    fun `bits follow the signs`() {
        val v = FloatArray(128) { if (it % 3 == 0) 0.5f else -0.5f }

        val bits = Quantizer.quantize(v).bits

        assertEquals(2, bits.size)
        v.indices.forEach { i -> assertEquals(v[i] > 0, (bits[i / 64] ushr (i % 64)) and 1L == 1L) }
    }

    @Test
    fun `hamming distance to itself is 0 and to its negation is dim`() {
        val v = randomUnit(256, 3)
        val neg = FloatArray(256) { -v[it] }

        assertEquals(0, Quantizer.hamming(Quantizer.bits(v), Quantizer.bits(v)))
        assertEquals(256, Quantizer.hamming(Quantizer.bits(v), Quantizer.bits(neg)))
    }

    @Test
    fun `int8 dot product approximates the float one`() {
        val a = randomUnit(256, 4)
        val b = randomUnit(256, 5)
        val q = Quantizer.quantize(b)

        assertEquals(Vectors.dot(a, b), Quantizer.dot(a, q.int8, q.scale), 0.01f)
    }

    @Test
    fun `bits survive the blob round trip`() {
        val bits = Quantizer.bits(randomUnit(256, 6))

        assertContentEquals(bits, Quantizer.blobToBits(Quantizer.bitsToBlob(bits)))
        assertEquals(32, Quantizer.bitsToBlob(bits).size)
    }
}
