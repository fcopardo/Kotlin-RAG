package rag.document

import org.junit.jupiter.api.io.TempDir
import rag.structure.ChapterStrategy
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractTest {
    @TempDir
    lateinit var dir: File

    private val agreement = """
        SERVICE AGREEMENT

        WHEREAS the Supplier wishes to provide maintenance services to the Customer;

        1. Definitions

        In this Agreement the following words shall have the meanings set out below, and the
        parties hereby agree that they apply throughout.

        1.1 Services

        "Services" means the maintenance work described in Schedule A, which the Supplier shall
        perform pursuant to this Agreement.

        1.2 Business Hours

        "Business Hours" means 09:00 to 17:00 on any working day.

        2. Term and Termination

        This Agreement shall commence on the Effective Date and continue until terminated herein.

        2.1 Notice

        Either party may terminate this Agreement on thirty days written notice to the other party.

        (a) Notice shall be given in writing and delivered to the address set out above.

        (b) Termination shall not affect any accrued obligations of either party.

        3. Liability

        The Supplier shall indemnify the Customer against any breach of warranty, notwithstanding
        the foregoing limitations on liability.
    """.trimIndent()

    private fun read(name: String, body: String) = TextBookSource().read(File(dir, name).apply { writeText(body) })

    @Test
    fun `a numbered agreement becomes a contract with nested clauses`() {
        val book = read("agreement.txt", agreement)

        assertEquals("contract", book.kind)
        assertEquals(ChapterStrategy.CLAUSES, book.plan.strategy)
        val titles = book.chapters.filter { it.number > 0 }.map { it.title }
        assertTrue(titles.any { it.startsWith("Recital") }, titles.toString())
        assertTrue("1. Definitions" in titles, titles.toString())
        assertTrue("1. Definitions › 1.1 Services" in titles, titles.toString())
        assertTrue("2. Term and Termination › 2.1 Notice" in titles, titles.toString())
        // Lettered items nest under the clause they follow, and are siblings of each other.
        assertTrue(titles.any { it.startsWith("2. Term and Termination › 2.1 Notice › (a)") }, titles.toString())
        assertTrue(titles.any { it.startsWith("2. Term and Termination › 2.1 Notice › (b)") }, titles.toString())

    }

    @Test
    fun `a clause whose body runs on from its name keeps only the name`() {
        // As in the Apache licence: the clause name and its first sentence share a line.
        val text = """
            2. Grant of Copyright License. Subject to the terms and conditions of this Agreement,
            each Contributor hereby grants to You a licence under the terms set out herein.

            3. Redistribution. You may reproduce and distribute copies of the Work, provided that
            the parties shall retain all notices, and nothing herein limits that obligation.

            4. Termination. This licence shall terminate upon breach by either party hereto.

            5. Warranty. The Work is provided without warranties of any kind, whereas liability
            is limited as set out herein.

            6. Liability. No Contributor shall be liable to You for damages pursuant hereto.
        """.trimIndent()

        val clauses = ClauseDetector.detect(text)!!

        assertEquals(
            listOf("2. Grant of Copyright License", "3. Redistribution", "4. Termination", "5. Warranty", "6. Liability"),
            clauses.map { it.title },
        )
    }

    @Test
    fun `a definition clause keeps only the defined term's name`() {
        val text = """
            1.1 Affiliate. "Affiliate" means any entity that controls, or is controlled by, a party hereto.

            1.2 Customer Data. “Customer Data” means all data that the Customer submits under this Agreement.

            1.3 Platform. 'Platform' means the hosted software the parties shall use, as defined herein.

            1.4 Order Form. Order Form means an ordering document signed by both parties hereto.

            1.5 Term. The term of this Agreement shall begin on the effective date, and either party may terminate it.
        """.trimIndent()

        val clauses = ClauseDetector.detect(text)!!

        assertEquals(
            listOf("1.1 Affiliate", "1.2 Customer Data", "1.3 Platform", "1.4 Order Form", "1.5 Term"),
            clauses.map { it.title },
        )
    }

    @Test
    fun `a numbered paper is not a contract`() {
        val paper = """
            A Study of Bicycle Gears

            1. Introduction

            Bicycle gearing has been studied since the nineteenth century, and the ratios chosen
            affect how much effort a rider spends on a climb.

            1.1 Prior work

            Earlier measurements focused on track racing rather than touring.

            2. Method

            We measured cadence and power on a fixed climb using a calibrated power meter.

            3. Results

            Lower ratios reduced peak torque on the chain without changing overall climb time.

            4. Discussion

            The measurements agree with the model proposed in earlier work on chain efficiency.

            5. Conclusion

            Gear choice matters most on sustained climbs.
        """.trimIndent()

        assertNull(ClauseDetector.detect(paper), "numbering alone must not make a contract")
        val book = read("paper.txt", paper)
        assertEquals("paper", book.kind)
    }

    @Test
    fun `a Word contract is detected from its text, not its styles`() {
        // The same agreement as plain paragraphs, with no heading styles at all.
        val file = File(dir, "agreement.html").apply {
            writeText("<html><body>" + agreement.split("\n\n").joinToString("") { "<p>${it.replace("\n", " ")}</p>" } + "</body></html>")
        }

        val book = HtmlBookSource().read(file)

        assertEquals("contract", book.kind)
        assertTrue(book.chapters.any { it.title.startsWith("2. Term and Termination › 2.1 Notice") }, book.chapters.map { it.title }.toString())
    }
}
