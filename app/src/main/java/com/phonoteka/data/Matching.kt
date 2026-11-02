package com.phonoteka.data

/**
 * Логика отбора треков — фильтрация ремиксов, мешапов, лайвов и т.д.
 *
 * Перенесено из Python matching.py:
 *  - Запрещённые издания (remix, mashup, live, sped up, slowed и т.д.)
 *  - Сравнение по Levenshtein-расстоянию
 *  - Оценка кандидата (score 0-100)
 *  - Проверка artist/title match
 */
object Matching {

    // Запрещённые слова в названии трека
    private val FORBIDDEN_EDITIONS = setOf(
        "remix", "ремикс", "mashup", "мешап", "mash up", "mash-up",
        "live", "концерт", "акустика", "acoustic",
        "sped up", "sped-up", "ускор", "slowed", "slowed down", "замедл",
        "8d", "8d audio", "nightcore", "заускорен",
        "8d music", "bass boosted", "bass boost",
        "chipmunk", "vapor", "vaporwave",
        "karaoke", "караоке",
        "instrumental", "инструментал",
        "cover", "кавер",          // кавер НЕ всегда запрещён — зависит от контекста
        "radio edit", "radio version",
        "clean version", "цензур",  // ищем без цензуры
        "shortened", "обрезан",
        "teaser", "тизер", "snippet", "фрагмент",
        "preview", "превью"
    )

    // Слова, которые НЕ блокируются, даже если выглядят как запрещённые
    private val ALLOW_IN_TITLE = setOf(
        "lyric", "lyrics", "official", "официальн", "audio", "аудио",
        "official audio", "official video", "music video"
    )

    // Минимальный score для авто-принятия кандидата
    const val MIN_AUTO_SCORE = 70
    const val MIN_ARTIST_SIMILARITY = 0.80
    const val MIN_TITLE_SIMILARITY = 0.85

    /**
     * Проверяет, содержит ли строка признаки запрещённого издания.
     * Возвращает пару (запрещено?, список найденных слов).
     */
    fun findForbidden(text: String): Pair<Boolean, List<String>> {
        val lower = text.lowercase().trim()
        val found = mutableListOf<String>()

        for (word in FORBIDDEN_EDITIONS) {
            if (lower.contains(word)) {
                // Проверяем, не входит ли в разрешённые
                val isAllowed = ALLOW_IN_TITLE.any { lower.contains(it) }
                if (!isAllowed) {
                    found.add(word)
                }
            }
        }

        return (found.isNotEmpty()) to found
    }

    /**
     * Расстояние Левенштейна между двумя строками.
     * Перенесено из Python matching.py.
     */
    fun levenshtein(a: String, b: String): Int {
        val s = a.lowercase().trim()
        val t = b.lowercase().trim()
        if (s == t) return 0
        if (s.isEmpty()) return t.length
        if (t.isEmpty()) return s.length

        val prev = IntArray(t.length + 1) { it }
        val curr = IntArray(t.length + 1)

        for (i in 1..s.length) {
            curr[0] = i
            for (j in 1..t.length) {
                val cost = if (s[i - 1] == t[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,           // deletion
                    curr[j - 1] + 1,       // insertion
                    prev[j - 1] + cost     // substitution
                )
            }
            prev.indices.forEach { prev[it] = curr[it] }
        }
        return prev[t.length]
    }

    /**
     * Нормализованное сходство строк (0.0 — 1.0).
     */
    fun similarity(a: String, b: String): Double {
        if (a.isBlank() && b.isBlank()) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        val dist = levenshtein(a, b)
        return 1.0 - dist.toDouble() / maxLen
    }

    /**
     * Оценка кандидата по сравнению с запросом.
     * Возвращает score 0-100.
     */
    fun scoreCandidate(
        queryArtist: String,
        queryTitle: String,
        candidateArtist: String,
        candidateTitle: String
    ): Int {
        val artistSim = similarity(queryArtist, candidateArtist)
        val titleSim = similarity(queryTitle, candidateTitle)

        // Проверка запрещённых изданий
        val (forbidden, _) = findForbidden(candidateTitle)
        val penalty = if (forbidden) 50 else 0

        // Проверка explicit — бонус
        val explicitBonus = 5  // предпочтение explicit-версиям

        val raw = (artistSim * 40 + titleSim * 60).toInt()
        val score = (raw - penalty + explicitBonus).coerceIn(0, 100)

        return score
    }

    /**
     * Решение: принимать кандидата автоматически?
     * Соответствует auto_ok из Python core.py.
     */
    fun autoOk(
        score: Int,
        artistSim: Double,
        titleSim: Double,
        hasForbidden: Boolean
    ): Boolean {
        if (hasForbidden) return false
        if (score < MIN_AUTO_SCORE) return false
        if (artistSim < MIN_ARTIST_SIMILARITY) return false
        if (titleSim < MIN_TITLE_SIMILARITY) return false
        return true
    }

    /**
     * Полный анализ кандидата.
     */
    data class CandidateAnalysis(
        val score: Int,
        val artistSimilarity: Double,
        val titleSimilarity: Double,
        val forbiddenWords: List<String>,
        val autoOk: Boolean,
        val needsReview: Boolean
    )

    fun analyze(
        queryArtist: String,
        queryTitle: String,
        candidateArtist: String,
        candidateTitle: String,
        isExplicit: Boolean = false
    ): CandidateAnalysis {
        val artistSim = similarity(queryArtist, candidateArtist)
        val titleSim = similarity(queryTitle, candidateTitle)
        val (forbidden, words) = findForbidden(candidateTitle)
        val score = scoreCandidate(queryArtist, queryTitle, candidateArtist, candidateTitle)
        val auto = autoOk(score, artistSim, titleSim, forbidden)

        // Требует ручной проверки, если не auto_ok но score > 0
        val review = !auto && score > 0

        return CandidateAnalysis(
            score = score,
            artistSimilarity = artistSim,
            titleSimilarity = titleSim,
            forbiddenWords = words,
            autoOk = auto,
            needsReview = review
        )
    }
}
