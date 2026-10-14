package rag.extract

import java.security.MessageDigest
import java.text.Normalizer as JdkNormalizer

/** Text normalization and unit hashing (SPEC §5.2). Pure: no PDF or IO dependencies. */
object Normalizer {

    private val lineEndings = Regex("\r\n?")
    // Both sides lowercase letters, so "exam-\nple" joins but "well-\nKnown" and "COVID-\n19" don't.
    private val lineEndHyphen = Regex("(\\p{Ll})-\\n[ \\t]*(\\p{Ll})")
    private val horizontalSpace = Regex("[ \\t\\x0B\\f]+")
    private val trailingSpace = Regex(" +(?=\\n)| +$")
    private val extraBlankLines = Regex("\\n{3,}")
    private val anyWhitespace = Regex("\\s+")

    /** Produces the stored "book text" form. */
    fun normalize(raw: String): String {
        var text = JdkNormalizer.normalize(raw, JdkNormalizer.Form.NFKC)
        text = lineEndings.replace(text, "\n")
        text = lineEndHyphen.replace(text, "$1$2")
        text = horizontalSpace.replace(text, " ")
        text = trailingSpace.replace(text, "")
        text = extraBlankLines.replace(text, "\n\n")
        return text
    }

    /** Extra normalization applied only before hashing. */
    fun hashNormalize(text: String): String =
        anyWhitespace.replace(text.lowercase(), " ").trim()

    /** First 16 hex chars of SHA-256 over the hash-normalized text. */
    fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(hashNormalize(text).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }
}
