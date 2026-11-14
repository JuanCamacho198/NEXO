package com.nexo.domain.definition

/**
 * The Wiktionary wikitext parser, reimplemented from desktop's `wiktionary.rs`.
 *
 * ## Why it is narrow
 *
 * This is NOT a MediaWiki parser. It recognises the two shapes that were
 * MEASURED in the real responses, and for anything else it degrades to EMPTY
 * rather than erroring — a wrong parse is worse than no parse, because the
 * reader would be shown a sentence that is not the word's meaning.
 *
 * 1. **es.wiktionary** — `== {{lengua|XX}} ==` level-2 blocks, `;1: gloss` sense
 *    lines, the part of speech in the `=== {{sustantivo femenino|es}} ===`
 *    subsection heading. The language of the WORD picks the block.
 * 2. **en.wiktionary** — `==English==` level-2 blocks, `===Noun===` subsections,
 *    `# gloss` sense lines.
 *
 * Which shape a page is in is decided ONCE, per page: a page carrying any
 * `{{lengua|}}` heading is read as the es shape; a page with none is read as the
 * en shape. The two sense forms (`;1:` and `#`) are never mixed, because picking
 * one block cutter keeps the boundaries honest.
 *
 * Both cutters are total: no input throws, and a missing block or a page with no
 * senses yields an empty list, never an error.
 */
object WiktionaryParser {
    /**
     * Definitions of [wordLanguage] as written on the [wikiLanguage] Wiktionary.
     *
     * [wordLanguage] selects the language block; [wikiLanguage] only fixes the
     * language of the OUTPUT and is not used to select the block, because an
     * es-wiktionary page about an English word is still filed under `en`.
     */
    fun definitions(
        wikitext: String,
        wordLanguage: String,
        wikiLanguage: String,
    ): List<DefinitionItem> =
        if (hasLenguaHeadings(wikitext)) {
            definitionsEs(wikitext, wordLanguage, wikiLanguage)
        } else {
            definitionsEn(wikitext, wordLanguage)
        }

    // ── Shape detection ────────────────────────────────────────────────────

    /** A page with one `== {{lengua|XX}} ==` heading is the es shape. */
    private fun hasLenguaHeadings(wikitext: String): Boolean = wikitext.lineSequence().any { isLanguageHeading(it.trim()) }

    // ── es shape ───────────────────────────────────────────────────────────

    private fun definitionsEs(
        wikitext: String,
        wordLanguage: String,
        wikiLanguage: String,
    ): List<DefinitionItem> {
        val block = languageBlock(wikitext, wordLanguage) ?: return emptyList()
        val out = mutableListOf<DefinitionItem>()
        var partOfSpeech: String? = null
        var inDiscardedSection = false

        for (line in block.lineSequence()) {
            val trimmed = line.trim()

            val heading = subsectionHeading(trimmed)
            if (heading != null) {
                if (slug(heading) in NON_DEFINITION_SECTIONS) {
                    inDiscardedSection = true
                } else {
                    inDiscardedSection = false
                    partOfSpeech = heading
                }
                continue
            }

            if (inDiscardedSection) continue

            val raw = senseLine(trimmed) ?: continue
            val text = clean(raw, wikiLanguage)
            if (text.isEmpty()) continue
            out += DefinitionItem(text = text, partOfSpeech = partOfSpeech)
        }
        return out
    }

    /**
     * Cut the `== {{lengua|XX}} ==` block for [wordLanguage], up to the next
     * `== {{lengua|` of any language, or the end of the page.
     *
     * Only level-2 headings open a block: `casa` carries 20 such sections, and
     * matching on a looser marker would slice the wrong region.
     */
    private fun languageBlock(
        wikitext: String,
        wordLanguage: String,
    ): String? {
        val lines = wikitext.lines()
        var start: Int? = null
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (!isLanguageHeading(trimmed)) continue
            if (start == null) {
                if (headingLanguage(trimmed) == wordLanguage) start = index + 1
            } else {
                return linesBetween(lines, start, index)
            }
        }
        return start?.let { linesBetween(lines, it, lines.size) }
    }

    // ── en shape ───────────────────────────────────────────────────────────

    private fun definitionsEn(
        wikitext: String,
        wordLanguage: String,
    ): List<DefinitionItem> {
        val block = englishBlock(wikitext, wordLanguage) ?: return emptyList()
        val out = mutableListOf<DefinitionItem>()
        var partOfSpeech: String? = null
        var inDiscardedSection = false

        for (line in block.lineSequence()) {
            val trimmed = line.trim()

            val heading = plainSubsectionHeading(trimmed)
            if (heading != null) {
                if (slug(heading) in EN_NON_DEFINITION_SECTIONS) {
                    inDiscardedSection = true
                } else {
                    inDiscardedSection = false
                    partOfSpeech = heading
                }
                continue
            }

            if (inDiscardedSection) continue

            val raw = enSenseLine(trimmed) ?: continue
            val text = clean(raw, wordLanguage)
            if (text.isEmpty()) continue
            out += DefinitionItem(text = text, partOfSpeech = partOfSpeech)
        }
        return out
    }

    /**
     * Cut the `==English==` block for [wordLanguage], up to the next level-2
     * heading of any language, or the end of the page.
     *
     * The en shape names the language in prose, so the match is on the heading
     * text (case-insensitively) or on the human language name for the code.
     */
    private fun englishBlock(
        wikitext: String,
        wordLanguage: String,
    ): String? {
        val lines = wikitext.lines()
        var start: Int? = null
        for ((index, line) in lines.withIndex()) {
            val name = plainLanguageHeading(line.trim()) ?: continue
            if (start == null) {
                if (name.equals(wordLanguage, ignoreCase = true) ||
                    languageNameMatches(name, wordLanguage)
                ) {
                    start = index + 1
                }
            } else {
                return linesBetween(lines, start, index)
            }
        }
        return start?.let { linesBetween(lines, it, lines.size) }
    }

    /** `==English==` → `English`. `null` for the `{{lengua}}` form and level-3+. */
    private fun plainLanguageHeading(line: String): String? {
        if (!line.startsWith("==") || !line.endsWith("==") || line.startsWith("===")) return null
        if (line.contains("{{")) return null
        val inner = line.trim('=').trim()
        if (inner.isEmpty() || inner.contains('=')) return null
        // The en shape may carry a disambiguator: `==English 2==`.
        return inner.split(WHITESPACE).firstOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * True when [name] is the human language name for the [wordLanguage] code.
     * Only the languages the app ships are listed; an unlisted code still matches
     * when the heading text equals the code, and otherwise returns empty, which
     * is the honest outcome.
     */
    private fun languageNameMatches(
        name: String,
        wordLanguage: String,
    ): Boolean {
        val expected =
            when (wordLanguage.lowercase()) {
                "en" -> "english"
                "es" -> "spanish"
                "fr" -> "french"
                "de" -> "german"
                "it" -> "italian"
                "pt" -> "portuguese"
                "nl" -> "dutch"
                "ru" -> "russian"
                "ja" -> "japanese"
                "zh" -> "chinese"
                "ko" -> "korean"
                "ar" -> "arabic"
                "ca" -> "catalan"
                "gl" -> "galician"
                "eu" -> "basque"
                "sv" -> "swedish"
                "pl" -> "polish"
                "tr" -> "turkish"
                "la" -> "latin"
                else -> return false
            }
        return name.trim().lowercase() == expected
    }

    /**
     * A plain subsection heading (`=== Noun ===`), reduced to its human title.
     * A template heading is the es form and returns `null` here.
     */
    private fun plainSubsectionHeading(line: String): String? {
        if (isLanguageHeading(line) || !line.startsWith("=") || !line.endsWith("=")) return null
        val title = line.trim('=').trim()
        if (title.isEmpty() || title.contains("{{")) return null
        return cleanInline(title)
    }

    /**
     * A top-level `# gloss` sense. `##` (sub-sense), `#*` (quotation) and `#:`
     * (example) are NOT definitions — they refine or evidence the parent sense.
     */
    private fun enSenseLine(line: String): String? {
        val rest = line.removePrefix("#")
        if (rest == line) return null
        if (rest.startsWith("#") || rest.startsWith("*") || rest.startsWith(":")) return null
        return rest.trim().takeIf { it.isNotEmpty() }
    }

    // ── Shared heading rules ───────────────────────────────────────────────

    /** True for a level-2 heading (`== ... ==`), how es.wiktionary marks a language. */
    private fun isLanguageHeading(line: String): Boolean = line.startsWith("== ") && line.endsWith(" ==") && !line.startsWith("===")

    /** `== {{lengua|en}} ==` → `en`. */
    private fun headingLanguage(line: String): String? = templateInner(line, "lengua")?.split('|')?.firstOrNull()?.trim()

    /**
     * A subsection heading (`=== Title ===` or `==== Title ====`) reduces to its
     * human title, template or not: `==== {{sustantivo femenino|es}} ====` →
     * `sustantivo femenino`. `null` for language headings.
     */
    private fun subsectionHeading(line: String): String? {
        if (isLanguageHeading(line)) return null
        if (!line.startsWith("=") || !line.endsWith("=")) return null
        val title = line.trim('=').trim()
        if (title.isEmpty()) return null
        templateBody(title)?.let { body ->
            val name =
                body
                    .split('|')
                    .firstOrNull()
                    ?.trim()
                    .orEmpty()
            return name.ifEmpty { null }
        }
        return cleanInline(title)
    }

    /**
     * A sense line begins with `;N` — either `;1: text` or the label form
     * `;1 {{csem|vivienda}}: text`. Returns everything after the number marker,
     * with the `:` separator that follows a leading label removed.
     */
    private fun senseLine(line: String): String? {
        if (!line.startsWith(";")) return null
        val rest = line.removePrefix(";").trimStart()
        val digits = rest.takeWhile { it.isDigit() }.length
        if (digits == 0) return null
        val afterNumber = rest.substring(digits).trimStart()
        afterNumber.removePrefix(":").let {
            if (it != afterNumber) return it.trim()
        }
        // `;1 {{label}}: prose` — drop the label template and the separator.
        if (afterNumber.startsWith("{{")) {
            val close = afterNumber.indexOf("}}")
            if (close >= 0) {
                val after = afterNumber.substring(close + 2).trimStart()
                return after.removePrefix(":").trim()
            }
        }
        return afterNumber
    }

    // ── Template + inline cleaning ─────────────────────────────────────────

    /** Resolve the closed template list, then strip wiki markup. */
    private fun clean(
        text: String,
        wikiLanguage: String,
    ): String {
        val resolved = resolveTemplates(text, wikiLanguage)
        return cleanInline(resolved).trim().trimEnd('.').trim()
    }

    /**
     * Walk the string keeping or dropping each `{{...}}` per the two lists.
     * Unknown templates keep their first positional argument, the least
     * destructive choice for output the reader will read.
     */
    private fun resolveTemplates(
        text: String,
        @Suppress("UNUSED_PARAMETER") wikiLanguage: String,
    ): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text.startsWith("{{", i)) {
                val end = findTemplateEnd(text, i + 2)
                if (end == null) {
                    // Unbalanced `{{` — emit the rest literally rather than throw.
                    out.append(text.substring(i))
                    break
                }
                val inner = text.substring(i + 2, end)
                val name = templateName(inner)
                if (name in DROP_WHOLE || name == "lengua") {
                    // dropped entirely
                } else {
                    // `plm` and unknown templates alike: keep the first arg.
                    out.append(firstArg(inner))
                }
                i = end + 2
                continue
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    /** Index of the `}}` that closes the template opened just after [i]. */
    private fun findTemplateEnd(
        text: String,
        from: Int,
    ): Int? {
        var i = from
        var depth = 1
        while (i < text.length) {
            if (text.startsWith("{{", i)) {
                depth++
                i += 2
                continue
            }
            if (text.startsWith("}}", i)) {
                depth--
                if (depth == 0) return i
                i += 2
                continue
            }
            i++
        }
        return null
    }

    /** Template name, lowercased, without any `1=` style prefix or params. */
    private fun templateName(inner: String): String =
        inner
            .split('|')
            .firstOrNull()
            .orEmpty()
            .split('=')
            .firstOrNull()
            .orEmpty()
            .trim()
            .lowercase()

    /** First positional argument of a template body, or the empty string. */
    private fun firstArg(inner: String): String =
        inner
            .split('|')
            .getOrNull(1)
            ?.trim()
            .orEmpty()

    /** `{{lengua|en}}` → `en` (the joined args after the name), or `null`. */
    private fun templateInner(
        line: String,
        name: String,
    ): String? {
        val open = "{{$name|"
        val at = line.indexOf(open)
        if (at < 0) return null
        val rest = line.substring(at + open.length)
        val end = rest.indexOf("}}")
        if (end < 0) return null
        return rest.substring(0, end)
    }

    /** The raw body of a `{{...}}` when the whole string is one template. */
    private fun templateBody(s: String): String? {
        val trimmed = s.trim()
        if (!trimmed.startsWith("{{") || !trimmed.endsWith("}}") || trimmed.length < 4) return null
        return trimmed.substring(2, trimmed.length - 2)
    }

    /** Resolve `[[X]]` / `[[X|Y]]`, drop `<ref>...</ref>`, strip bold/italic. */
    private fun cleanInline(text: String): String {
        var s = resolveLinks(stripRefs(text))
        s = s.replace("'''", "").replace("''", "")
        s = s.replace("[[", "").replace("]]", "")
        s = s.replace("{{", "").replace("}}", "")
        return collapseSpaces(s)
    }

    private fun resolveLinks(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text.startsWith("[[", i)) {
                val end = text.indexOf("]]", i + 2)
                if (end >= 0) {
                    val inner = text.substring(i + 2, end)
                    // `[[X|Y]]` shows Y; `[[X]]` shows X.
                    val label = inner.substringAfterLast('|')
                    out.append(label.trim())
                    i = end + 2
                    continue
                }
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    private fun stripRefs(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text.startsWith("<ref", i)) {
                val end = text.indexOf("</ref>", i)
                if (end >= 0) {
                    i = end + "</ref>".length
                    continue
                }
                val selfClose = text.indexOf("/>", i)
                if (selfClose >= 0) {
                    i = selfClose + 2
                    continue
                }
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    /** Lowercase, trimmed key for section lookup. */
    private fun slug(name: String): String = name.trim().lowercase()

    private fun collapseSpaces(text: String): String {
        val out = StringBuilder(text.length)
        var lastSpace = false
        for (c in text) {
            if (c.isWhitespace()) {
                if (!lastSpace) out.append(' ')
                lastSpace = true
            } else {
                out.append(c)
                lastSpace = false
            }
        }
        return out.toString()
    }

    private fun linesBetween(
        lines: List<String>,
        start: Int,
        end: Int,
    ): String {
        val out = StringBuilder()
        var index = start
        while (index < end && index < lines.size) {
            out.append(lines[index]).append('\n')
            index++
        }
        return out.toString()
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * Templates that are pure noise on a definition line and must vanish with
     * all their arguments: usage labels, synonyms, the sense-context marker,
     * example and etymology scaffolding.
     *
     * Everything NOT listed here — `{{plm|casa}}` included — keeps its first
     * positional argument, because on this wiki the first argument is the prose.
     */
    private val DROP_WHOLE =
        setOf(
            "csem",
            "uso",
            "sinónimo",
            "sinonimo",
            "sin",
            "antónimo",
            "antonimo",
            "ant",
            "ejemplo",
            "etimología",
            "etimologia",
            "etim",
            "l",
            "l+",
            "m",
            "derivad",
            "w",
            "pron-graf",
            "DLC1914",
            "Labernia1866",
            "trad-arriba",
            "trad-abajo",
        )

    /**
     * es-shape subsections that are never definitions. `casa` on es-wiktionary
     * lists dozens of locutions after the real senses, and a parser that walked
     * them would return "casa por casa" as the meaning of "casa".
     */
    private val NON_DEFINITION_SECTIONS =
        setOf(
            "locuciones",
            "locución",
            "locucion",
            "compuestos",
            "derivados",
            "referencias",
            "referencias y notas",
            "conjugación",
            "conjugacion",
            "véase también",
            "vease tambien",
            "véase",
            "anagramas",
            "información adicional",
            "informacion adicional",
            "traducciones",
            "etimología",
            "etimologia",
            "pronunciación",
            "pronunciacion",
            "frases",
            "refranes",
            "proverbios",
            "expresiones",
            "hipónimos",
            "hiperónimos",
            "merónimos",
            "holónimos",
            "coordinados",
            "términos relacionados",
            "terminos relacionados",
            "notas",
            "notas de uso",
        )

    /**
     * en-shape subsections that are never definitions. `juxtaposition` is the
     * measured case: after the real Noun/Verb senses come Hypernyms, Derived
     * terms and a large Translations table, and a parser that walked those would
     * return a translation row as the meaning of the word.
     */
    private val EN_NON_DEFINITION_SECTIONS =
        setOf(
            "alternative forms",
            "etymology",
            "pronunciation",
            "hypernyms",
            "hyponyms",
            "derived terms",
            "related terms",
            "translations",
            "see also",
            "references",
            "anagrams",
            "descendants",
            "coordinate terms",
            "usage notes",
            "quotations",
            "further reading",
            "external links",
            "trivia",
            "statistics",
            "collocations",
            "conjugation",
            "inflection",
            "declension",
        )
}
