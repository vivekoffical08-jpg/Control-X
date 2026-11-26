package com.controlx.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalCatalogMatcherTest {
    @Test
    fun matchesCaseAndPunctuationInsensitively() {
        val result = LocalCatalogMatcher.uniqueBestMatch(
            query = "you tube",
            candidates = listOf("YouTube", "YouTube Music"),
            label = { it },
        )

        assertEquals("YouTube", result)
    }

    @Test
    fun matchesMinorSpellingErrors() {
        val result = LocalCatalogMatcher.uniqueBestMatch(
            query = "spotfy",
            candidates = listOf("Spotify", "Settings"),
            label = { it },
        )

        assertEquals("Spotify", result)
    }

    @Test
    fun declinesAmbiguousContactOrAppNames() {
        val result = LocalCatalogMatcher.uniqueBestMatch(
            query = "alex",
            candidates = listOf("Alex Smith", "Alex Jones"),
            label = { it },
        )

        assertNull(result)
    }

    @Test
    fun rejectsUnrelatedQueries() {
        val result = LocalCatalogMatcher.uniqueBestMatch(
            query = "weather",
            candidates = listOf("Phone", "Camera"),
            label = { it },
        )

        assertNull(result)
    }
}
