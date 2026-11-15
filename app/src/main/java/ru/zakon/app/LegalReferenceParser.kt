package ru.zakon.app

/**
 * Parses common Russian legal references and normalizes user input before it reaches SQLite.
 * It deliberately does not infer a document when the query contains only an article number.
 */
internal data class ParsedLegalReference(
    val article: String,
    val allowSubArticles: Boolean,
    val documentHint: String?
)

internal object LegalReferenceParser {
    private val articlePattern = Regex(
        """(?iu)(?:ст(?:атья|атьи|атью)?\.?\s*)?(\d{1,4}(?:\.\d{1,3})?)"""
    )
    private val tokenPattern = Regex("""[\p{L}\p{N}]+""")
    private val ignored = setOf("ст", "статья", "статьи", "статью", "часть", "ч", "п", "пункт", "рф", "фз", "кодекс")

    fun parse(input: String): ParsedLegalReference? {
        if (!Regex("""(?iu)\bст(?:атья|атьи|атью)?\.?\s*\d""").containsMatchIn(input)) return null
        val match = articlePattern.find(input) ?: return null
        val number = match.groupValues[1]
        val normalized = normalize(input)
        val documentHint = when {
            Regex("""\bук\b|уголовн").containsMatchIn(normalized) -> "уголовный кодекс"
            Regex("""\\bгк\b|гражданск").containsMatchIn(normalized) -> "гражданский кодекс"
            Regex("""\bкоап\b|административн").containsMatchIn(normalized) -> "кодекс российской федерации об административных правонарушениях"
            Regex("""\bупк\b|уголовно процессуальн").containsMatchIn(normalized) -> "уголовно процессуальный кодекс"
            Regex("""\bтк\b|трудов").containsMatchIn(normalized) -> "трудовой кодекс"
            Regex("""\bнк\b|налогов").containsMatchIn(normalized) -> "налоговый кодекс"
            Regex("""\bжк\b|жилищн").containsMatchIn(normalized) -> "жилищный кодекс"
            Regex("""\bск\b|семейн").containsMatchIn(normalized) -> "семейный кодекс"
            else -> null
        }
        return ParsedLegalReference(number, !number.contains('.'), documentHint)
    }

    fun tokens(input: String): List<String> = tokenPattern.findAll(normalize(input))
        .map { it.value }
        .filter { it.length >= 2 && it !in ignored }
        .distinct()
        .take(12)
        .toList()

    fun normalize(input: String): String = input.lowercase()
        .replace('ё', 'е')
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
}
