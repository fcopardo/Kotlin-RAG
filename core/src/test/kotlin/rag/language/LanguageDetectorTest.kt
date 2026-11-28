package rag.language

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [LanguageDetector] (SPEC §19.4, Phase 10 step 3). Every text below was written for this test. */
@Timeout(10)
class LanguageDetectorTest {
    private val texts = mapOf(
        "en" to "The library reads each book once and stores its text with the chapters, paragraphs and sentences it found. When you ask a question, it looks for the words you typed and for passages that mean the same thing, and then it shows you where they are in the book. Nothing is sent to a remote service, so the books and the questions stay on your own computer or phone, and the search keeps working without a network.",
        "es" to "La biblioteca lee cada libro una sola vez y guarda su texto con los capítulos, párrafos y frases que encontró. Cuando haces una pregunta, busca las palabras que escribiste y también los pasajes que significan lo mismo, y luego te muestra dónde están en el libro. Nada se envía a un servicio remoto, así que los libros y las preguntas se quedan en tu propio computador o teléfono, y la búsqueda sigue funcionando sin red.",
        "pt" to "A biblioteca lê cada livro uma única vez e guarda o seu texto com os capítulos, parágrafos e frases que encontrou. Quando você faz uma pergunta, ela procura as palavras que você escreveu e também os trechos que significam a mesma coisa, e depois mostra onde eles estão no livro. Nada é enviado para um serviço remoto, então os livros e as perguntas ficam no seu próprio computador ou telefone, e a busca continua funcionando sem rede.",
        "fr" to "La bibliothèque lit chaque livre une seule fois et garde son texte avec les chapitres, les paragraphes et les phrases qu'elle a trouvés. Quand vous posez une question, elle cherche les mots que vous avez tapés et aussi les passages qui veulent dire la même chose, puis elle vous montre où ils se trouvent dans le livre. Rien n'est envoyé à un service distant, donc les livres et les questions restent sur votre ordinateur ou votre téléphone, et la recherche fonctionne encore sans réseau.",
        "de" to "Die Bibliothek liest jedes Buch nur einmal und speichert seinen Text mit den Kapiteln, Absätzen und Sätzen, die sie gefunden hat. Wenn du eine Frage stellst, sucht sie nach den Wörtern, die du eingegeben hast, und auch nach Stellen, die dasselbe bedeuten, und dann zeigt sie dir, wo sie im Buch stehen. Nichts wird an einen entfernten Dienst geschickt, also bleiben die Bücher und die Fragen auf deinem eigenen Rechner oder Telefon, und die Suche funktioniert auch ohne Netz.",
        "it" to "La biblioteca legge ogni libro una sola volta e conserva il suo testo con i capitoli, i paragrafi e le frasi che ha trovato. Quando fai una domanda, cerca le parole che hai scritto e anche i passaggi che significano la stessa cosa, e poi ti mostra dove si trovano nel libro. Niente viene inviato a un servizio remoto, quindi i libri e le domande restano sul tuo computer o sul tuo telefono, e la ricerca funziona anche senza rete.",
        "nl" to "De bibliotheek leest elk boek maar één keer en bewaart de tekst met de hoofdstukken, alinea's en zinnen die ze heeft gevonden. Als je een vraag stelt, zoekt ze naar de woorden die je hebt getypt en ook naar passages die hetzelfde betekenen, en dan laat ze je zien waar ze in het boek staan. Niets wordt naar een externe dienst gestuurd, dus de boeken en de vragen blijven op je eigen computer of telefoon, en het zoeken werkt ook zonder netwerk.",
    )

    @Test
    fun `seven Latin-script languages are told apart`() {
        texts.forEach { (code, text) -> assertEquals(code, LanguageDetector.detect(text), "the $code text") }
    }

    @Test
    fun `other languages come from their writing system`() {
        assertEquals("zh", LanguageDetector.detect("这个图书馆只读取每本书一次，并保存它找到的章节、段落和句子。"))
        assertEquals("ja", LanguageDetector.detect("この図書館は本を一度だけ読み、見つけた章や段落や文と一緒にテキストを保存します。"))
        assertEquals("ko", LanguageDetector.detect("이 도서관은 각 책을 한 번만 읽고 찾은 장과 단락과 문장과 함께 텍스트를 저장합니다."))
        assertEquals("ru", LanguageDetector.detect("Библиотека читает каждую книгу только один раз и сохраняет её текст вместе с главами и абзацами."))
        assertEquals("ar", LanguageDetector.detect("تقرأ المكتبة كل كتاب مرة واحدة فقط وتحفظ نصه مع الفصول والفقرات والجمل التي وجدتها."))
        assertEquals("el", LanguageDetector.detect("Η βιβλιοθήκη διαβάζει κάθε βιβλίο μόνο μία φορά και κρατά το κείμενό του."))
    }

    @Test
    fun `short, empty and letterless texts are undetermined`() {
        assertEquals("und", LanguageDetector.detect(""))
        assertEquals("und", LanguageDetector.detect("12345 67890 !!! ??? 3.14 -> {}"))
        assertEquals("und", LanguageDetector.detect("coroutine best practices"))
        assertEquals("und", LanguageDetector.detect("buenas prácticas de corrutinas en kotlin"))
        assertEquals("und", LanguageDetector.detect("中文"))
    }

    @Test
    fun `a text half English and half Spanish is undetermined`() {
        assertEquals("und", LanguageDetector.detect(texts.getValue("en") + " " + texts.getValue("es")))
    }

    @Test
    fun `words shared by two languages are left out of the profiles`() {
        val profiles = LanguageDetector.PROFILES
        assertEquals(LanguageDetector.FUNCTION_WORDS.keys.toList(), profiles.keys.toList())
        val all = profiles.values.flatten()
        assertEquals(all.size, all.toSet().size, "no word in two profiles")
        listOf("de", "que", "en", "un", "il", "is", "was", "les").forEach { w -> assertFalse(profiles.values.any { w in it }, "'$w' is shared") }
        assertTrue("the" in profiles.getValue("en"))
        assertTrue("el" in profiles.getValue("es"))
        assertTrue("não" in profiles.getValue("pt"))
        assertTrue("und" in profiles.getValue("de"))
    }

    @Test
    fun `a document is detected from samples, so a preface in another language doesn't decide it`() {
        val spanishBody = (texts.getValue("es") + "\n").repeat(150)
        val preface = texts.getValue("en").repeat(7).take(3000)
        val document = preface + "\n" + spanishBody
        assertEquals("en", LanguageDetector.detect(document.take(LanguageDetector.DOCUMENT_SAMPLE_CHARS)), "its first window alone")
        assertEquals("es", LanguageDetector.detectDocument(document))
        assertEquals("en", LanguageDetector.detectDocument((texts.getValue("en") + "\n").repeat(150)))
        // Short documents are read whole.
        assertEquals("de", LanguageDetector.detectDocument(texts.getValue("de")))
    }

    @Test
    fun `detection is linear`() {
        val big = (texts.getValue("fr") + "\n").repeat(20_000) // about 9 MB
        val t = System.nanoTime()
        assertEquals("fr", LanguageDetector.detect(big))
        assertTrue((System.nanoTime() - t) / 1_000_000 < 5_000, "9 MB in under 5 s")
    }
}
