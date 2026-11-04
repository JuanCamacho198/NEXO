//! Wiktionary gloss extraction for the "Definir" reader action.
//!
//! Product goal: select a word in the reader and get its MEANING written in the
//! app's language, regardless of the language the book is written in. The
//! mechanism (measured, not assumed) is that `{app_lang}.wiktionary` already
//! answers in `{app_lang}` — an entry on `es.wiktionary` is written in Spanish
//! even when the headword is English. A lookup of `house` on `es.wiktionary`
//! returns `casa`/`sala`, not a Spanish-to-English gloss.
//!
//! `es.wiktionary` hosts many languages on ONE page: `casa` carries 20
//! `== {{lengua|XX}} ==` sections. The lookup must therefore be told the
//! language of the SELECTED WORD (`en` for `house`) so it reads the right
//! section, and the wiki language (`es`) is what determines the output language.
//!
//! The wikitext format is NOT consistent across wikis, and it is not
//! consistent within one wiki either. This parser recognises TWO measured
//! shapes and degrades to an EMPTY result instead of an error for anything it
//! does not recognise. A wrong parse is worse than no parse: the reader shows
//! "no definition" and the cascade falls through to the next source.
//!
//! 1. `es.wiktionary` — `== {{lengua|es}} ==` blocks, `;1: gloss` senses, POS
//!    in the `=== {{sustantivo}} ===` heading. This is the SHAPE 1 source: a
//!    native gloss in the reader's language already, the best outcome.
//! 2. `en.wiktionary` — `==English==` blocks, `===Noun===` subsections,
//!    `# gloss` senses. This is the FALLBACK source: it answers in English, and
//!    the cascade translates it. It exists because `es.wiktionary` does not
//!    carry every English word (measured: `juxtaposition` is absent there but
//!    present here).
//!
//! Deliberately NOT a general MediaWiki parser. It knows a CLOSED list of
//! templates; anything not on the list is either dropped or left as literal
//! text. It never panics and never errors on input.
//!
//! Which shape a page is in is decided per heading, not per wiki: an
//! `es` request reads `{{lengua|es}}` blocks and a `#gloss` sense form only
//! when the page has no `{{lengua|}}` language headings at all. The two are
//! never mixed within one call, because the block cutter is chosen once.

use serde_json::Value;

const WIKTIONEXT_FIELD: &str = "*";

/// A single sense line: the cleaned gloss plus, when the page states it, the
/// part of speech the sense belongs to (`sustantivo femenino`, `locución
/// verbal`, `verbo`, ...).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Definition {
    pub part_of_speech: Option<String>,
    pub text: String,
}

/// Templates that are pure noise on a definition line and must vanish with all
/// their arguments: usage labels, synonyms, the sense-context marker, example
/// and etymology scaffolding.
///
/// Everything NOT listed here — `{{plm|casa}}` included — keeps its first
/// positional argument, because on this wiki the first argument is the prose.
const DROP_WHOLE: &[&str] = &[
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
];

/// Subsection headings inside an `en.wiktionary` language block that are never
/// definitions. `juxtaposition` is the measured case: after the two real
/// `===Noun===` senses come Hypernyms, Derived terms, Related terms and a large
/// Translations table, and a parser that walked those would return
/// `{{l|en|position}}` or the translation rows as the meaning of the word.
const EN_NON_DEFINITION_SECTIONS: &[&str] = &[
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
    "external links",
];

/// Subsection headings inside a language block that are never definitions.
/// `casa` on es-wiktionary is mostly these: its `es` block lists dozens of
/// locutions after the 15 real senses, and a parser that walked them would
/// return "casa por casa" as the meaning of "casa".
const NON_DEFINITION_SECTIONS: &[&str] = &[
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
];

/// Extract the wikitext string from a parsed API response.
///
/// Accepts the raw JSON body of
/// `https://{lang}.wiktionary.org/w/api.php?action=parse&page=W&prop=wikitext&format=json`.
/// A missing page, an `error` field, or malformed JSON all yield `None` — the
/// caller then has nothing to show, which is the correct outcome for a page
/// that does not exist.
pub fn extract_wikitext(page_json: &str) -> Option<String> {
    let root: Value = serde_json::from_str(page_json).ok()?;
    let raw = root.get("parse")?.get("wikitext")?.get(WIKTIONEXT_FIELD)?;
    Some(raw.as_str()?.to_string())
}

/// Definitions of `word_lang` as written on the `wiki_lang` Wiktionary.
///
/// `word_lang` is the language of the selected word (`en` for `house`); it picks
/// the `== {{lengua|en}} ==` block (es shape) or the `==English==` block (en
/// shape). `wiki_lang` is the edition being read and only fixes the language of
/// the OUTPUT — it is not used to select the block, because an es-wiktionary
/// page for an English word is still filed under `en`.
///
/// The shape is chosen once, by the page: a page carrying `{{lengua|}}` language
/// headings is read as es-wiktionary; a page without any is read as
/// en-wiktionary. The two are never mixed, because their sense forms (`;1:` vs
/// `#`) do not collide and picking one cutter keeps the block boundaries honest.
///
/// Returns an empty vector when the block is missing, when it holds no sense
/// lines, or when the input is not wikitext. It never returns an error: "no
/// definition" is a normal outcome that the reader renders as such and the
/// cascade uses to fall through.
pub fn definitions(wikitext: &str, word_lang: &str, wiki_lang: &str) -> Vec<Definition> {
    if has_lengua_headings(wikitext) {
        definitions_es(wikitext, word_lang, wiki_lang)
    } else {
        definitions_en(wikitext, word_lang)
    }
}

/// True when the page uses the `es.wiktionary` language-heading form.
///
/// The presence of ONE `== {{lengua|XX}} ==` proves the es shape, and it is the
/// es shape that the es-wiktionary edition always emits. A page with no such
/// heading is the en shape; the en cutter is a strict fallback and returns
/// empty for anything it does not recognise, so a misclassification of a
/// non-es page cannot corrupt an es result.
fn has_lengua_headings(wikitext: &str) -> bool {
    wikitext.lines().any(|line| is_language_heading(line.trim()))
}

/// The `es.wiktionary` shape: `== {{lengua|XX}} ==` blocks, `;N:` senses, POS
/// in the `=== {{sustantivo}} ===` heading.
fn definitions_es(wikitext: &str, word_lang: &str, wiki_lang: &str) -> Vec<Definition> {
    let Some(block) = language_block(wikitext, word_lang) else {
        return Vec::new();
    };

    let mut out = Vec::new();
    let mut part_of_speech: Option<String> = None;
    let mut in_discarded_section = false;

    for line in block.lines() {
        let trimmed = line.trim();

        if let Some(heading) = subsection_heading(trimmed) {
            let name = slug(&heading);
            if NON_DEFINITION_SECTIONS.contains(&name.as_str()) {
                in_discarded_section = true;
            } else {
                in_discarded_section = false;
                part_of_speech = Some(heading);
            }
            continue;
        }

        if in_discarded_section {
            continue;
        }

        let Some(raw) = sense_line(trimmed) else {
            continue;
        };
        let text = clean(raw, wiki_lang);
        if text.is_empty() {
            continue;
        }
        out.push(Definition { part_of_speech: part_of_speech.clone(), text });
    }

    out
}

/// The `en.wiktionary` shape: `==English==` blocks, `===Noun===` subsections,
/// `# gloss` senses.
///
/// Only top-level `#` senses are definitions. `##` sub-senses are refinements
/// of the parent sense (the cascade shows the gloss, not the tree), and `#*`
/// quotations / `#:` examples are evidence, not meaning — a parser that took
/// them would return a Coleridge quotation as the definition of
/// `juxtaposition`. The label template `{{lb|en|grammar}}` is dropped by the
/// existing closed-list rules, leaving the prose.
///
/// `word_lang` selects the `==English==` / `==French==` block on the same page
/// exactly as the es cutter selects `{{lengua|}}`.
fn definitions_en(wikitext: &str, word_lang: &str) -> Vec<Definition> {
    let Some(block) = english_block(wikitext, word_lang) else {
        return Vec::new();
    };

    let mut out = Vec::new();
    let mut part_of_speech: Option<String> = None;
    let mut in_discarded_section = false;

    for line in block.lines() {
        let trimmed = line.trim();

        if let Some(heading) = plain_subsection_heading(trimmed) {
            let name = slug(&heading);
            if EN_NON_DEFINITION_SECTIONS.contains(&name.as_str()) {
                in_discarded_section = true;
            } else {
                in_discarded_section = false;
                part_of_speech = Some(heading);
            }
            continue;
        }

        if in_discarded_section {
            continue;
        }

        let Some(raw) = en_sense_line(trimmed) else {
            continue;
        };
        let text = clean(raw, word_lang);
        if text.is_empty() {
            continue;
        }
        out.push(Definition { part_of_speech: part_of_speech.clone(), text });
    }

    out
}

/// Cut the `==English==` block for `word_lang` from the page, up to the next
/// `==Language==` of any language (or end of page).
///
/// The en shape names the language in prose (`==English==`) rather than in a
/// template, so the match is on the heading text, case-insensitively. Level-2
/// headings are the boundary, exactly as in the es cutter: `juxtaposition`
/// carries an `==English==` block and a `==French==` block on one page, and a
/// looser match would splice them together.
fn english_block(wikitext: &str, word_lang: &str) -> Option<String> {
    let mut start: Option<usize> = None;

    for (idx, line) in wikitext.lines().enumerate() {
        let trimmed = line.trim();
        let Some(name) = plain_language_heading(trimmed) else {
            continue;
        };
        match start {
            None => {
                if name.eq_ignore_ascii_case(word_lang) || language_name_matches(&name, word_lang) {
                    start = Some(idx + 1);
                }
            }
            Some(s) => {
                // Any further level-2 heading ends the block we wanted.
                return Some(lines_between(wikitext, s, idx));
            }
        }
    }

    start.map(|s| lines_between(wikitext, s, usize::MAX))
}

/// `==English==` → `Some("English")`. `None` for any other heading, including
/// the `== {{lengua|en}} ==` form (handled by the es cutter) and level-3+
/// headings, which are subsections rather than language boundaries.
///
/// The en form has no space after the `==` (`==English==`), so the es
/// `is_language_heading` predicate — which requires `== ` — does not fit; the
/// level-2 test is done here directly.
fn plain_language_heading(line: &str) -> Option<String> {
    if !line.starts_with("==") || !line.ends_with("==") || line.starts_with("===") {
        return None;
    }
    if line.contains("{{") {
        return None;
    }
    let inner = line.trim_start_matches('=').trim_end_matches('=').trim();
    if inner.is_empty() || inner.contains('=') {
        return None;
    }
    // The en shape may carry a disambiguator: `==English==` vs `==English 2==`.
    let base = inner.split_whitespace().next().unwrap_or(inner);
    if base.is_empty() {
        None
    } else {
        Some(base.to_string())
    }
}

/// True when `name` is the human language name for the `word_lang` code.
///
/// The en shape spells the language out (`English` for `en`), so the code
/// lookup needs a name. Only the languages the reader actually emits are
/// listed; an unlisted code still matches when the heading text happens to
/// equal the code, and otherwise returns empty, which is the honest outcome.
fn language_name_matches(name: &str, word_lang: &str) -> bool {
    let expected = match word_lang {
        "en" => "english",
        "es" => "spanish",
        "fr" => "french",
        "de" => "german",
        "it" => "italian",
        "pt" => "portuguese",
        "nl" => "dutch",
        "ru" => "russian",
        "ja" => "japanese",
        "zh" => "chinese",
        "ko" => "korean",
        "ar" => "arabic",
        "ca" => "catalan",
        "gl" => "galician",
        "eu" => "basque",
        "sv" => "swedish",
        "pl" => "polish",
        "tr" => "turkish",
        "la" => "latin",
        _ => return false,
    };
    name.trim().to_lowercase() == expected
}

/// A plain subsection heading (`=== Noun ===`), reduced to its human title.
/// Unlike `subsection_heading` this never resolves templates: the en shape
/// writes the part of speech as prose, and a template heading here is the es
/// form the en cutter must not touch.
fn plain_subsection_heading(line: &str) -> Option<String> {
    if is_language_heading(line) || !line.starts_with('=') || !line.ends_with('=') {
        return None;
    }
    let title = line.trim_matches('=').trim();
    if title.is_empty() || title.contains("{{") {
        return None;
    }
    Some(clean_inline(title))
}

/// A sense line in the en shape: a top-level `# gloss`.
///
/// `##` (sub-sense), `#*` (quotation) and `#:` (example) are NOT definitions:
/// they refine or evidence the parent sense rather than naming a new meaning.
/// They are rejected here rather than filtered later so the caller cannot
/// accidentally include them.
fn en_sense_line(line: &str) -> Option<&str> {
    let rest = line.strip_prefix('#')?;
    // `#`, `# ` open a sense; `##`, `#*`, `#:` and `#;` do not.
    if rest.starts_with('#') || rest.starts_with('*') || rest.starts_with(':') {
        return None;
    }
    let text = rest.trim();
    if text.is_empty() {
        None
    } else {
        Some(text)
    }
}

/// Cut the `== {{lengua|XX}} ==` block for `word_lang` from the page, up to the
/// next `== {{lengua|` of any language (or end of page).
///
/// Only level-2 headings open a language block: `casa` starts with a
/// `{{desambiguación|...}}` banner and the page carries 20 such blocks, so
/// matching on the bare template name would slice the wrong region.
fn language_block(wikitext: &str, word_lang: &str) -> Option<String> {
    let mut start: Option<usize> = None;

    for (idx, line) in wikitext.lines().enumerate() {
        let trimmed = line.trim();
        if !is_language_heading(trimmed) {
            continue;
        }
        match start {
            None => {
                if heading_language(trimmed).as_deref() == Some(word_lang) {
                    start = Some(idx + 1);
                }
            }
            Some(s) => {
                // Any further language heading ends the block we wanted.
                return Some(lines_between(wikitext, s, idx));
            }
        }
    }

    start.map(|s| lines_between(wikitext, s, usize::MAX))
}

/// Handles the last block, where there is no following heading: take everything
/// from `start` to `end` (exclusive), or to the end of the text.
fn lines_between(wikitext: &str, start: usize, end: usize) -> String {
    let mut out = String::new();
    for (idx, line) in wikitext.lines().enumerate() {
        if idx < start {
            continue;
        }
        if idx >= end {
            break;
        }
        out.push_str(line);
        out.push('\n');
    }
    out
}

/// True for a level-2 heading (`== ... ==`), which is how `es.wiktionary` marks
/// a language. Subsection headings (`===`, `====`) are not language boundaries.
fn is_language_heading(line: &str) -> bool {
    line.starts_with("== ") && line.ends_with(" ==") && !line.starts_with("===")
}

/// `== {{lengua|en}} ==` → `Some("en")`.
fn heading_language(line: &str) -> Option<String> {
    let inner = template_inner(line, "lengua")?;
    inner.split('|').next().map(|s| s.trim().to_string())
}

/// A subsection heading (`=== Title ===` or `==== Title ====`) reduces to its
/// human title, template or not: `==== {{sustantivo femenino|es}} ====` →
/// `sustantivo femenino`. Returns `None` for language headings.
fn subsection_heading(line: &str) -> Option<String> {
    if is_language_heading(line) {
        return None;
    }
    if !line.starts_with('=') || !line.ends_with('=') {
        return None;
    }
    let title = line.trim_matches('=').trim();
    if title.is_empty() {
        return None;
    }
    // The heading may be a template carrying the part of speech, as in
    // `{{sustantivo femenino|es}}`; take the template name and drop the lang.
    if let Some(inner) = template_body(title) {
        let name = inner.split('|').next().unwrap_or("").trim();
        return if name.is_empty() { None } else { Some(name.to_string()) };
    }
    Some(clean_inline(title))
}

/// A sense line begins with `;N` — either `;1: text` or the label form
/// `;1 {{csem|vivienda}}: text`. Returns everything after the number marker,
/// with the `:` separator that follows a leading label removed.
fn sense_line(line: &str) -> Option<&str> {
    let rest = line.strip_prefix(';')?;
    let rest = rest.trim_start();
    let digits = rest.chars().take_while(|c| c.is_ascii_digit()).count();
    if digits == 0 {
        return None;
    }
    let rest = rest[digits..].trim_start();
    // `;1: prose` — plain form, separator comes first.
    if let Some(after) = rest.strip_prefix(':') {
        return Some(after.trim());
    }
    // `;1 {{label}}: prose` — the label is a template; drop it and the
    // separator, keeping the prose that follows.
    if rest.starts_with("{{") {
        if let Some(close) = rest.find("}}") {
            let after = rest[close + 2..].trim_start();
            return Some(after.strip_prefix(':').unwrap_or(after).trim());
        }
    }
    Some(rest)
}

/// Resolve the closed template list on a sense line, then strip wiki markup.
fn clean(text: &str, wiki_lang: &str) -> String {
    let resolved = resolve_templates(text, wiki_lang);
    clean_inline(&resolved).trim().trim_end_matches('.').trim().to_string()
}

/// Walk the string, keeping or dropping each `{{...}}` per the two lists.
/// Unknown templates keep their first positional argument, which is the least
/// destructive choice for output the user will read.
fn resolve_templates(text: &str, wiki_lang: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut i = 0;

    // Byte offsets from `find_template_end` are safe to slice here because the
    // delimiters `{{` `}}` are ASCII and we only cut AT matched delimiter
    // boundaries; between them we copy whole `str` slices, never single bytes.
    while i < text.len() {
        if text[i..].starts_with("{{") {
            match find_template_end(text, i + 2) {
                Some(end) => {
                    let inner = &text[i + 2..end];
                    let name = template_name(inner);
                    if DROP_WHOLE.contains(&name.as_str()) {
                        // dropped entirely
                    } else if name == "lengua" {
                        // never a definition, drop
                    } else {
                        // `plm` and unknown templates alike: keep first arg.
                        // Reduces to empty when there is no positional arg,
                        // which is the measured `{{plm}}.` case on `casa`(it).
                        out.push_str(first_arg(inner));
                    }
                    i = end + 2;
                    continue;
                }
                None => {
                    // Unbalanced `{{` — emit the rest literally rather than panic.
                    out.push_str(&text[i..]);
                    break;
                }
            }
        }
        let ch = text[i..].chars().next().expect("non-empty slice has a char");
        out.push(ch);
        i += ch.len_utf8();
    }

    let _ = wiki_lang;
    out
}

/// Index of the `}}` that closes the template opened just after `i`.
fn find_template_end(text: &str, mut i: usize) -> Option<usize> {
    let bytes = text.as_bytes();
    let mut depth = 1usize;
    while i < bytes.len() {
        if bytes[i] == b'{' && bytes.get(i + 1) == Some(&b'{') {
            depth += 1;
            i += 2;
            continue;
        }
        if bytes[i] == b'}' && bytes.get(i + 1) == Some(&b'}') {
            depth -= 1;
            if depth == 0 {
                return Some(i);
            }
            i += 2;
            continue;
        }
        i += 1;
    }
    None
}

/// Template name, lowercased, without any `1=` style prefix or params.
fn template_name(inner: &str) -> String {
    let first = inner.split('|').next().unwrap_or("");
    first.split('=').next().unwrap_or("").trim().to_lowercase()
}

/// First positional argument of a template body, or the empty string.
fn first_arg(inner: &str) -> &str {
    inner.split('|').nth(1).unwrap_or("").trim()
}

/// `{{lengua|en}}` → `en` (the joined args after the name), or `None`.
fn template_inner(line: &str, name: &str) -> Option<String> {
    let open = format!("{{{{{name}|");
    let at = line.find(&open)?;
    let rest = &line[at + open.len()..];
    let end = rest.find("}}")?;
    Some(rest[..end].to_string())
}

/// The raw body of a `{{...}}` when the whole string is one template.
fn template_body(s: &str) -> Option<&str> {
    let s = s.trim();
    let inner = s.strip_prefix("{{")?.strip_suffix("}}")?;
    Some(inner)
}

/// Resolve `[[X]]` / `[[X|Y]]`, drop `<ref>...</ref>`, strip bold/italic.
fn clean_inline(text: &str) -> String {
    let mut s = resolve_links(&strip_refs(text));
    s = s.replace("'''", "").replace("''", "");
    s = s.replace("[[", "").replace("]]", "");
    s = s.replace("{{", "").replace("}}", "");
    collapse_spaces(&s)
}

fn resolve_links(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < text.len() {
        if text[i..].starts_with("[[") {
            if let Some(end) = text[i + 2..].find("]]") {
                let inner = &text[i + 2..i + 2 + end];
                // `[[X|Y]]` shows Y; `[[X]]` shows X. A link with a namespace
                // prefix (`w:Horace Walpole`) keeps the full target.
                let label = inner.rsplit_once('|').map(|(_, y)| y).unwrap_or(inner);
                out.push_str(label.trim());
                i = i + 2 + end + 2;
                continue;
            }
        }
        let ch = text[i..].chars().next().expect("non-empty slice has a char");
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

fn strip_refs(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < text.len() {
        if text[i..].starts_with("<ref") {
            if let Some(end) = text[i..].find("</ref>") {
                i += end + "</ref>".len();
                continue;
            }
            if let Some(end) = text[i..].find("/>") {
                i += end + 2;
                continue;
            }
        }
        let ch = text[i..].chars().next().expect("non-empty slice has a char");
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

/// Lowercase, accent-folded, whitespace-collapsed key for section lookup.
fn slug(name: &str) -> String {
    name.trim().to_lowercase()
}

fn collapse_spaces(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut last_space = false;
    for c in text.chars() {
        if c.is_whitespace() {
            if !last_space {
                out.push(' ');
            }
            last_space = true;
        } else {
            out.push(c);
            last_space = false;
        }
    }
    out
}

#[cfg(test)]
mod wiktionary_tests {
    use super::*;

    const ES_HOUSE: &str = include_str!("../fixtures/wiktionary/es_house.json");
    const ES_GIVE_UP: &str = include_str!("../fixtures/wiktionary/es_give_up.json");
    const ES_SERENDIPITY: &str = include_str!("../fixtures/wiktionary/es_serendipity.json");
    const ES_CASA: &str = include_str!("../fixtures/wiktionary/es_casa.json");
    const EN_JUXTAPOSITION: &str = include_str!("../fixtures/wiktionary/en_juxtaposition.json");

    #[test]
    fn fixtures_carry_the_real_wikitext_payload() {
        // Guards the fixtures themselves: if a download ever returned an error
        // page, every assertion below would pass vacuously on empty input.
        let house = extract_wikitext(ES_HOUSE).expect("es.house wikitext");
        assert!(house.contains("{{lengua|en}}"), "house must be an English entry");

        let casa = extract_wikitext(ES_CASA).expect("es.casa wikitext");
        assert!(casa.contains("{{lengua|es}}"), "casa must carry a Spanish section");
        assert!(casa.contains("{{lengua|it}}"), "casa must carry an Italian section");
        assert!(
            casa.matches("== {{lengua|").count() >= 18,
            "casa is the multi-language case, got {} sections",
            casa.matches("== {{lengua|").count()
        );
    }

    #[test]
    fn house_in_english_section_gives_spanish_gloss() {
        let wt = extract_wikitext(ES_HOUSE).unwrap();
        let defs = definitions(&wt, "en", "es");

        let joined = defs.iter().map(|d| d.text.as_str()).collect::<Vec<_>>().join(" | ");
        assert!(joined.contains("casa"), "expected 'casa' in {joined:?}");
        assert!(joined.contains("sala"), "expected 'sala' in {joined:?}");
    }

    #[test]
    fn house_reports_part_of_speech() {
        let wt = extract_wikitext(ES_HOUSE).unwrap();
        let defs = definitions(&wt, "en", "es");

        let parts: Vec<&str> = defs.iter().filter_map(|d| d.part_of_speech.as_deref()).collect();
        assert!(
            parts.iter().any(|p| p.contains("sustantivo")),
            "expected a sustantivo sense, got {parts:?}"
        );
    }

    #[test]
    fn phrasal_verb_give_up_gives_spanish_gloss() {
        let wt = extract_wikitext(ES_GIVE_UP).unwrap();
        let defs = definitions(&wt, "en", "es");

        let joined = defs.iter().map(|d| d.text.as_str()).collect::<Vec<_>>().join(" | ");
        assert!(joined.contains("rendirse"), "expected 'rendirse' in {joined:?}");
        assert!(joined.contains("desistir"), "expected 'desistir' in {joined:?}");
    }

    #[test]
    fn serendipity_gives_spanish_gloss() {
        let wt = extract_wikitext(ES_SERENDIPITY).unwrap();
        let defs = definitions(&wt, "en", "es");

        let joined = defs.iter().map(|d| d.text.as_str()).collect::<Vec<_>>().join(" | ");
        assert!(joined.contains("serendipia"), "expected 'serendipia' in {joined:?}");
    }

    /// The hard case: `casa` on es-wiktionary opens with a disambiguation
    /// banner and then stacks 20 language sections on one page. Asking for the
    /// SPANISH section must return the Spanish sense (a dwelling), and asking
    /// for the ITALIAN section must return the Italian one — which is the only
    /// proof that language selection actually selects.
    #[test]
    fn casa_selects_spanish_and_italian_apart() {
        let wt = extract_wikitext(ES_CASA).unwrap();

        let es = definitions(&wt, "es", "es");
        let it = definitions(&wt, "it", "es");

        let es_text = es.iter().map(|d| d.text.as_str()).collect::<Vec<_>>().join(" | ");
        let it_text = it.iter().map(|d| d.text.as_str()).collect::<Vec<_>>().join(" | ");

        assert!(
            es_text.contains("edificación") || es_text.contains("edificacion"),
            "Spanish section must define casa as a building, got {es_text:?}"
        );
        assert!(
            es_text.contains("domicilio") || es_text.contains("vivienda"),
            "Spanish section must carry the dwelling senses, got {es_text:?}"
        );

        // The Italian entry's own senses are 'tienda' (a shop) — proving the
        // slices do not bleed into each other.
        assert!(
            it_text.contains("tienda"),
            "Italian section must define casa as tienda, got {it_text:?}"
        );

        assert_ne!(es_text, it_text, "language selection must produce different output");
    }

    /// A language the page does not carry is not an error: the caller shows
    /// nothing and the cascade moves on.
    #[test]
    fn missing_language_section_is_empty_not_error() {
        let wt = extract_wikitext(ES_HOUSE).unwrap();
        assert!(definitions(&wt, "ja", "es").is_empty());
    }

    #[test]
    fn garbage_and_empty_input_do_not_panic() {
        assert!(definitions("", "en", "es").is_empty());
        assert!(definitions("   \n\n  ", "en", "es").is_empty());
        assert!(definitions("not wikitext at all {{{", "en", "es").is_empty());
        assert!(definitions("== {{lengua|en}} ==\n=== Title ===\nno senses here", "en", "es")
            .is_empty());
    }

    #[test]
    fn malformed_page_json_yields_none() {
        assert!(extract_wikitext("").is_none());
        assert!(extract_wikitext("{}").is_none());
        assert!(extract_wikitext(r#"{"error":{"code":"missingtitle"}}"#).is_none());
        assert!(extract_wikitext("not json").is_none());
    }

    /// en.wiktionary uses `==English==`, not `{{lengua|}}`. This is the shape
    /// the cascade's step 2 reads, and the fixture is the real page.
    #[test]
    fn en_juxtaposition_yields_english_definitions() {
        let wt = extract_wikitext(EN_JUXTAPOSITION).expect("en.juxtaposition wikitext");
        assert!(wt.contains("==English=="));

        let defs = definitions(&wt, "en", "en");
        let joined = defs.iter().map(|d| d.text.as_str()).collect::<Vec<_>>().join(" | ");

        assert!(!defs.is_empty(), "en shape must produce senses");
        assert!(
            joined.contains("nearness") || joined.contains("emphasis"),
            "expected the real Noun senses, got {joined:?}"
        );
        // The Verb section is a second definition, and the POS is carried over.
        assert!(
            joined.contains("place in juxtaposition"),
            "expected the Verb sense, got {joined:?}"
        );
    }

    /// The decoys: `#*` quotations and `#:` examples and `##` sub-senses must
    /// NOT become definitions. `juxtaposition` carries a Coleridge quotation
    /// under the first Noun sense; a parser that took it would return the
    /// quotation as the meaning of the word.
    #[test]
    fn en_parser_rejects_quotations_examples_and_subsenses() {
        let wt = extract_wikitext(EN_JUXTAPOSITION).unwrap();
        let defs = definitions(&wt, "en", "en");
        let joined = defs.iter().map(|d| d.text.as_str()).collect::<Vec<_>>().join(" | ");

        assert!(!joined.contains("Coleridge"), "a quotation leaked into definitions: {joined:?}");
        assert!(
            !joined.contains("mother father"),
            "an example leaked into definitions: {joined:?}"
        );
        assert!(
            !joined.contains("absence of operators"),
            "a ## sub-sense leaked into definitions: {joined:?}"
        );
        // And the Translations table is not a definition either.
        assert!(!joined.contains("justaposição"), "translations leaked: {joined:?}");
    }

    /// The en block cutter selects the language, exactly as the es one does:
    /// a language the page does not carry is empty, not an error, and the
    /// English block is not returned in its place.
    #[test]
    fn en_block_cutter_selects_the_requested_language() {
        let wt = extract_wikitext(EN_JUXTAPOSITION).unwrap();

        assert!(definitions(&wt, "ja", "ja").is_empty(), "absent language must not fall back");

        // The French block on this page is present but its only sense is a bare
        // cross-reference (`{{l|en|juxtaposition}}`), which the closed template
        // list drops as noise — so it reduces to empty rather than returning
        // the English senses. Empty is the honest outcome here.
        assert!(
            definitions(&wt, "fr", "fr").is_empty(),
            "a dropped-cross-reference sense must not borrow the English block"
        );
    }

    /// The es cutter must be untouched by the en addition: the same fixtures
    /// still parse as before.
    #[test]
    fn es_shape_is_unchanged_by_the_en_addition() {
        let house = extract_wikitext(ES_HOUSE).unwrap();
        let joined = definitions(&house, "en", "es")
            .iter()
            .map(|d| d.text.clone())
            .collect::<Vec<_>>()
            .join(" | ");
        assert!(joined.contains("casa"), "es shape regressed: {joined:?}");

        let casa = extract_wikitext(ES_CASA).unwrap();
        let es = definitions(&casa, "es", "es");
        assert!(es
            .iter()
            .any(|d| d.text.contains("edificación") || d.text.contains("edificacion")));
    }
}
