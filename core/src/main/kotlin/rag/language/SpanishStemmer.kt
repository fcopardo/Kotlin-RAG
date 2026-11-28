package rag.language

/**
 * The Snowball Spanish stemmer (snowballstem.org; the reference source is `core/src/test/resources/snowball/spanish.sbl`,
 * BSD-licensed), ported to Kotlin so keyword search conflates "biblioteca"/"bibliotecas" and "preguntaba"/"preguntar"
 * the same way on the desktop and on a phone (SPEC §19.4, Phase 10 step 6).
 *
 * Input: one lower-case word. Output: its stem, lower-case, without acute accents. Words are processed whole; there is
 * no minimum length.
 *
 * **Letters.** Vowels are `a e i o u á é í ó ú ü`; every other character is a non-vowel.
 *
 * **Regions** (positions are indexes into the original word; each region is "from that index to the end"; a region that
 * doesn't exist starts at the word's length). A suffix is "in" a region when its first character's index is at least
 * the region's start.
 * - **RV** (index `pV`), on the original word `w`:
 *   - `w[0]` is a vowel:
 *     - if `w[1]` is a non-vowel, `pV` = 1 + the index of the first vowel at index 2 or later;
 *     - if that fails (no such vowel, or `w[1]` is a vowel): if `w[1]` is a vowel, `pV` = 1 + the index of the first
 *       non-vowel at index 2 or later;
 *     - if both fail, `pV` = length.
 *   - `w[0]` is a non-vowel:
 *     - if `w[1]` is a non-vowel, `pV` = 1 + the index of the first vowel at index 2 or later;
 *     - else if `w[1]` is a vowel, `pV` = 3 (the position after the third letter), if the word has at least 3 letters;
 *     - otherwise `pV` = length.
 *   - Words shorter than 2 letters: `pV` = length.
 * - **R1** (`p1`): 1 + the index of the first non-vowel that follows a vowel; length if none.
 * - **R2** (`p2`): the same rule applied again starting at `p1`: 1 + the index of the first non-vowel that follows a
 *   vowel, both at index `p1` or later; length if none.
 *
 * "The longest of these suffixes" below means: among the listed suffixes that the current word ends with, the longest
 * one. Only that one is considered: if its condition fails, the step does nothing (or fails, where it says so); a
 * shorter suffix is never tried instead. "Searched in RV" means only suffixes that lie wholly in RV are candidates.
 *
 * **Step 0, attached pronoun.** Take the longest of `me se sela selo selas selos la le lo las les los nos`. Immediately
 * before it, take the longest of `iéndo ándo ár ér ír ando iendo ar er ir yendo`; it must be in RV (else stop: nothing
 * changes). Then:
 * - `iéndo ándo ár ér ír`: replace that ending AND the pronoun with `iendo ando ar er ir` respectively;
 * - `ando iendo ar er ir`: delete the pronoun;
 * - `yendo`: delete the pronoun only if the letter before `yendo` is `u` (that `u` may be outside RV).
 *
 * **Step 1, standard suffix.** Take the longest of all the suffixes below (searched in the whole word). If there is none,
 * or its first condition fails, step 1 FAILS (go to step 2a). Otherwise apply its action; step 1 succeeds.
 * - `anza anzas ico ica icos icas ismo ismos able ables ible ibles ista istas oso osa osos osas amiento amientos
 *   imiento imientos`: in R2 → delete.
 * - `adora ador ación adoras adores aciones ante antes ancia ancias acion`: in R2 → delete; then, if the word now ends
 *   with `ic` and that `ic` is in R2, delete it.
 * - `logía logías`: in R2 → replace with `log`.
 * - `ución uciones ucion`: in R2 → replace with `u`.
 * - `encia encias`: in R2 → replace with `ente`.
 * - `amente`: in R1 → delete; then take the longest of `iv os ic ad` the word now ends with; if it is in R2 delete it,
 *   and if it was `iv` and the word now ends with `at` in R2, delete that too.
 * - `mente`: in R2 → delete; then if the word now ends with `ante`, `able` or `ible` in R2, delete it.
 * - `idad idades`: in R2 → delete; then if the word now ends with `abil`, `ic` or `iv` in R2, delete it.
 * - `iva ivo ivas ivos`: in R2 → delete; then if the word now ends with `at` in R2, delete it.
 *
 * **Step 2a, verb suffixes starting with y** (only if step 1 failed). The longest of
 * `ya ye yan yen yeron yendo yo yó yas yes yais yamos`, searched in RV, whose preceding letter is `u` (that `u` may be
 * outside RV): delete the suffix (not the `u`). If there is no such suffix (none in RV, or not after `u`), step 2a
 * FAILS: go to step 2b.
 *
 * **Step 2b, other verb suffixes** (only if step 2a failed). The longest, searched in RV, of:
 * `en es éis emos` → delete; if the letters before it are `gu`, delete the `u` too (it may be outside RV);
 * `arían arías arán arás aríais aría aréis aríamos aremos ará aré erían erías erán erás eríais ería eréis eríamos
 * eremos erá eré irían irías irán irás iríais iría iréis iríamos iremos irá iré aba ada ida ía ara iera ad ed id ase
 * iese aste iste an aban ían aran ieran asen iesen aron ieron ado ido ando iendo ió ar er ir as abas adas idas ías
 * aras ieras ases ieses ís áis abais íais arais ierais aseis ieseis asteis isteis ados idos amos ábamos íamos imos
 * áramos iéramos iésemos ásemos` → delete.
 *
 * **Step 3, residual suffix** (always). The longest of `os a o á í ó e é` (searched in the whole word): if it is in RV,
 * delete it; for `e` or `é`, after deleting, if the word now ends with `gu` and that `u` is in RV, delete the `u`.
 *
 * **Finally** replace `á é í ó ú` with `a e i o u` everywhere in the word (`ü` stays).
 */
object SpanishStemmer {
    private const val VOWELS = "aeiouáéíóúü"

    private val pronouns = listOf("me", "se", "sela", "selo", "selas", "selos", "la", "le", "lo", "las", "les", "los", "nos")
    private val pronounVerbEndings = listOf("iéndo", "ándo", "ár", "ér", "ír", "ando", "iendo", "ar", "er", "ir", "yendo")
    private val accented = mapOf("iéndo" to "iendo", "ándo" to "ando", "ár" to "ar", "ér" to "er", "ír" to "ir")

    private val deleteInR2 = listOf(
        "anza", "anzas", "ico", "ica", "icos", "icas", "ismo", "ismos", "able", "ables", "ible", "ibles", "ista", "istas",
        "oso", "osa", "osos", "osas", "amiento", "amientos", "imiento", "imientos",
    )
    private val deleteInR2ThenIc = listOf("adora", "ador", "ación", "adoras", "adores", "aciones", "ante", "antes", "ancia", "ancias", "acion")
    private val toLog = listOf("logía", "logías")
    private val toU = listOf("ución", "uciones", "ucion")
    private val toEnte = listOf("encia", "encias")
    private val idad = listOf("idad", "idades")
    private val iva = listOf("iva", "ivo", "ivas", "ivos")
    private val standard = deleteInR2 + deleteInR2ThenIc + toLog + toU + toEnte + listOf("amente", "mente") + idad + iva

    private val yVerb = listOf("ya", "ye", "yan", "yen", "yeron", "yendo", "yo", "yó", "yas", "yes", "yais", "yamos")
    private val guVerb = listOf("en", "es", "éis", "emos")
    private val otherVerb = (
        "arían arías arán arás aríais aría aréis aríamos aremos ará aré erían erías erán erás eríais ería eréis eríamos " +
            "eremos erá eré irían irías irán irás iríais iría iréis iríamos iremos irá iré aba ada ida ía ara iera ad ed id " +
            "ase iese aste iste an aban ían aran ieran asen iesen aron ieron ado ido ando iendo ió ar er ir as abas adas " +
            "idas ías aras ieras ases ieses ís áis abais íais arais ierais aseis ieseis asteis isteis ados idos amos ábamos " +
            "íamos imos áramos iéramos iésemos ásemos"
        ).split(' ')
    private val residual = listOf("os", "a", "o", "á", "í", "ó", "e", "é")

    private fun isVowel(c: Char) = c in VOWELS

    /** The longest of [suffixes] that [w] ends with, starting at index [minStart] or later; null if none. */
    private fun longest(w: CharSequence, suffixes: List<String>, minStart: Int = 0): String? {
        var best: String? = null
        for (s in suffixes) {
            if (s.length <= w.length && w.endsWith(s) && w.length - s.length >= minStart && (best == null || s.length > best.length)) best = s
        }
        return best
    }


    /** 1 + the index of the first non-vowel following a vowel, both at [from] or later; [w]'s length if none. */
    private fun regionAfter(w: String, from: Int): Int {
        for (i in from until w.length - 1) if (isVowel(w[i]) && !isVowel(w[i + 1])) return i + 2
        return w.length
    }

    private fun rv(w: String): Int {
        val n = w.length
        if (n < 2) return n
        if (isVowel(w[0])) {
            if (!isVowel(w[1])) (2 until n).firstOrNull { isVowel(w[it]) }?.let { return it + 1 }
            if (isVowel(w[1])) (2 until n).firstOrNull { !isVowel(w[it]) }?.let { return it + 1 }
            return n
        }
        if (!isVowel(w[1])) return (2 until n).firstOrNull { isVowel(w[it]) }?.let { it + 1 } ?: n
        return if (n >= 3) 3 else n
    }

    fun stem(word: String): String {
        val pV = rv(word)
        val p1 = regionAfter(word, 0)
        val p2 = if (p1 < word.length) regionAfter(word, p1) else word.length
        val w = StringBuilder(word)

        // Step 0: attached pronoun.
        longest(w, pronouns)?.let { pronoun ->
            val base = w.length - pronoun.length
            val before = w.substring(0, base)
            val ending = longest(before, pronounVerbEndings)
            if (ending != null && base - ending.length >= pV) {
                when {
                    ending in accented -> { w.setLength(base - ending.length); w.append(accented.getValue(ending)) }
                    ending == "yendo" -> if (before.length > ending.length && before[before.length - ending.length - 1] == 'u') w.setLength(base)
                    else -> w.setLength(base)
                }
            }
        }

        // Step 1, else 2a, else 2b.
        if (!standardSuffix(w, p1, p2)) {
            val y = longest(w, yVerb, pV)
            if (y != null && w.length > y.length && w[w.length - y.length - 1] == 'u') {
                w.setLength(w.length - y.length)
            } else {
                longest(w, guVerb + otherVerb, pV)?.let { s ->
                    w.setLength(w.length - s.length)
                    if (s in guVerb && w.endsWith("gu")) w.setLength(w.length - 1)
                }
            }
        }

        // Step 3: residual suffix.
        longest(w, residual)?.let { s ->
            if (w.length - s.length >= pV) {
                w.setLength(w.length - s.length)
                if ((s == "e" || s == "é") && w.endsWith("gu") && w.length - 1 >= pV) w.setLength(w.length - 1)
            }
        }

        // Finally, acute accents off.
        for (i in w.indices) {
            when (w[i]) {
                'á' -> w.setCharAt(i, 'a'); 'é' -> w.setCharAt(i, 'e'); 'í' -> w.setCharAt(i, 'i')
                'ó' -> w.setCharAt(i, 'o'); 'ú' -> w.setCharAt(i, 'u')
            }
        }
        return w.toString()
    }

    /** Step 1; false when there is no standard suffix or its first condition fails. */
    private fun standardSuffix(w: StringBuilder, p1: Int, p2: Int): Boolean {
        val s = longest(w, standard) ?: return false
        val start = w.length - s.length
        fun deleteIfR2(vararg endings: String) {
            longest(w, endings.toList())?.let { e -> if (w.length - e.length >= p2) w.setLength(w.length - e.length) }
        }
        if (s == "amente") {
            if (start < p1) return false
            w.setLength(start)
            longest(w, listOf("iv", "os", "ic", "ad"))?.let { e ->
                if (w.length - e.length >= p2) {
                    w.setLength(w.length - e.length)
                    if (e == "iv") deleteIfR2("at")
                }
            }
            return true
        }
        if (start < p2) return false
        w.setLength(start)
        when (s) {
            in deleteInR2 -> {}
            in deleteInR2ThenIc -> deleteIfR2("ic")
            in toLog -> w.append("log")
            in toU -> w.append("u")
            in toEnte -> w.append("ente")
            "mente" -> deleteIfR2("ante", "able", "ible")
            in idad -> deleteIfR2("abil", "ic", "iv")
            in iva -> deleteIfR2("at")
        }
        return true
    }
}
