package com.phonoteka.data

import java.text.Normalizer
import java.util.Locale

/** Exact, conservative matching. Never silently substitute live/remix/cover versions. */
object LocalAudioMatcher {
    fun parseArtistTitle(raw: String): Pair<String, String> {
        val separator = Regex("\\s+[—–-]\\s+").find(raw.trim()) ?: return "" to raw.trim()
        val value = raw.trim()
        val artist = value.substring(0, separator.range.first).trim()
        val title = value.substring(separator.range.last + 1).trim()
        return if (artist.isNotBlank() && title.isNotBlank()) artist to title else "" to value
    }

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("\\s+"), " ").trim()

    class Index(tracks: List<Track>) {
        private val byName = tracks.groupBy { key(it.artist, it.title) }

        fun match(artist: String?, title: String?, displayName: String): Track? {
            val (fileArtist, fileTitle) = parseArtistTitle(displayName.substringBeforeLast('.', displayName))
            val tagged = !artist.isNullOrBlank() && !title.isNullOrBlank()
            val tagMatches = if (tagged) byName[key(artist!!, title!!)].orEmpty() else emptyList()
            val fileMatches = if (fileArtist.isNotBlank()) byName[key(fileArtist, fileTitle)].orEmpty() else emptyList()
            if (tagged) {
                // Full tags are authoritative; contradictions are left for explicit selection.
                val track = tagMatches.singleOrNull() ?: return null
                if (fileMatches.isNotEmpty() && fileMatches.none { it.id == track.id }) return null
                return track
            }
            return fileMatches.singleOrNull()
        }

        private fun key(artist: String, title: String) = normalize(artist) to normalize(title)
    }
}
