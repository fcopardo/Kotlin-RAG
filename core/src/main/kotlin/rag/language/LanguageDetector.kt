package rag.language

import java.util.Locale

/**
 * Detects the language of a text (SPEC §19.4, Phase 10 step 3): an ISO 639-1 code such as "en" or "es", or
 * [UNDETERMINED] ("und") when the text is too short or no language clearly wins. No library and no model: Latin-script
 * languages by their most frequent function words, other languages by their writing system. Only English and Spanish
 * must be told apart exactly (they get expert models); a wrong guess between others only changes which shared-model
 * route a document takes, and the caller can always state the language instead.
 */
object LanguageDetector {
    const val UNDETERMINED = "und"

    /** Fewest letters a text needs before any answer. */
    const val MIN_LETTERS = 20

    /** Fewest words a Latin-script text needs. */
    const val MIN_WORDS = 30

    /**
     * The most frequent function words of each Latin-script language, as written (lower case). A word listed for
     * more than one language says nothing about which it is: [PROFILES] leaves those out.
     */
    val FUNCTION_WORDS: Map<String, String> = mapOf(
        "en" to """
            the of and to is that it for was with as his they be at by this had not are but from have which
            you were her she there would their we him been has when who will more if out so said what its
            about into than them can only other could these may then do first any my now such our over even
            most made after also did many before must through where much your should because each just those
            how too very own between both being under never while might us since against
        """,
        "es" to """
            el los las del que y en un una por con para es se su sus al lo como más pero ya muy también fue
            era había han ha este esta estos estas ese esa eso todo todos sin sobre entre cuando hasta desde
            donde porque cual ni le les nos él ella ellos yo usted cada ser estaba están tiene tenía hay así
            de
        """,
        "pt" to """
            o os as do da dos das um uma não que em no na nos nas com para por é foi era seu sua seus suas
            ao aos mais mas também como já muito ele ela eles elas isso este esta esse essa você ser está
            são tem tinha pelo pela entre quando sem sobre até onde porque então ainda de
        """,
        "fr" to """
            le les des du et est une un que qui dans pour pas sur au aux avec ce cette ces il elle ils elles
            nous vous je mais où son sa ses leur leurs été était avait sont ont être avoir plus tout tous
            comme fait très aussi bien même encore donc alors chez de
        """,
        "de" to """
            der die das und ist ein eine einer einem einen nicht mit sich auf für von dem den des zu im auch
            als wie bei aus nach oder aber wenn noch wird werden wurde sind war hat haben ich er sie wir ihr
            es dass schon nur kann über unter durch
        """,
        "it" to """
            il lo gli la le di del della delle dei degli e è che un una non per con sono era ho ha hanno nel
            nella sul sulla alla al anche come più ma se questo questa quello quella dopo molto tutto tutti
            già perché quando ancora
        """,
        "nl" to """
            de het een en van is dat niet op te zijn met voor als aan er maar om hij zij ze wij ik je ook
            nog bij uit dan naar was werd wordt worden heeft hebben kan zou door over geen wel al
        """,
    )

    /**
     * [FUNCTION_WORDS] as sets, split on whitespace, without any word that appears in more than one language's list
     * (e.g. "de", "que", "en", "un"). Keys in the same order as [FUNCTION_WORDS].
     */
    val PROFILES: Map<String, Set<String>> by lazy {
        // Split each language's function words on whitespace.
        // Count in how many lists each word appears.
        // Keep only words that appear in exactly one list.
        // Keep the map's key order (LinkedHashMap).
        val wordToLangs = mutableMapOf<String, MutableSet<String>>()
        for ((lang, wordsStr) in FUNCTION_WORDS) {
            for (word in wordsStr.trim().split(Regex("\\s+"))) {
                if (word.isEmpty()) continue
                wordToLangs.getOrPut(word) { mutableSetOf() }.add(lang)
            }
        }
        // Build profiles: only words appearing in exactly one language
        val result = linkedMapOf<String, Set<String>>()
        for ((lang, wordsStr) in FUNCTION_WORDS) {
            result[lang] = wordsStr.trim().split(Regex("\\s+"))
                .filter { it.isNotEmpty() && wordToLangs[it]?.size == 1 }
                .toSet()
        }
        result
    }

    /**
     * The language of [text].
     *
     * 1. Letters are the characters for which `Character.isLetter` is true; each is counted under its
     *    `Character.UnicodeScript`. Fewer than [MIN_LETTERS] letters → [UNDETERMINED].
     * 2. If one script other than LATIN holds at least half of the letters, the answer comes from the script:
     *    HAN → "zh", except "ja" when HIRAGANA and KATAKANA letters together are at least a tenth of all letters;
     *    HIRAGANA or KATAKANA → "ja"; HANGUL → "ko"; CYRILLIC → "ru"; ARABIC → "ar"; GREEK → "el"; HEBREW → "he";
     *    DEVANAGARI → "hi"; THAI → "th"; any other script → [UNDETERMINED]. (HIRAGANA and KATAKANA count as one
     *    script for the "half" test.)
     * 3. Otherwise, words are the maximal runs of letters, lower-cased with `Locale.ROOT`. Fewer than [MIN_WORDS]
     *    words → [UNDETERMINED].
     * 4. Each language in [PROFILES] scores the number of words (with repeats) in its set. Take the best and the
     *    second best score.
     * 5. The best language wins only if its score is at least a tenth of the words AND at least twice the second
     *    best score; otherwise [UNDETERMINED]. (A text half English, half Spanish is undetermined.)
     *
     * Linear in the text's length.
     */
    fun detect(text: String): String {
        // Step 1: Count letters per Unicode script
        val scriptCounts = mutableMapOf<Character.UnicodeScript, Int>()
        var totalLetters = 0
        var hiraganaCount = 0
        var katakanaCount = 0

        for (ch in text) {
            if (!Character.isLetter(ch)) continue
            val script = Character.UnicodeScript.of(ch.code)
            if (script == Character.UnicodeScript.HIRAGANA) {
                hiraganaCount++
                totalLetters++
                scriptCounts.merge(script, 1, Int::plus)
            } else if (script == Character.UnicodeScript.KATAKANA) {
                katakanaCount++
                totalLetters++
                scriptCounts.merge(script, 1, Int::plus)
            } else {
                totalLetters++
                scriptCounts.merge(script, 1, Int::plus)
            }
        }

        if (totalLetters < MIN_LETTERS) return UNDETERMINED

        // Step 2: Script-based detection
        // For the "half" test, treat Hiragana and Katakana together
        val hiraKataCombined = hiraganaCount + katakanaCount

        // Find the script with the highest count (considering Hira+Kata combined)
        var bestScript: Character.UnicodeScript? = null
        var bestScriptCount = 0

        for ((script, count) in scriptCounts) {
            val adjustedCount = if (script == Character.UnicodeScript.HIRAGANA) {
                hiraKataCombined
            } else if (script == Character.UnicodeScript.KATAKANA) {
                hiraKataCombined
            } else {
                count
            }
            if (adjustedCount > bestScriptCount) {
                bestScriptCount = adjustedCount
                bestScript = script
            }
        }

        if (bestScript != Character.UnicodeScript.LATIN && bestScriptCount * 2 >= totalLetters) {
            // Map script to language
            return when (bestScript) {
                Character.UnicodeScript.HAN -> {
                    // "ja" when Hiragana + Katakana letters together are at least a tenth of all letters
                    if (hiraKataCombined * 10 >= totalLetters) "ja" else "zh"
                }
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "ja"
                Character.UnicodeScript.HANGUL -> "ko"
                Character.UnicodeScript.CYRILLIC -> "ru"
                Character.UnicodeScript.ARABIC -> "ar"
                Character.UnicodeScript.GREEK -> "el"
                Character.UnicodeScript.HEBREW -> "he"
                Character.UnicodeScript.DEVANAGARI -> "hi"
                Character.UnicodeScript.THAI -> "th"
                else -> UNDETERMINED
            }
        }

        // Step 3: Word-based detection for Latin-script texts
        // Words are maximal runs of letters, lower-cased with Locale.ROOT
        val words = mutableListOf<String>()
        var currentWord = StringBuilder()
        for (ch in text) {
            if (Character.isLetter(ch)) {
                currentWord.append(ch)
            } else {
                if (currentWord.isNotEmpty()) {
                    words.add(currentWord.toString().lowercase(Locale.ROOT))
                    currentWord.clear()
                }
            }
        }
        if (currentWord.isNotEmpty()) {
            words.add(currentWord.toString().lowercase(Locale.ROOT))
        }

        if (words.size < MIN_WORDS) return UNDETERMINED

        // Step 4: Score each language
        var bestScore = 0
        var bestLang = ""
        var secondBestScore = 0

        for ((lang, profile) in PROFILES) {
            var score = 0
            for (word in words) {
                if (word in profile) score++
            }
            if (score > bestScore) {
                secondBestScore = bestScore
                bestScore = score
                bestLang = lang
            } else if (score > secondBestScore) {
                secondBestScore = score
            }
        }

        // Step 5: Best must be >= 1/10 of total words AND >= 2x second best
        if (bestScore * 10 >= words.size && bestScore >= secondBestScore * 2) {
            return bestLang
        }

        return UNDETERMINED
    }

    /**
     * The language of a whole document's text, from samples spread over it, so a preface or a licence in another
     * language doesn't decide it. A text of at most [DOCUMENT_SAMPLE_CHARS] × 5 characters is detected whole. A
     * longer one is detected from 5 windows of [DOCUMENT_SAMPLE_CHARS] characters joined with a newline, window i
     * (0..4) starting at `(text.length - DOCUMENT_SAMPLE_CHARS) * i / 4`.
     */
    fun detectDocument(text: String): String {
        if (text.length <= DOCUMENT_SAMPLE_CHARS * 5) {
            return detect(text)
        }
        // 5 windows of DOCUMENT_SAMPLE_CHARS characters
        // window i (0..4) starting at (text.length - DOCUMENT_SAMPLE_CHARS) * i / 4
        val start = text.length - DOCUMENT_SAMPLE_CHARS
        val builder = StringBuilder()
        for (i in 0..4) {
            if (i > 0) builder.append('\n')
            val offset = start * i / 4
            if (offset + DOCUMENT_SAMPLE_CHARS <= text.length) {
                builder.append(text, offset, offset + DOCUMENT_SAMPLE_CHARS)
            } else {
                builder.append(text, offset, text.length)
            }
        }
        return detect(builder.toString())
    }

    const val DOCUMENT_SAMPLE_CHARS = 4000

}
