package com.carloj005.awanime.data.network

import com.carloj005.awanime.data.model.SkipKind
import com.carloj005.awanime.data.model.SkipSegment
import kotlin.math.abs

data class RawSkipCandidate(
    val kind: SkipKind,
    val startMs: Long,
    val endMs: Long,
    val episodeLengthSec: Double? = null,
    val source: String,
    val isMixed: Boolean = false,
    val confidence: Double? = null,
)

data class AnimeSkipMarker(
    val atSec: Double,
    val typeName: String,
)

data class AnimeSkipEpisodeResult(
    val segments: List<RawSkipCandidate> = emptyList(),
    val confirmsNoIntro: Boolean = false,
    val matchedBaseDurationSec: Double? = null,
)

/**
 * Motore universale di validazione e consenso per i timestamp di Intro / Outro / Recap:
 * - Valida durata e posizione temporale di ogni segmento (blocca falsi ED a inizio episodio e falsi OP a fine episodio)
 * - Verifica tolleranza stretta tra `episodeLength` del DB e durata reale del video (±12s)
 * - Confronta in consenso multi-sorgente AniSkip (exact + global) e Anime-Skip GraphQL per eliminare
 *   automaticamente le segnalazioni errate o troll (es. Ep. 1 senza sigla come Chainsaw Man, Solo Leveling, Frieren)
 */
object SkipTimeValidator {

    private const val MAX_EPISODE_LENGTH_DRIFT_SEC = 12.0
    private const val MAX_SELF_CONFLICT_MS = 12_000L
    private const val MAX_CROSS_DB_START_DIFF_MS = 12_000L
    private const val MAX_CROSS_DB_END_DIFF_MS = 18_000L

    fun isStructurallyValid(
        cand: RawSkipCandidate,
        realDurationSec: Int,
    ): Boolean {
        if (cand.startMs < 0L || cand.endMs <= cand.startMs) return false
        val startSec = cand.startMs / 1000.0
        val endSec = cand.endMs / 1000.0
        val segDurSec = endSec - startSec

        // 1. Limiti di durata realistici per tipo di segmento
        val durOk = when (cand.kind) {
            SkipKind.INTRO -> segDurSec in 35.0..135.0
            SkipKind.OUTRO -> segDurSec in 35.0..150.0
            SkipKind.RECAP -> segDurSec in 20.0..240.0
        }
        if (!durOk) return false

        // 2. Soglia minima di confidenza per il rilevatore acustico VixCloud
        if (cand.source == "vixcloud" && cand.confidence != null && cand.confidence < 10.0) {
            return false
        }

        // 3. Per sorgenti crowdsourced, la durata dell'episodio nel DB deve combaciare con quella reale (±12s)
        if (cand.source != "vixcloud" && realDurationSec > 0) {
            val dbLen = cand.episodeLengthSec
            if (dbLen != null && dbLen > 60.0 && abs(dbLen - realDurationSec) > MAX_EPISODE_LENGTH_DRIFT_SEC) {
                return false
            }
        }

        // 4. Regole posizionali sulla timeline per episodi standard (>= 10 min)
        if (realDurationSec >= 600) {
            val dur = realDurationSec.toDouble()
            if (startSec >= dur - 5.0) return false
            when (cand.kind) {
                SkipKind.INTRO -> {
                    val maxStart = if (cand.source == "vixcloud") {
                        dur * 0.45
                    } else {
                        minOf(dur * 0.40, 480.0)
                    }
                    if (startSec > maxStart) return false
                }
                SkipKind.OUTRO -> {
                    // Un'outro/ending deve trovarsi nella parte finale (ultimo 35% dell'episodio)
                    if (startSec < dur * 0.65 || endSec < dur * 0.72) return false
                }
                SkipKind.RECAP -> {
                    if (startSec > dur * 0.40) return false
                }
            }
        }

        return true
    }

    private fun toSkipSegment(
        cand: RawSkipCandidate,
        realDurationSec: Int,
        verified: Boolean,
        overrideSource: String = cand.source,
    ): SkipSegment {
        val clampedEnd = if (realDurationSec > 0) {
            cand.endMs.coerceAtMost(realDurationSec * 1000L)
        } else {
            cand.endMs
        }
        return SkipSegment(
            kind = cand.kind,
            startMs = cand.startMs.coerceAtLeast(0L),
            endMs = clampedEnd.coerceAtLeast(cand.startMs + 1_000L),
            source = overrideSource,
            verified = verified,
        )
    }

    /**
     * Valida e deduplica i timestamp nativi rilevati acusticamente da VixCloud.
     */
    fun validateVixCloudSegments(
        raw: List<RawSkipCandidate>,
        realDurationSec: Int,
    ): List<SkipSegment> {
        val valid = raw.filter { isStructurallyValid(it, realDurationSec) }
        val byKind = LinkedHashMap<SkipKind, RawSkipCandidate>()
        for (c in valid.sortedByDescending { it.confidence ?: 100.0 }) {
            byKind.putIfAbsent(c.kind, c)
        }
        return byKind.values
            .map { toSkipSegment(it, realDurationSec, verified = true) }
            .sortedBy { it.startMs }
    }

    /**
     * Converte i marker sequenziali di Anime-Skip GraphQL (`Intro`, `New Intro`, `Canon`, `Credits`, `Preview`, ...)
     * in segmenti con inizio e fine verificati.
     */
    fun parseAnimeSkipEpisode(
        markers: List<AnimeSkipMarker>,
        baseDurationSec: Double,
        realDurationSec: Int,
    ): AnimeSkipEpisodeResult {
        if (markers.isEmpty() || baseDurationSec <= 60.0) {
            return AnimeSkipEpisodeResult()
        }
        if (realDurationSec > 0 && abs(baseDurationSec - realDurationSec) > MAX_EPISODE_LENGTH_DRIFT_SEC) {
            return AnimeSkipEpisodeResult()
        }

        val sorted = markers.sortedBy { it.atSec }
        val segments = mutableListOf<RawSkipCandidate>()
        var hasAnyIntroMarker = false
        var hasCanonMarker = false
        var hasCreditsOrPreviewMarker = false

        for (i in sorted.indices) {
            val cur = sorted[i]
            val typeLower = cur.typeName.trim().lowercase()
            val startSec = cur.atSec.coerceAtLeast(0.0)
            val nextSec = sorted.getOrNull(i + 1)?.atSec

            when {
                typeLower == "canon" -> hasCanonMarker = true
                typeLower == "preview" -> hasCreditsOrPreviewMarker = true
                typeLower in setOf("intro", "new intro", "mixed intro") -> {
                    hasAnyIntroMarker = true
                    // Un Intro senza marker successivo NON ha fine valida (evita marker orfani come CSM Ep 1)
                    if (nextSec != null && nextSec > startSec) {
                        segments.add(
                            RawSkipCandidate(
                                kind = SkipKind.INTRO,
                                startMs = (startSec * 1000.0).toLong(),
                                endMs = (nextSec * 1000.0).toLong(),
                                episodeLengthSec = baseDurationSec,
                                source = "animeskip",
                                isMixed = "mixed" in typeLower,
                            )
                        )
                    }
                }
                typeLower in setOf("credits", "new credits", "mixed credits", "outro") -> {
                    hasCreditsOrPreviewMarker = true
                    val endSec = nextSec ?: baseDurationSec
                    if (endSec > startSec) {
                        segments.add(
                            RawSkipCandidate(
                                kind = SkipKind.OUTRO,
                                startMs = (startSec * 1000.0).toLong(),
                                endMs = (endSec * 1000.0).toLong(),
                                episodeLengthSec = baseDurationSec,
                                source = "animeskip",
                                isMixed = "mixed" in typeLower,
                            )
                        )
                    }
                }
                typeLower == "recap" -> {
                    if (nextSec != null && nextSec > startSec) {
                        segments.add(
                            RawSkipCandidate(
                                kind = SkipKind.RECAP,
                                startMs = (startSec * 1000.0).toLong(),
                                endMs = (nextSec * 1000.0).toLong(),
                                episodeLengthSec = baseDurationSec,
                                source = "animeskip",
                            )
                        )
                    }
                }
            }
        }

        val validSegments = segments.filter { isStructurallyValid(it, realDurationSec) }
        val confirmsNoIntro = !hasAnyIntroMarker && hasCanonMarker && hasCreditsOrPreviewMarker
        return AnimeSkipEpisodeResult(
            segments = validSegments,
            confirmsNoIntro = confirmsNoIntro,
            matchedBaseDurationSec = baseDurationSec,
        )
    }

    /**
     * Riconcilia i timestamp crowdsourced da AniSkip (`episodeLength=reale` + `episodeLength=0`)
     * e Anime-Skip GraphQL eliminando contraddizioni, troll e tagli con durata diversa.
     */
    fun reconcileCrowdsourcedSegments(
        aniSkipExact: List<RawSkipCandidate>,
        aniSkipGlobal: List<RawSkipCandidate>,
        animeSkip: AnimeSkipEpisodeResult,
        realDurationSec: Int,
    ): List<SkipSegment> {
        fun pickBestByKind(list: List<RawSkipCandidate>): Map<SkipKind, RawSkipCandidate> {
            val map = LinkedHashMap<SkipKind, RawSkipCandidate>()
            // Preferisci sempre non-mixed (op/ed) rispetto a mixed-op/mixed-ed
            for (c in list.sortedBy { if (it.isMixed) 1 else 0 }) {
                if (isStructurallyValid(c, realDurationSec)) {
                    map.putIfAbsent(c.kind, c)
                }
            }
            return map
        }

        val exactMap = pickBestByKind(aniSkipExact)
        val globalMap = pickBestByKind(aniSkipGlobal)
        // Conserva anche il miglior candidato globale senza filtro episodeLength SOLO per rilevare auto-conflitti interni di AniSkip
        val rawGlobalByKind = LinkedHashMap<SkipKind, RawSkipCandidate>()
        for (c in aniSkipGlobal.sortedBy { if (it.isMixed) 1 else 0 }) {
            if (isStructurallyValid(c, realDurationSec = 0)) {
                rawGlobalByKind.putIfAbsent(c.kind, c)
            }
        }
        val askipMap = pickBestByKind(animeSkip.segments)

        fun agree(a: RawSkipCandidate, b: RawSkipCandidate): Boolean {
            return abs(a.startMs - b.startMs) <= MAX_CROSS_DB_START_DIFF_MS &&
                abs(a.endMs - b.endMs) <= MAX_CROSS_DB_END_DIFF_MS
        }

        val result = mutableListOf<SkipSegment>()
        for (kind in listOf(SkipKind.INTRO, SkipKind.OUTRO, SkipKind.RECAP)) {
            val aniExact = exactMap[kind]
            val aniGlobal = globalMap[kind]
            val aniGlobalUnfiltered = rawGlobalByKind[kind]
            val animeSkipSeg = askipMap[kind]

            // 1. Consenso incrociato tra AniSkip e Anime-Skip -> massima affidabilità (verified = true)
            if (animeSkipSeg != null) {
                if (aniExact != null && agree(aniExact, animeSkipSeg)) {
                    result.add(toSkipSegment(aniExact, realDurationSec, verified = true, overrideSource = "verified"))
                    continue
                }
                if (aniGlobal != null && agree(aniGlobal, animeSkipSeg)) {
                    result.add(toSkipSegment(aniGlobal, realDurationSec, verified = true, overrideSource = "verified"))
                    continue
                }
            }

            // 2. Veto esplicito di Anime-Skip su INTRO quando l'episodio ha Canon+Credits puliti e ZERO Intro
            if (kind == SkipKind.INTRO && animeSkip.confirmsNoIntro) {
                continue
            }

            // 3. Conflitto diretto tra AniSkip e Anime-Skip sullo stesso tipo di segmento -> rifiuta entrambi!
            if (animeSkipSeg != null && (aniExact != null || aniGlobal != null)) {
                continue
            }

            // 4. Auto-conflitto interno di AniSkip (es. Chainsaw Man Ep 1: len=1525 -> 363s vs len=0 -> 230s!)
            if (aniExact != null && aniGlobalUnfiltered != null &&
                abs(aniExact.startMs - aniGlobalUnfiltered.startMs) > MAX_SELF_CONFLICT_MS
            ) {
                continue
            }

            // 5. Singola sorgente coerente e strutturalmente valida
            when {
                aniExact != null -> result.add(toSkipSegment(aniExact, realDurationSec, verified = false))
                animeSkipSeg != null -> result.add(toSkipSegment(animeSkipSeg, realDurationSec, verified = false))
                aniGlobal != null && (realDurationSec <= 0 || aniGlobal.episodeLengthSec == null ||
                    abs(aniGlobal.episodeLengthSec - realDurationSec) <= MAX_EPISODE_LENGTH_DRIFT_SEC) -> {
                    result.add(toSkipSegment(aniGlobal, realDurationSec, verified = false))
                }
            }
        }

        return result.sortedBy { it.startMs }
    }
}
