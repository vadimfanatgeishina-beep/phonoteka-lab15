package com.phonoteka.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for [LocalAudioMatcher]. The matcher is deliberately conservative:
 * it must never silently substitute a different (live/remix/cover) recording.
 */
class LocalAudioMatcherTest {

    private fun track(id: Long, artist: String, title: String) =
        Track(id = id, artist = artist, title = title)

    @Test
    fun parseArtistTitle_splitsOnSpacedSeparators_only() {
        assertEquals("Artist" to "Song", LocalAudioMatcher.parseArtistTitle("Artist — Song"))
        assertEquals("Artist" to "Song", LocalAudioMatcher.parseArtistTitle("Artist – Song"))
        assertEquals("Artist" to "Song", LocalAudioMatcher.parseArtistTitle("Artist - Song"))
        // No whitespace around the hyphen => not a separator, the whole string stays the title.
        assertEquals("" to "Artist-Song", LocalAudioMatcher.parseArtistTitle("Artist-Song"))
        assertEquals("" to "NoSeparator", LocalAudioMatcher.parseArtistTitle("NoSeparator"))
        assertEquals("" to "", LocalAudioMatcher.parseArtistTitle("   "))
    }

    @Test
    fun parseArtistTitle_preservesArtistHyphens_andSplitsOnFirstSeparator() {
        // "Jay-Z" keeps its internal hyphen because only " - " counts as a separator.
        assertEquals("Jay-Z" to "99 Problems", LocalAudioMatcher.parseArtistTitle("Jay-Z - 99 Problems"))
        // Only the first separator splits; later hyphens stay inside the title.
        assertEquals("Artist" to "Song - Live", LocalAudioMatcher.parseArtistTitle("Artist - Song - Live"))
        assertEquals("AC/DC" to "Back in Black", LocalAudioMatcher.parseArtistTitle("  AC/DC — Back in Black  "))
    }

    @Test
    fun normalize_foldsCaseYoAndWhitespace() {
        assertEquals("елка", LocalAudioMatcher.normalize("ЁЛКА"))
        assertEquals("елка", LocalAudioMatcher.normalize("ёлка"))
        assertEquals("елка прованс", LocalAudioMatcher.normalize("  Ёлка   Прованс "))
        assertEquals("abc", LocalAudioMatcher.normalize("ABC"))
        // NFKC folds full-width latin before lowercasing.
        assertEquals("abc", LocalAudioMatcher.normalize("ＡＢＣ"))
    }

    @Test
    fun index_matchesOnExactNormalizedTags() {
        val index = LocalAudioMatcher.Index(listOf(track(1, "Artist", "Song")))

        val match = index.match("Artist", "Song", "some file.mp3")

        assertEquals(1L, match?.id)
    }

    @Test
    fun index_fallsBackToFilename_whenTagsAreAbsent_andStripsExtension() {
        val index = LocalAudioMatcher.Index(listOf(track(7, "Artist", "Song")))

        assertEquals(7L, index.match(null, null, "Artist - Song.mp3")?.id)
        assertEquals(7L, index.match("", "", "Artist — Song.flac")?.id)
        // No extension at all still parses.
        assertEquals(7L, index.match(null, null, "Artist - Song")?.id)
    }

    @Test
    fun index_matchesCyrillicCaseAndYoInsensitively() {
        val index = LocalAudioMatcher.Index(listOf(track(3, "Ёлка", "Прованс")))

        // Filename fallback: "елка"/"прованс" must match catalogue "Ёлка"/"Прованс".
        assertEquals(3L, index.match(null, null, "елка - прованс.mp3")?.id)
        // Tagged match with different case / ё spelling.
        assertEquals(3L, index.match("ёлка", "прованс", "елка - прованс.mp3")?.id)
    }

    @Test
    fun index_skipsAmbiguousDuplicateCatalogueEntries() {
        val index = LocalAudioMatcher.Index(listOf(track(1, "Artist", "Song"), track(2, "Artist", "Song")))

        // Tagged lookup is ambiguous.
        assertNull(index.match("Artist", "Song", "whatever.mp3"))
        // Filename fallback is equally ambiguous.
        assertNull(index.match(null, null, "Artist - Song.mp3"))
    }

    @Test
    fun index_skipsWhenFilenameContradictsFullTags() {
        val index = LocalAudioMatcher.Index(listOf(track(1, "Artist", "Song"), track(2, "Other", "Different")))

        // Tags point at track 2 but the filename clearly points at track 1 => contradiction.
        assertNull(index.match("Other", "Different", "Artist - Song.mp3"))
    }

    @Test
    fun index_doesNotReplaceRemixOrLiveWithOriginal() {
        val index = LocalAudioMatcher.Index(listOf(track(1, "Artist", "Song")))

        // A remix/live file must not be bound to the studio original.
        assertNull(index.match(null, null, "Artist - Song (Remix).mp3"))
        assertNull(index.match(null, null, "Artist - Song (Live).mp3"))
        // The plain original still matches.
        assertEquals(1L, index.match(null, null, "Artist - Song.mp3")?.id)
    }

    @Test
    fun index_returnsNullWhenNothingMatches() {
        assertNull(LocalAudioMatcher.Index(emptyList()).match(null, null, "Artist - Song.mp3"))
        assertNull(LocalAudioMatcher.Index(listOf(track(1, "Artist", "Song"))).match(null, null, "Nobody - Nothing.mp3"))
        // Tags present but absent from the catalogue => skipped, not silently downgraded.
        assertNull(LocalAudioMatcher.Index(listOf(track(1, "Artist", "Song"))).match("Ghost", "Track", "Artist - Song.mp3"))
    }
}
