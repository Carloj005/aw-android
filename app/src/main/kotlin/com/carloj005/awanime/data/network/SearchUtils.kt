package com.carloj005.awanime.data.network

import com.carloj005.awanime.data.model.AnimeItem
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Utility di ricerca fuzzy, tolleranza ai refusi (es. "jojo steal ball run"),
 * raggruppamento franchise e ordinamento cronologico delle stagioni (SUB + ITA),
 * porting diretto e testato da `aw-cli/search_utils.py`.
 */
object SearchUtils {

    private val stopwords = setOf(
        "di", "del", "dello", "della", "dei", "degli", "delle", "da", "dal",
        "in", "il", "lo", "la", "le", "gli", "un", "uno", "una", "ed",
        "the", "of", "and", "to", "on", "at", "by", "for", "no", "ni", "wa", "ga", "wo",
    )

    private val seasonQuarter = mapOf(
        "inverno" to 1, "winter" to 1,
        "primavera" to 2, "spring" to 2,
        "estate" to 3, "summer" to 3,
        "autunno" to 4, "autumn" to 4, "fall" to 4,
    )

    private val dubTagRegex = Regex("""\s*\((?:ITA|SUB\s*ITA|CR|DUB)\)\s*""", RegexOption.IGNORE_CASE)
    private val franchiseCutRegex = Regex(
        """(?::\s+|\s+[-–—]\s*|\s+\b(?:the\s+final\s+season|final\s+season|season\s+\d+|stagione\s+\d+|part(?:e)?\s+\d+|the\s+movie\b|movie\b|film\b|ova\b|oav\b|special\b|prequel\b))""",
        RegexOption.IGNORE_CASE,
    )
    private val trailingNumRegex = Regex("""\s+(?:\d+|ii|iii|iv|v|vi)$""", RegexOption.IGNORE_CASE)
    private val partExplicitRegex = Regex("""\b(?:part(?:e)?|stage|cour|special)\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val ordinalStageRegex = Regex(
        """\b(\d+)(?:st|nd|rd|th)\s*(?:[-–—]?\s*\d+(?:st|nd|rd|th)\s*)?(?:stage|season|part|cour)?""",
        RegexOption.IGNORE_CASE,
    )
    private val trailingDigitRegex = Regex("""\s+(\d+)$""")
    private val nonAlnumRegex = Regex("""[^a-z0-9]+""")
    private val multiSpaceRegex = Regex("""\s+""")

    fun normalizeSearchText(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val noMarks = buildString(decomposed.length) {
            for (ch in decomposed) {
                if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
                    append(ch)
                }
            }
        }
        return multiSpaceRegex.replace(nonAlnumRegex.replace(noMarks.lowercase(), " "), " ").trim()
    }

    fun stripDubTags(title: String): String {
        return multiSpaceRegex.replace(dubTagRegex.replace(title, " "), " ").trim()
    }

    fun extractFranchiseKey(title: String): String {
        val base = stripDubTags(title)
        val head = franchiseCutRegex.split(base, limit = 2).firstOrNull().orEmpty().trim()
        val cut = trailingNumRegex.replace(head, "").trim()
        val norm = normalizeSearchText(cut)
        return norm.ifEmpty { normalizeSearchText(base) }
    }

    fun extractPartNumber(title: String): Int {
        val base = stripDubTags(title)
        partExplicitRegex.find(base)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return if (it >= 1) it else 1
        }
        ordinalStageRegex.find(base)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return if (it >= 1) it else 1
        }
        trailingDigitRegex.find(base)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return if (it >= 1) it else 1
        }
        return 1
    }

    fun generateFallbackQueries(query: String, maxQueries: Int = 6): List<String> {
        val norm = normalizeSearchText(query)
        if (norm.isEmpty()) return emptyList()
        val tokens = norm.split(" ").filter { it.length >= 2 }
        if (tokens.isEmpty()) return emptyList()

        val candidates = mutableListOf<String>()
        fun add(q: String) {
            val trimmed = q.trim()
            if (trimmed.length >= 3 && trimmed != norm && trimmed !in candidates) {
                candidates.add(trimmed)
            }
        }

        val sigTokens = tokens.filter { it !in stopwords && it.length >= 3 }.ifEmpty {
            tokens.filter { it.length >= 3 }.ifEmpty { tokens }
        }

        if (tokens.size >= 2) {
            val bigrams = (0 until tokens.size - 1).map { "${tokens[it]} ${tokens[it + 1]}" }
            add(bigrams.last())
            add(bigrams.first())
            if (sigTokens.isNotEmpty()) add(sigTokens.first())
            for (i in 1 until bigrams.size - 1) add(bigrams[i])
            for (tok in sigTokens.sortedByDescending { it.length }) add(tok)
        } else {
            val word = sigTokens.first()
            if (word.length >= 5) {
                for (cut in listOf(word.length - 1, word.length - 2, 4, 3)) {
                    if (cut in 3 until word.length) add(word.substring(0, cut))
                }
            }
        }
        return candidates.take(maxQueries)
    }

    /**
     * Similarità normalizzata basata su distanza di Levenshtein [0.0, 1.0].
     */
    fun similarityRatio(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val maxLen = max(a.length, b.length)
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return 1.0 - (prev[b.length].toDouble() / maxLen.toDouble())
    }

    private fun scoreSingleTitle(
        queryNorm: String,
        queryCompact: String,
        queryTokens: List<String>,
        candidateTitle: String,
    ): Double {
        val candNorm = normalizeSearchText(candidateTitle)
        if (candNorm.isEmpty()) return 0.0
        if (queryNorm == candNorm) return 1.0

        val candCompact = candNorm.replace(" ", "")
        if (queryCompact.length >= 4 && queryCompact == candCompact) return 0.99
        if (queryNorm in candNorm) return 0.96 + 0.03 * (queryNorm.length.toDouble() / candNorm.length)
        if (queryCompact.length >= 5 && queryCompact in candCompact) {
            return 0.94 + 0.03 * (queryCompact.length.toDouble() / candCompact.length)
        }

        val candTokens = candNorm.split(" ")
        if (candTokens.isEmpty() || queryTokens.isEmpty()) return 0.0

        val candCompounds = (0 until candTokens.size - 1).map { candTokens[it] + candTokens[it + 1] }
        val pool = candTokens + candCompounds

        val tokenScores = queryTokens.map { qt ->
            var best = 0.0
            for (ct in pool) {
                if (qt == ct) {
                    best = 1.0
                    break
                }
                var r = similarityRatio(qt, ct)
                if (qt.length >= 4 && ct.startsWith(qt)) {
                    r = max(r, 0.88)
                }
                if (r > best) best = r
            }
            best
        }

        val avgTok = tokenScores.average()
        val seqRatio = similarityRatio(queryNorm, candNorm)
        val compactRatio = similarityRatio(queryCompact, candCompact)

        if (queryTokens.size == 1) {
            return max(avgTok * 0.85 + max(seqRatio, compactRatio) * 0.15, compactRatio)
        }

        val matched = tokenScores.count { it >= 0.72 }
        val coverage = matched.toDouble() / tokenScores.size
        if (coverage < 0.5 && compactRatio < 0.78) return 0.0

        return 0.65 * avgTok + 0.25 * coverage + 0.10 * max(seqRatio, compactRatio)
    }

    fun scoreQueryAgainstTitles(query: String, titles: List<String?>): Double {
        val qNorm = normalizeSearchText(query)
        if (qNorm.isEmpty()) return 0.0
        val qCompact = qNorm.replace(" ", "")
        var qTokens = qNorm.split(" ")
        if (qTokens.size > 1) {
            val filtered = qTokens.filter { it !in stopwords }
            if (filtered.isNotEmpty()) qTokens = filtered
        }
        var best = 0.0
        for (title in titles) {
            if (title.isNullOrBlank()) continue
            val s = scoreSingleTitle(qNorm, qCompact, qTokens, title)
            if (s > best) best = s
        }
        return best
    }

    fun rankFuzzyCandidates(
        query: String,
        candidates: List<AnimeItem>,
        minScore: Double = 0.62,
    ): List<AnimeItem> {
        data class Scored(val score: Double, val idx: Int, val item: AnimeItem)

        val scored = candidates.mapIndexedNotNull { idx, item ->
            val aliases = listOf(item.title, item.titleJa, item.titleIt, item.slug.replace("-", " "))
            val s = scoreQueryAgainstTitles(query, aliases)
            if (s >= minScore) Scored(s, idx, item) else null
        }
        if (scored.isEmpty()) return emptyList()

        val best = scored.maxOf { it.score }
        val cutoff = if (best >= 0.85) max(minScore, best - 0.20) else minScore
        return scored
            .filter { it.score >= cutoff }
            .sortedWith(compareBy<Scored>({ -(it.score * 15).toInt() }, { it.idx }))
            .map { it.item }
    }

    /**
     * Ordina i risultati di AnimeUnity raggruppando per franchise e mettendo le stagioni
     * in ordine cronologico (Stagione 1 -> ultima stagione, SUB seguito subito da ITA).
     */
    fun sortAnimeItems(items: List<AnimeItem>, query: String = ""): List<AnimeItem> {
        if (items.size <= 1) return items

        val qNorm = normalizeSearchText(query)
        val qTokens = qNorm.split(" ").filter { it.isNotEmpty() && it !in stopwords }
            .ifEmpty { qNorm.split(" ").filter { it.isNotEmpty() } }

        val rawFKeys = items.map { extractFranchiseKey(it.title) }
        val uniqueRoots = rawFKeys.filter { it.length >= 4 }.distinct().sortedBy { it.length }

        fun canonicalFranchise(fk: String, tokens: Set<String>): String {
            if (qTokens.isNotEmpty() && qTokens.all { it in tokens }) {
                return "__query_exact__$qNorm"
            }
            for (root in uniqueRoots) {
                if (fk == root || fk.startsWith("$root ")) return root
            }
            return fk
        }

        val hasPrimaryQueryMatch = qTokens.isNotEmpty() && items.any { item ->
            val tokSet = normalizeSearchText(item.title).split(" ").toSet()
            qTokens.all { it in tokSet }
        }

        data class WorkMeta(val year: Int, val quarter: Int, val partNum: Int, val minId: Int)
        data class Decorated(
            val fKey: String,
            val baseNorm: String,
            val isDubInt: Int,
            val item: AnimeItem,
        )

        val workMeta = mutableMapOf<String, WorkMeta>()
        val franchiseFirstIdx = mutableMapOf<String, Int>()
        val franchiseBestRel = mutableMapOf<String, Int>()
        val decorated = mutableListOf<Decorated>()

        items.forEachIndexed { idx, item ->
            val baseNorm = normalizeSearchText(stripDubTags(item.title))
            val fkRaw = rawFKeys[idx]
            val aliasesNorm = listOfNotNull(item.title, item.titleJa, item.titleIt)
                .map { normalizeSearchText(it) }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            val primaryTokens = normalizeSearchText(item.title).split(" ").toSet()
            val allAliasTokens = aliasesNorm.split(" ").toSet()

            var fKey = canonicalFranchise(fkRaw, primaryTokens)
            if (!hasPrimaryQueryMatch &&
                fKey != "__query_exact__$qNorm" &&
                qTokens.isNotEmpty() &&
                qTokens.all { it in allAliasTokens }
            ) {
                fKey = "__query_exact__$qNorm"
            }

            franchiseFirstIdx.putIfAbsent(fKey, idx)

            val rel = when {
                fKey == "__query_exact__$qNorm" -> 0
                qNorm.isNotEmpty() && fkRaw == qNorm -> 0
                qNorm.isNotEmpty() && fkRaw.startsWith(qNorm) -> 1
                qNorm.isNotEmpty() && qNorm in fkRaw -> 2
                qNorm.isNotEmpty() && qNorm in aliasesNorm -> 3
                else -> 4
            }
            val prevRel = franchiseBestRel[fKey]
            if (prevRel == null || rel < prevRel) {
                franchiseBestRel[fKey] = rel
            }

            val year = item.year ?: 9999
            val quarter = seasonQuarter[item.season.lowercase().trim()] ?: 3
            val partNum = extractPartNumber(item.title)
            val recId = item.id.toIntOrNull() ?: 999999

            val existing = workMeta[baseNorm]
            if (existing == null) {
                workMeta[baseNorm] = WorkMeta(year, quarter, partNum, recId)
            } else {
                workMeta[baseNorm] = WorkMeta(
                    year = min(existing.year, year),
                    quarter = min(existing.quarter, quarter),
                    partNum = min(existing.partNum, partNum),
                    minId = min(existing.minId, recId),
                )
            }

            decorated.add(
                Decorated(
                    fKey = fKey,
                    baseNorm = baseNorm,
                    isDubInt = if (item.isDub || "(ita)" in item.title.lowercase()) 1 else 0,
                    item = item,
                )
            )
        }

        return decorated.sortedWith(
            compareBy<Decorated>(
                { franchiseBestRel[it.fKey] ?: 4 },
                { franchiseFirstIdx[it.fKey] ?: Int.MAX_VALUE },
                { workMeta[it.baseNorm]?.year ?: 9999 },
                { workMeta[it.baseNorm]?.quarter ?: 3 },
                { workMeta[it.baseNorm]?.partNum ?: 1 },
                { workMeta[it.baseNorm]?.minId ?: 999999 },
                { it.baseNorm },
                { it.isDubInt },
            )
        ).map { it.item }
    }

    /**
     * Chiave di ordinamento sicura per numeri di episodio (interi, decimali `5.5`,
     * range `3-4`, alfanumerici `7a`, speciali `SP1` / `OVA`) senza mai lanciare `NumberFormatException`.
     */
    data class EpisodeSortKey(
        val group: Int,
        val endVal: Double,
        val startVal: Double,
        val suffix: String,
    ) : Comparable<EpisodeSortKey> {
        override fun compareTo(other: EpisodeSortKey): Int {
            return compareValuesBy(this, other, { it.group }, { it.endVal }, { it.startVal }, { it.suffix })
        }
    }

    private val leadingNumRegex = Regex("""^(\d+(?:\.\d+)?)\s*(.*)$""")
    private val anyNumRegex = Regex("""(\d+(?:\.\d+)?)""")

    fun episodeSortKey(rawNum: String): EpisodeSortKey {
        val raw = rawNum.trim()
        if ("-" in raw) {
            val parts = raw.split("-", limit = 2)
            val start = parts[0].toDoubleOrNull()
            val end = parts[1].toDoubleOrNull()
            if (end != null) {
                return EpisodeSortKey(0, end, start ?: 0.0, "")
            }
        }
        raw.toDoubleOrNull()?.let { return EpisodeSortKey(0, it, it, "") }
        leadingNumRegex.matchEntire(raw)?.let { m ->
            val v = m.groupValues[1].toDouble()
            return EpisodeSortKey(0, v, v, m.groupValues[2].lowercase())
        }
        anyNumRegex.find(raw)?.let { m ->
            val v = m.groupValues[1].toDouble()
            return EpisodeSortKey(1, v, v, raw.lowercase())
        }
        return EpisodeSortKey(1, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, raw.lowercase())
    }
}
