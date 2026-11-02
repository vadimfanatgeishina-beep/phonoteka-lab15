package com.phonoteka.network

import java.text.Normalizer
import java.security.MessageDigest

/**
 * Port of Python `matching.py` + `text_utils.py`.
 *
 * Conservative matching of requested studio tracks to public media.
 * Filters out remix/cover/live/mashup/sped-up/slowed/AI-cover/etc.
 * Uses token/sequence similarity (Levenshtein-based) instead of substring tests.
 */
object Matching {

    // ----------------------------------------------------------------------- //
    // Text helpers (from text_utils.py)
    // ----------------------------------------------------------------------- //

    /** NFKC normalise → casefold → ё→е → collapse whitespace. */
    fun norm(value: String?): String {
        val s = value.orEmpty()
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
        return nfkc.lowercase().replace("ё", "е")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    /** Extract word tokens from normalised text. */
    fun words(value: String?): String {
        return norm(value).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    /** SHA-256 of a string, first 24 hex chars — used as a stable ID. */
    fun digest(value: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(24)
    }

    /** Detect data-quality issues in artist/title. */
    fun issues(artist: String, title: String): List<String> {
        val value = "$artist $title"
        val result = mutableListOf<String>()
        if ("..." in value || "…" in value)
            result.add("Обрезанное название или многоточие: нужна проверка")
        if ("*" in value)
            result.add("Звёздочки в названии: возможна маскировка слов")
        if (norm(artist) in setOf("name not found", "various artists", "unknown", "неизвестный"))
            result.add("Неизвестный или общий исполнитель")
        return result
    }

    // ----------------------------------------------------------------------- //
    // Edition vocabulary
    // ----------------------------------------------------------------------- //

    /** Editions that describe a different recording than the requested studio track. */
    val EDITION_PATTERNS: Map<String, Regex> = mapOf(
        "remix" to Regex("\\bremix\\b|\\brmx\\b|ремикс|\\bflip\\b|\\bbootleg\\b|\\brework\\b|\\bvip\\b", RegexOption.IGNORE_CASE),
        "cover" to Regex("\\bcover\\b|\\bcovers\\b|кавер", RegexOption.IGNORE_CASE),
        "live" to Regex("\\blive\\b|\\blive version\\b|концерт\\w*", RegexOption.IGNORE_CASE),
        "slow" to Regex("\\bslow(?:ed)?\\b|\\bslowed\\s*down\\b|замедлен", RegexOption.IGNORE_CASE),
        "sped" to Regex("\\bsped[\\s-]*up\\b|\\bspeed[\\s-]*up\\b|\\bspeedup\\b|\\baccelerat(?:ed|ing|ion)\\b|ускорен|\\bnightcore\\b", RegexOption.IGNORE_CASE),
        "instrumental" to Regex("\\binstrumental\\b|инструментал|\\bkaraoke\\b|караоке|\\bbacking track\\b", RegexOption.IGNORE_CASE),
        "acoustic" to Regex("\\bacoustic\\b|акустич", RegexOption.IGNORE_CASE),
        "8d" to Regex("\\b8d\\b", RegexOption.IGNORE_CASE),
        "bass" to Regex("\\bbass\\s*boost(?:ed)?\\b|\\bboosted\\b|\\blow\\s*bass\\b", RegexOption.IGNORE_CASE),
        "reverb" to Regex("\\breverb\\b|\\becho\\b", RegexOption.IGNORE_CASE),
        "mashup" to Regex("\\bmash[\\s-]*up\\b|м[еэ]шап|м[еэ]ш[\\s-]*ап|\\bmedley\\b", RegexOption.IGNORE_CASE),
        "snippet" to Regex("\\bsnippet\\b|\\bpreview\\b|\\bteaser\\b|сниппет|отрывок", RegexOption.IGNORE_CASE),
        "loop" to Regex("\\b(?:1|one|10)\\s*hour|\\bon\\s+repeat\\b|\\bloop(?:ed)?\\b", RegexOption.IGNORE_CASE),
        "ai" to Regex("\\bai\\s+(?:cover|version)\\b|нейрокавер", RegexOption.IGNORE_CASE),
        "remaster" to Regex("\\bremaster(?:ed)?\\b", RegexOption.IGNORE_CASE),
        "female" to Regex("\\bfemale\\b|\\bmale\\b", RegexOption.IGNORE_CASE),
        "edit" to Regex("\\bedit\\b|\\bedited\\b", RegexOption.IGNORE_CASE),
        "rock" to Regex("\\b(?:rock|metal|piano|guitar|violin|orchestral)\\s+(?:cover|version)\\b", RegexOption.IGNORE_CASE),
        "extended" to Regex("\\bextended\\b", RegexOption.IGNORE_CASE),
        "club" to Regex("\\bclub\\s+(?:mix|version)\\b", RegexOption.IGNORE_CASE),
        "demo" to Regex("\\bdemo\\b", RegexOption.IGNORE_CASE),
        "reprise" to Regex("\\breprise\\b", RegexOption.IGNORE_CASE),
        "playlist" to Regex("\\bplaylist\\b|\\bfull\\s+album\\b|\\bcompilation\\b|\\bmixtape\\b", RegexOption.IGNORE_CASE),
    )

    /** Neutral qualifiers: stripped but never block. */
    val NEUTRAL_PATTERNS: Map<String, Regex> = mapOf(
        "official" to Regex("\\bofficial\\b", RegexOption.IGNORE_CASE),
        "audio" to Regex("\\baudio\\b|\\baudio\\s+only\\b", RegexOption.IGNORE_CASE),
        "lyric" to Regex("\\blyric(?:s|al)?\\b|\\bтекст\\b", RegexOption.IGNORE_CASE),
        "video" to Regex("\\bvideo\\b|\\bmusic\\s+video\\b|\\bвидео\\b", RegexOption.IGNORE_CASE),
        "visualizer" to Regex("\\bvisuali[sz]er\\b", RegexOption.IGNORE_CASE),
        "hd" to Regex("\\b(?:hd|hq|4k|1080p|720p)\\b", RegexOption.IGNORE_CASE),
        "original" to Regex("\\boriginal\\b|\\bоригинал\\b", RegexOption.IGNORE_CASE),
        "stereo" to Regex("\\bstereo\\b|\\bmono\\b", RegexOption.IGNORE_CASE),
    )

    val FORBIDDEN_EDITIONS: Set<String> = EDITION_PATTERNS.keys - "remaster"
    val NEUTRAL_EDITIONS: Set<String> = NEUTRAL_PATTERNS.keys + "remaster"

    private val EXPLICIT = Regex("\\bun[\\s-]*censored\\b|\\bexplicit\\b|без\\s+цензуры|нецензурн\\w*", RegexOption.IGNORE_CASE)
    private val EXPLICIT_NEG = Regex("\\b(?:not|no|never|without)\\s+(?:an?\\s+)?(?:explicit|uncensored)\\b|\\bбез\\s+нецензурн\\w*", RegexOption.IGNORE_CASE)
    private val CLEAN = Regex(
        "\\bclean\\s+(?:version|edit|mix)\\b|\\bradio\\s+(?:edit|version|mix)\\b|\\bcensored\\b|\\bfamily\\s+friendly\\b|с\\s+цензурой|цензурная\\s+версия",
        RegexOption.IGNORE_CASE
    )
    private val PROMO = Regex("\\[(?:@[^\\]]+|[^\\]]*\\.(?:net|com|ru|org)[^\\]]*)\\]", RegexOption.IGNORE_CASE)
    private val PLAYLIST = Regex(
        "\\bfull\\s+album\\b|\\bplaylist\\b|\\bmixtape\\b|\\bcompilation\\b|\\balbum\\s+stream\\b|\\b(?:1|one|10)\\s*hour\\b",
        RegexOption.IGNORE_CASE
    )

    private val BRACKET = Regex("[\\[(]([^\\]]*)(?:[\\])]|$)")
    private val TRAILING_DASH = Regex("\\s+[-–—]\\s+(.+)$")
    private val MASHUP_JOIN = Regex("\\S+\\s+[x×]\\s+\\S+")

    private val FEATURE_MARK = setOf("feat", "ft", "featuring", "prod", "produced")

    private val CREDIT_FILLER = setOf(
        // English edition/credit vocabulary
        "remix", "rmx", "mix", "mixed", "remixed", "cover", "covers", "live", "version",
        "edit", "edited", "flip", "bootleg", "rework", "vip", "mashup", "mash", "medley",
        "nightcore", "slowed", "slow", "sped", "speed", "up", "down",
        "accelerated", "accelerating", "acceleration", "instrumental",
        "karaoke", "acoustic", "8d", "bass", "boost", "boosted", "reverb", "echo", "loop",
        "looped", "repeat", "snippet", "preview", "teaser", "ai", "remaster", "remastered",
        "radio", "extended", "club", "demo", "reprise", "female", "male", "explicit",
        "clean", "censored", "uncensored", "family", "friendly", "full", "album", "playlist",
        "compilation", "mixtape", "hour", "hours",
        // Neutral qualifiers
        "official", "audio", "lyric", "lyrics", "video", "visualizer", "mv", "hd", "hq",
        "4k", "1080p", "720p", "stereo", "mono", "original", "digital", "deluxe", "bonus",
        "track", "disc", "cd",
        // Structural/credit connectors
        "the", "a", "an", "of", "at", "from", "in", "on", "with", "and", "or", "to", "for",
        "by", "feat", "ft", "featuring", "prod", "produced",
        // Russian
        "ремикс", "кавер", "живой", "живая", "версия", "микс", "замедлен", "замедленная",
        "ускорен", "ускоренная", "инструментал", "караоке", "акустика", "акустическая",
        "нейрокавер", "мешап", "мэшап", "ремастер", "радио", "редакция", "цензура",
        "цензурой", "без", "с", "и", "полный", "альбом", "сборник", "трек", "песня",
        "оригинал", "официальный", "аудио", "текст", "видео", "версии",
    )

    private val NEUTRAL_WORDS = setOf(
        "remaster", "remastered", "official", "audio", "lyric", "lyrics", "video",
        "visualizer", "hd", "hq", "4k", "stereo", "mono", "original",
    )

    private val STRONG_TRAILING = setOf(
        "remix", "rmx", "mix", "cover", "covers", "version", "edit", "edited", "flip",
        "bootleg", "rework", "vip", "mashup", "mash", "medley", "nightcore", "slowed",
        "accelerated", "accelerating", "acceleration",
        "instrumental", "karaoke", "acoustic", "reverb", "boost", "boosted", "loop",
        "snippet", "preview", "teaser", "remaster", "remastered", "live", "radio",
        "extended", "club", "demo", "reprise", "playlist", "compilation", "album", "up", "down",
    )

    private val AMBIGUOUS_SOLE = setOf("live", "mix", "rock")

    // ----------------------------------------------------------------------- //
    // Edition detection
    // ----------------------------------------------------------------------- //

    /** Every edition/qualifier key that appears in [value]. */
    fun editions(value: String?): Set<String> {
        val text = norm(value)
        val found = mutableSetOf<String>()
        for ((key, pat) in EDITION_PATTERNS) if (pat.containsMatchIn(text)) found.add(key)
        for ((key, pat) in NEUTRAL_PATTERNS) if (pat.containsMatchIn(text)) found.add(key)
        return found
    }

    /** True when [segment] is a version credit, not a real title fragment. */
    private fun isEditionQualifier(segment: String): Boolean {
        val toks = words(segment).split(" ").filter { it.isNotEmpty() }
        if (toks.isEmpty()) return false
        if (PLAYLIST.containsMatchIn(segment)) return true
        val marks = editions(segment)
        if (marks.intersect(FORBIDDEN_EDITIONS + NEUTRAL_EDITIONS).isEmpty()) return false
        if (Regex("\\blive\\s+(?:at|from|in)\\b", RegexOption.IGNORE_CASE).containsMatchIn(toks.joinToString(" "))) return true
        if (toks.last() in STRONG_TRAILING) return true
        return toks.all { it in CREDIT_FILLER || it.all { c -> c.isDigit() } }
    }

    /** Forbidden edition credits in trailing tokens of [value] (no brackets). */
    private fun trailingCredit(value: String): Set<String> {
        val toks = words(value).split(" ").filter { it.isNotEmpty() }
        if (toks.isEmpty()) return emptySet()
        for (start in toks.indices) {
            val segment = toks.drop(start).joinToString(" ")
            if (!isEditionQualifier(segment)) continue
            if (start == 0 && toks.size == 1 && toks[0] in AMBIGUOUS_SOLE) return emptySet()
            return editions(segment).intersect(FORBIDDEN_EDITIONS)
        }
        return emptySet()
    }

    /** Forbidden edition credits that are qualifiers of [value]. */
    fun editionCredits(value: String?): Set<String> {
        val text = PROMO.replace(value.orEmpty(), "")
        val found = mutableSetOf<String>()
        for (group in BRACKET.findAll(text)) {
            if (isEditionQualifier(group.groupValues[1]))
                found.addAll(editions(group.groupValues[1]).intersect(FORBIDDEN_EDITIONS))
        }
        val tail = TRAILING_DASH.find(text)
        if (tail != null && isEditionQualifier(tail.groupValues[1]))
            found.addAll(editions(tail.groupValues[1]).intersect(FORBIDDEN_EDITIONS))
        val bare = TRAILING_DASH.replace(BRACKET.replace(text, " "), " ")
        found.addAll(trailingCredit(bare))
        return found
    }

    /** Drop the longest trailing credit phrase, keeping at least one title token. */
    private fun stripTrailingCredit(value: String): String {
        val toks = value.split(" ").filter { it.isNotEmpty() }
        for (start in 1 until toks.size) {
            if (isEditionQualifier(toks.drop(start).joinToString(" ")))
                return toks.take(start).joinToString(" ")
        }
        return value
    }

    /** Remove edition credits from a title without damaging the real title. */
    fun originalTitle(title: String?): String {
        val value = PROMO.replace(title.orEmpty(), "")
        // Strip bracket groups that are edition qualifiers
        var result = value.replace(BRACKET) { m ->
            if (isEditionQualifier(m.groupValues[1])) "" else m.value
        }
        // Strip trailing dash credits
        result = result.replace(TRAILING_DASH) { m ->
            if (isEditionQualifier(m.groupValues[1])) "" else m.value
        }
        result = stripTrailingCredit(result)
        val cleaned = result.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        return cleaned.ifEmpty {
            PROMO.replace(title.orEmpty(), "").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }

    // ----------------------------------------------------------------------- //
    // Scoring
    // ----------------------------------------------------------------------- //

    /** Levenshtein distance between two strings. */
    private fun levenshtein(a: String, b: String): Int {
        val la = a.length
        val lb = b.length
        if (la == 0) return lb
        if (lb == 0) return la
        val dp = IntArray(lb + 1) { it }
        for (i in 1..la) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..lb) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[lb]
    }

    /** SequenceMatcher-like ratio using Levenshtein. */
    private fun ratio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
    }

    /** Phrase-level similarity: 1.0 if [needle] is a contiguous phrase in [haystack]. */
    fun phraseScore(needle: String, haystack: String): Double {
        val a = words(needle)
        val b = words(haystack)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (" $a " in " $b ") return 1.0
        val aa = a.split(" ").filter { it.isNotEmpty() }
        val bb = b.split(" ").filter { it.isNotEmpty() }
        if (aa.size == 1) {
            return bb.maxOfOrNull { ratio(a, it) } ?: 0.0
        }
        val windows = (0..maxOf(0, bb.size - aa.size)).map { i ->
            bb.subList(i, minOf(i + aa.size, bb.size)).joinToString(" ")
        }
        return windows.maxOfOrNull { ratio(a, it) } ?: 0.0
    }

    private fun featureNames(title: String): Set<String> {
        val toks = words(title).split(" ").filter { it.isNotEmpty() }
        val names = mutableSetOf<String>()
        for ((i, token) in toks.withIndex()) {
            if (token in FEATURE_MARK) names.addAll(toks.drop(i + 1))
        }
        return names
    }

    private fun residualExtra(residual: List<String>, allow: Set<String>): List<String> {
        val neutral = residual.any { it in NEUTRAL_WORDS }
        return residual.filter { it !in allow && !(it.all { c -> c.isDigit() } && neutral) }
    }

    /**
     * Token/sequence similarity between the requested title and a candidate title.
     * Extra content words are penalised: "Song" vs "Song 2" scores ~0.75, not 1.0.
     */
    fun titleSimilarity(target: String, title: String, allowed: Set<String> = emptySet()): Double {
        val a = words(target)
        val b = words(title)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val at = a.split(" ").filter { it.isNotEmpty() }
        val bt = b.split(" ").filter { it.isNotEmpty() }
        val allow = allowed + CREDIT_FILLER
        val n = at.size
        val m = bt.size
        var partial = 0.0
        for (index in 0..(m - n)) {
            if (bt.subList(index, index + n) == at) {
                val residual = (bt.take(index) + bt.drop(index + n))
                val extra = residualExtra(residual, allow)
                if (extra.isEmpty()) return 1.0
                partial = maxOf(partial, 1.0 - 0.5 * extra.size / maxOf(1, m))
            }
        }
        if (partial > 0) return (partial * 10000).toInt() / 10000.0
        // Fallback: F1-like score + sequence ratio
        val ca = at.groupingBy { it }.eachCount()
        val cb = bt.groupingBy { it }.eachCount()
        val common = ca.keys.intersect(cb.keys).sumOf { minOf(ca[it]!!, cb[it]!!) }
        val precision = common.toDouble() / m
        val recall = common.toDouble() / n
        val f1 = if (precision + recall > 0) 2 * precision * recall / (precision + recall) else 0.0
        val r = ratio(a, b)
        return maxOf(f1, r)
    }

    private fun extraWords(target: String, title: String, allowed: Set<String> = emptySet()): List<String> {
        val at = words(target).split(" ").filter { it.isNotEmpty() }
        val bt = words(title).split(" ").filter { it.isNotEmpty() }
        if (at.isEmpty() || bt.isEmpty()) return emptyList()
        val allow = allowed + CREDIT_FILLER
        val n = at.size
        val m = bt.size
        for (index in 0..(m - n)) {
            if (bt.subList(index, index + n) == at) {
                val residual = (bt.take(index) + bt.drop(index + n))
                return residualExtra(residual, allow)
            }
        }
        return residualExtra(bt, allow)
    }

    // ----------------------------------------------------------------------- //
    // Censorship labels
    // ----------------------------------------------------------------------- //

    private fun explicitHits(value: String?): String? {
        val text = EXPLICIT_NEG.replace(value.orEmpty(), " ")
        val toks = words(text).split(" ").filter { it.isNotEmpty() }
        for (start in toks.indices) {
            val suffix = toks.drop(start).joinToString(" ")
            val match = EXPLICIT.find(suffix) ?: continue
            val leftover = words(EXPLICIT.replace(suffix, " ")).split(" ").filter { it.isNotEmpty() }
            if (leftover.isNotEmpty() && !leftover.all { it in CREDIT_FILLER || it.all { c -> c.isDigit() } })
                continue
            return words(match.value)
        }
        return null
    }

    /** True only when explicit is a version qualifier of [title]. */
    fun explicitMarker(title: String?, target: String? = null, allowed: Set<String> = emptySet()): Boolean {
        val text = PROMO.replace(title.orEmpty(), "")
        val exclude = words(target.orEmpty()).split(" ").toMutableSet()
        exclude.addAll(allowed.flatMap { words(it).split(" ") })
        val hits = mutableListOf<String?>()
        for (group in BRACKET.findAll(text)) {
            hits.add(explicitHits(group.groupValues[1]))
        }
        val tail = TRAILING_DASH.find(text)
        if (tail != null) hits.add(explicitHits(tail.groupValues[1]))
        hits.add(explicitHits(TRAILING_DASH.replace(BRACKET.replace(text, " "), " ")))
        return hits.any { hit ->
            val hw = words(hit).split(" ").filter { it.isNotEmpty() }
            hw.isNotEmpty() && hw.toSet() !in exclude.map { setOf(it) }
        }
    }

    /** True for a qualified clean marker ("Clean Version", "Radio Edit", "(Clean)", "Censored"). */
    fun cleanMarker(title: String?, target: String? = null): Boolean {
        val value = EXPLICIT.replace(title.orEmpty(), "")
        if (CLEAN.containsMatchIn(value)) return true
        for (group in BRACKET.findAll(value)) {
            if (Regex("\\bclean\\b|\\bcensored\\b|family\\s+friendly|с\\s+цензурой", RegexOption.IGNORE_CASE)
                    .containsMatchIn(norm(group.groupValues[1]))) return true
        }
        return false
    }

    /** True when a bare trailing "clean" is ambiguous (may be part of the title). */
    fun ambiguousClean(title: String?, target: String? = null, allowed: Set<String> = emptySet()): Boolean {
        val value = title.orEmpty()
        if (cleanMarker(value, target)) return false
        val toks = words(value).split(" ").filter { it.isNotEmpty() }
        if (toks.isEmpty() || toks.last() != "clean") return false
        val targetToks = words(target.orEmpty()).split(" ").filter { it.isNotEmpty() }
        if (targetToks.isNotEmpty() && targetToks.last() == "clean") return false
        val content = toks.filter { it !in allowed }
        if (content.isEmpty() || content.last() != "clean") return false
        if (targetToks.isNotEmpty() && content == targetToks) return false
        return true
    }

    // ----------------------------------------------------------------------- //
    // Source credit reasons
    // ----------------------------------------------------------------------- //

    fun sourceCreditReasons(rawTarget: String?): List<String> {
        val reasons = mutableListOf<String>()
        val text = rawTarget.orEmpty()
        if ("mashup" in editions(text) || (MASHUP_JOIN.containsMatchIn(norm(text)) && editions(text).intersect(FORBIDDEN_EDITIONS).isNotEmpty()))
            reasons.add("Мэшап или объединение нескольких песен: оригинал определяется вручную")
        if (words(originalTitle(text)).isEmpty())
            reasons.add("Название состоит только из пометок версии; оригинал определяется вручную")
        if (Regex("\\bprod\\.?\\b|\\bproduced\\s+by\\b|продюсер", RegexOption.IGNORE_CASE).containsMatchIn(norm(text)) &&
            !Regex("\\(?\\s*prod\\.?\\s*(?:by\\s+)?[^)]*\\)?\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(text))
            reasons.add("Продюсерский кредит не отделяется уверенно; проверьте название")
        return reasons
    }

    // ----------------------------------------------------------------------- //
    // Candidate evaluation
    // ----------------------------------------------------------------------- //

    /**
     * Score one search result against the requested track.
     * Returns a [CandidateScore] with score, blocked flag, and reasons.
     */
    fun evaluateCandidate(
        trackArtist: String,
        trackTitle: String,
        candidateTitle: String,
        candidateUploader: String,
        candidateArtist: String?,
        durationSec: Long?
    ): CandidateScore {
        val rawTarget = trackTitle
        val target = originalTitle(rawTarget)
        val primary = Regex("\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|,|\\s+&\\s+", RegexOption.IGNORE_CASE)
            .split(trackArtist).firstOrNull()?.trim() ?: trackArtist
        val allowed = words(primary).split(" ").toSet() +
            words(candidateUploader).split(" ").toSet() +
            featureNames(candidateTitle)

        val titleScore = titleSimilarity(target, candidateTitle, allowed)
        val artistScore = phraseScore(primary, "$candidateTitle $candidateUploader ${candidateArtist.orEmpty()}")
        val score = (80 * titleScore + 20 * artistScore).toInt()

        val explicit = explicitMarker(candidateTitle, target, allowed)
        val clean = cleanMarker(candidateTitle, target)
        val softClean = ambiguousClean(candidateTitle, target, allowed)

        val reasons = mutableListOf<String>()
        var blocked = false
        var version = when {
            clean -> "clean"
            explicit -> "explicit"
            else -> "unknown"
        }
        if (clean) {
            blocked = true
            reasons.add("Пометка clean/censored/radio edit — не подходит")
        }

        val credits = editionCredits(candidateTitle)
        if (credits.isNotEmpty()) {
            blocked = true
            reasons.add("Запрещённая версия: " + credits.sorted().joinToString(", "))
        }

        if (PLAYLIST.containsMatchIn(candidateTitle)) {
            blocked = true
            reasons.add("Плейлист, полный альбом или сборник вместо отдельной записи")
        }

        val sourceReasons = sourceCreditReasons(rawTarget)
        reasons.addAll(sourceReasons)
        val unresolved = sourceReasons.isNotEmpty()

        val extras = extraWords(target, candidateTitle, allowed)
        if (extras.isNotEmpty())
            reasons.add("Лишние слова в названии: " + extras.sorted().distinct().joinToString(", "))
        if (softClean)
            reasons.add("Слово clean может быть частью названия или пометкой версии: нужна проверка")

        if (artistScore < 0.8) {
            blocked = true
            reasons.add("Исполнитель совпал недостаточно уверенно")
        }
        if (titleScore < 0.9)
            reasons.add("Название совпало недостаточно уверенно")

        var badDuration = false
        if (durationSec != null) {
            when {
                durationSec <= 0 -> {
                    badDuration = true
                    reasons.add("Нулевая или отрицательная длительность: нужна проверка")
                }
                durationSec > 1200 -> {
                    blocked = true
                    reasons.add("Длительность больше 20 минут: вероятен сборник или повтор")
                }
            }
        }

        val currentIssues = issues(trackArtist, rawTarget)
        reasons.addAll(currentIssues)

        if (version == "explicit")
            reasons.add("Без цензуры — только по пометке источника, аудио не проверено")
        else if (version == "unknown")
            reasons.add("Отсутствие цензуры не подтверждено")

        val autoOk = !blocked && !unresolved && !softClean && !badDuration &&
            currentIssues.isEmpty() && extras.isEmpty() &&
            score >= 94 && artistScore >= 0.90 && titleScore >= 0.95

        return CandidateScore(
            score = score,
            blocked = blocked,
            autoOk = autoOk,
            version = version,
            reasons = reasons,
            titleScore = titleScore,
            artistScore = artistScore
        )
    }

    /**
     * Choose the best non-blocked, auto-confirmed candidate.
     * Prefers explicit over unknown (unless [allowUnverified]).
     */
    fun chooseBest(candidates: List<Pair<TrackCandidateData, CandidateScore>>, allowUnverified: Boolean = false): Pair<TrackCandidateData, CandidateScore>? {
        val eligible = candidates.filter { (_, score) ->
            !score.blocked && score.autoOk &&
            (score.version == "explicit" || (allowUnverified && score.version == "unknown"))
        }
        return eligible.maxWithOrNull(
            compareByDescending<Pair<TrackCandidateData, CandidateScore>> { it.second.version == "explicit" }
                .thenByDescending { it.second.score }
        )
    }
}

/** Evaluation result for a single candidate. */
data class CandidateScore(
    val score: Int,
    val blocked: Boolean,
    val autoOk: Boolean,
    val version: String,           // "explicit" / "clean" / "unknown"
    val reasons: List<String>,
    val titleScore: Double,
    val artistScore: Double
)

/** Lightweight carrier for candidate data passed to [Matching.evaluateCandidate]. */
data class TrackCandidateData(
    val title: String,
    val uploader: String,
    val artist: String?,
    val durationSec: Long?,
    val url: String
)
