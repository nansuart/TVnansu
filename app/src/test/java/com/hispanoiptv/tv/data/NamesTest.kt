package com.hispanoiptv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Title cleaning and platform naming used to match the provider with TVmaze and Wikidata. */
class NamesTest {

    @Test
    fun cleansProviderTitles() {
        assertEquals("The Bear", PlatformClassifier.searchName("ES - The Bear (2022) [4K]"))
        assertEquals("La casa de papel", PlatformClassifier.searchName("|ES| La casa de papel"))
        assertEquals("viaje de chihiro", Collections.norm("ES - El Viaje de Chihiro (2001)"))
        assertEquals("viaje de chihiro", Collections.norm("El viaje de Chihiro"))
        assertEquals("amelie", Collections.norm("Amélie"))
        assertEquals("año de la peste", Collections.norm("El Año de la Peste"))
        assertEquals("dark knight", Collections.norm("The Dark Knight [HD]"))
    }

    @Test
    fun namesPlatforms() {
        assertEquals("HBO Max", PlatformClassifier.normalize("HBO"))
        assertEquals("HBO Max", PlatformClassifier.normalize("Max"))
        assertEquals("Apple TV+", PlatformClassifier.normalize("Apple TV"))
        assertEquals("Netflix", PlatformClassifier.fromText("SERIES NETFLIX"))
        assertEquals("Prime Video", PlatformClassifier.fromText("Amazon Prime Series"))
        assertEquals("Hulu", PlatformClassifier.normalize("Hulu"))
        assertEquals(null, PlatformClassifier.fromText("SERIES DRAMA"))
    }

    @Test
    fun scoresLiveSport() {
        assertTrue(Repository.sportScore("LaLiga EA Sports: Real Madrid - Barcelona (Directo)") >= 2)
        assertTrue(Repository.sportScore("NBA: Lakers vs Celtics") >= 2)
        assertTrue(Repository.sportScore("Resumen LaLiga") < 2)
        assertEquals(0, Repository.sportScore("Telediario 2"))
    }
}
