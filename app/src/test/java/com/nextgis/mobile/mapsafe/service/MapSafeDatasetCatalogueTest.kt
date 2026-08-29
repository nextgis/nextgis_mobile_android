package com.nextgis.mobile.mapsafe.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.rules.TemporaryFolder
import org.junit.Test
import org.junit.Rule

class MapSafeDatasetCatalogueTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun recognisesOnlyMapSafeDecryptionImportNames() {
        assertTrue(MapSafeDatasetCatalogue.hasDecryptionImportName("survey (decrypted)"))
        assertTrue(MapSafeDatasetCatalogue.hasDecryptionImportName("survey (decrypted) 2"))
        assertFalse(MapSafeDatasetCatalogue.hasDecryptionImportName("survey"))
        assertFalse(MapSafeDatasetCatalogue.hasDecryptionImportName("survey decrypted"))
    }

    @Test
    fun layerNameCannotImpersonateVerifiedImportWithoutMarker() {
        val layerStorage = temporaryFolder.newFolder("survey (decrypted)")
        assertFalse(MapSafeDatasetCatalogue.hasVerifiedDecryptionMarker(layerStorage))
    }

    @Test
    fun classifiesOriginalMaskedAndHexbinnedDetail() {
        assertEquals(
            MapSafeDatasetCatalogue.DetailLevel.ORIGINAL,
            MapSafeDatasetCatalogue.classify("survey (decrypted)")
        )
        assertEquals(
            MapSafeDatasetCatalogue.DetailLevel.MASKED,
            MapSafeDatasetCatalogue.classify("survey_masked (decrypted)")
        )
        assertEquals(
            MapSafeDatasetCatalogue.DetailLevel.MASKED,
            MapSafeDatasetCatalogue.classify(
                "renamed (decrypted)",
                listOf("mapsafe_distance_m")
            )
        )
        assertEquals(
            MapSafeDatasetCatalogue.DetailLevel.HEXBINNED,
            MapSafeDatasetCatalogue.classify("survey_hexbin (decrypted)")
        )
        assertEquals(
            MapSafeDatasetCatalogue.DetailLevel.HEXBINNED,
            MapSafeDatasetCatalogue.classify("renamed (decrypted)", listOf("cell_id", "count"))
        )
    }

    @Test
    fun removesOnlyTheMapSafeImportSuffixFromDisplayName() {
        assertEquals(
            "survey_masked",
            MapSafeDatasetCatalogue.displayName("survey_masked (decrypted) 3")
        )
    }
}
