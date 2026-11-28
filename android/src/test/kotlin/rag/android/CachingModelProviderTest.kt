package rag.android

import rag.embed.Embedder
import rag.embed.OnnxModel
import rag.embed.OnnxModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [CachingModelProvider]: loads what the policy picks and never holds more models than it allows (SPEC §19.2). */
class CachingModelProviderTest {
    private val gib = 1024L * 1024 * 1024
    private val phone12 = DeviceMemory((10.9 * gib).toLong(), lowRam = false)
    private val phone4 = DeviceMemory((3.6 * gib).toLong(), lowRam = false)
    private val mxbai = OnnxModels.MXBAI_EMBED_LARGE_INT8
    private val nomic = OnnxModels.NOMIC_EMBED_TEXT_INT8
    private val third = mxbai.copy(storedName = "third-model")

    /** Records every open and close, and the most models open at once. */
    private class Recorder {
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val calls = mutableListOf<Triple<String, DeviceTier, ModelPolicy>>()
        var open = 0
        var peak = 0
        var missing = setOf<String>()

        inner class Fake(override val model: String) : Embedder, AutoCloseable {
            override val dim = 4
            override fun embedDocuments(texts: List<String>) = texts.map { FloatArray(dim) }
            override fun embedQuery(text: String) = FloatArray(dim)
            override fun close() {
                closed += model
                open--
            }
        }

        fun open(model: OnnxModel, tier: DeviceTier, policy: ModelPolicy): Embedder? {
            calls += Triple(model.storedName, tier, policy)
            if (model.storedName in missing) return null
            opened += model.storedName
            open++
            peak = maxOf(peak, open)
            return Fake(model.storedName)
        }
    }

    private fun provider(memory: DeviceMemory, policy: ModelPolicy, recorder: Recorder) =
        CachingModelProvider(memory, recorder::open).apply { bind(policy) }

    @Test
    fun `asking before bind fails`() {
        val p = CachingModelProvider(phone12) { _, _, _ -> null }
        assertFailsWith<IllegalStateException> { p.embedder() }
        assertFailsWith<IllegalStateException> { p.embedderFor(mxbai) }
    }

    @Test
    fun `the device's model is opened once and kept`() {
        val r = Recorder()
        val p = provider(phone12, DefaultModelPolicy(), r)
        val first = p.embedder()!!
        assertEquals(mxbai.storedName, first.model)
        assertSame(first, p.embedder())
        assertEquals(listOf(mxbai.storedName), r.opened)
        assertEquals(listOf(mxbai.storedName), p.loaded())
    }

    @Test
    fun `the open call gets the bound policy and the device's tier`() {
        val r = Recorder()
        val policy = ForcedModelPolicy(tier = DeviceTier.SMALL)
        provider(phone12, policy, r).embedder()
        assertEquals(Triple(nomic.storedName, DeviceTier.SMALL, policy as ModelPolicy), r.calls.single())
    }

    @Test
    fun `missing files give null and are tried again later`() {
        val r = Recorder().apply { missing = setOf(mxbai.storedName) }
        val p = provider(phone12, DefaultModelPolicy(), r)
        assertNull(p.embedder())
        assertNull(p.embedder())
        assertEquals(2, r.calls.size)
        assertEquals(emptyList(), p.loaded())
        r.missing = emptySet()
        assertEquals(mxbai.storedName, p.embedder()!!.model)
    }

    @Test
    fun `one model at a time, asking for another unloads the first`() {
        val r = Recorder()
        val p = provider(phone12, ForcedModelPolicy(maxLoadedModels = 1), r)
        p.embedderFor(mxbai)
        p.embedderFor(nomic)
        assertEquals(listOf(mxbai.storedName), r.closed)
        assertEquals(listOf(nomic.storedName), p.loaded())
        p.embedderFor(mxbai)
        p.embedderFor(nomic)
        assertEquals(1, r.peak, "never two models open at once")
        assertEquals(4, r.opened.size)
    }

    @Test
    fun `room is made before opening, even when the new model turns out to be missing`() {
        val r = Recorder().apply { missing = setOf(nomic.storedName) }
        val p = provider(phone12, ForcedModelPolicy(maxLoadedModels = 1), r)
        p.embedderFor(mxbai)
        assertNull(p.embedderFor(nomic))
        assertEquals(listOf(mxbai.storedName), r.closed)
        assertEquals(emptyList(), p.loaded())
    }

    @Test
    fun `a small device holds one model under the default policy`() {
        val r = Recorder()
        val p = provider(phone4, DefaultModelPolicy(), r)
        p.embedderFor(nomic)
        p.embedderFor(mxbai)
        assertEquals(1, r.peak)
    }

    @Test
    fun `two at a time, the least recently used is the one unloaded`() {
        val r = Recorder()
        val p = provider(phone12, DefaultModelPolicy(), r)
        val a = p.embedderFor(mxbai)
        p.embedderFor(nomic)
        assertSame(a, p.embedderFor(mxbai)) // mxbai used last: nomic is now the oldest
        assertEquals(listOf(nomic.storedName, mxbai.storedName), p.loaded())
        p.embedderFor(third)
        assertEquals(listOf(nomic.storedName), r.closed)
        assertEquals(listOf(mxbai.storedName, "third-model"), p.loaded())
        assertEquals(2, r.peak)
    }

    @Test
    fun `close unloads everything, and the provider reopens on demand`() {
        val r = Recorder()
        val p = provider(phone12, DefaultModelPolicy(), r)
        val before = p.embedderFor(mxbai)
        p.embedderFor(nomic)
        p.close()
        assertEquals(setOf(mxbai.storedName, nomic.storedName), r.closed.toSet())
        assertEquals(emptyList(), p.loaded())
        assertEquals(0, r.open)
        val after = p.embedder()
        assertNotSame(before, after)
        assertTrue(r.opened.size == 3)
    }

    @Test
    fun `an embedder that isn't closeable is just forgotten`() {
        val plain = object : Embedder {
            override val model = mxbai.storedName
            override val dim = 4
            override fun embedDocuments(texts: List<String>) = texts.map { FloatArray(dim) }
            override fun embedQuery(text: String) = FloatArray(dim)
        }
        val p = CachingModelProvider(phone12) { m, _, _ -> if (m == mxbai) plain else null }.apply { bind(ForcedModelPolicy(maxLoadedModels = 1)) }
        assertSame(plain, p.embedderFor(mxbai))
        p.close()
        assertEquals(emptyList(), p.loaded())
    }
}
