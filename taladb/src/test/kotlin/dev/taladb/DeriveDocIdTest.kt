package dev.taladb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeriveDocIdTest {
    /**
     * The engine's `derive_doc_id_cross_language_vectors`, verbatim. If these
     * drift, two clients give the same key different ids and data forks.
     */
    @Test
    fun matchesTheEnginesCrossLanguageVectors() {
        listOf(
            Triple("products", "sku-123", "56GC678DQYWW1Z98HPYJ90WVKH"),
            Triple("products", "1", "7Z6Y6H8NG96ZGN4PJVDP18CSY2"),
            Triple("orders", "1", "5VYD63GDV5KCDXV2A0SYRWSKVZ"),
            Triple("", "", "6J535PJ40THJQQH49BE174M53Z"),
            // Multi-byte UTF-8: the hash runs over bytes, not UTF-16 units.
            Triple("products", "sku-ñ-💡", "5NZ0PGNM2CF0BTHN0AAX8CWPAA"),
        ).forEach { (collection, key, expected) ->
            assertEquals("deriveDocId($collection, $key)", expected, deriveDocId(collection, key))
        }
    }

    @Test
    fun theSeparatorKeepsKeysApart() {
        assertNotEquals(deriveDocId("ab", "c"), deriveDocId("a", "bc"))
    }
}
