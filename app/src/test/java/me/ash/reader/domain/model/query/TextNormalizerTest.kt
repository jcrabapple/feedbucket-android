package me.ash.reader.domain.model.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * S12a — canonical normalization pins (codepoints verified against Python
 * `str.lower()` output recorded 2026-11-16):
 * `env -u PYTHONPATH /home/jason/projects/feedbucket/.venv/bin/python -c`
 * over the same fixture strings. Python `lower()` performs the same simple
 * case mapping as Kotlin `lowercase(Locale.ROOT)` for these fixtures;
 * `casefold` would fold ß->ss (the pinned deliberate deviation).
 */
class TextNormalizerTest {

    @Test
    fun nullAndEmpty() {
        assertEquals("", TextNormalizer.normalize(null))
        assertEquals("", TextNormalizer.normalize(""))
    }

    @Test
    fun asciiLowercase() {
        assertEquals("feedbucket", TextNormalizer.normalize("FeedBucket"))
        assertEquals("feedbucket", TextNormalizer.normalize("FEEDBUCKET"))
    }

    @Test
    fun turkishDottedAndDotlessI() {
        // İ (U+0130) -> i (U+0069) + combining dot above (U+0307)
        assertEquals("i\u0307", TextNormalizer.normalize("İ"))
        assertNotEquals("i", TextNormalizer.normalize("İ"))
        // ı (U+0131 dotless) is its own character, unchanged
        assertEquals("ı", TextNormalizer.normalize("ı"))
        // term "i" is a SUBSTRING of normalized "İ" (i + combining dot)...
        assertEquals(true, TextNormalizer.normalize("İ").contains("i"))
        // ...but does NOT match dotless ı
        assertEquals(false, TextNormalizer.normalize("ı").contains("i"))
    }

    @Test
    fun greekSigma() {
        // ΣΊΣΥΦΟΣ -> σίσυφος (final ς stays ς under simple lowercase)
        assertEquals("σίσυφος", TextNormalizer.normalize("ΣΊΣΥΦΟΣ"))
        // σ matches Σ, but final-form ς is a distinct char
        assertEquals("σ", TextNormalizer.normalize("Σ"))
        assertEquals("ς", TextNormalizer.normalize("ς"))
        assertEquals(true, TextNormalizer.normalize("ΣΊΣΥΦΟΣ").contains("σίσυφο"))
        assertEquals(false, TextNormalizer.normalize("ΣΊΣΥΦΟΣ").contains("σίσυφοσ"))
    }

    @Test
    fun cyrillic() {
        assertEquals("привет", TextNormalizer.normalize("ПРИВЕТ"))
    }

    @Test
    fun germanSharpSIsNotFullFolded() {
        // Pinned limitation (S12a): simple lowercase, NOT casefold.
        assertEquals("straße", TextNormalizer.normalize("straße"))
        assertEquals("strasse", TextNormalizer.normalize("STRASSE"))
        assertNotEquals(TextNormalizer.normalize("straße"), TextNormalizer.normalize("STRASSE"))
        // "strasse" does NOT match "straße" (casefold would fold ß->ss)
        assertEquals(false, TextNormalizer.normalize("straße").contains("strasse"))
        // but direct ß terms match
        assertEquals(true, TextNormalizer.normalize("STRASSE 10").contains("strasse"))
        assertEquals(true, TextNormalizer.normalize("In der Straße").contains("straße"))
    }
}
