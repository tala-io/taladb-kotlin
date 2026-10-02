package dev.taladb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UnicodeErrorTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun engineErrorMessagesPreserveSupplementaryCharacters() = runBlocking {
        TalaDB.open(tmp.root.resolve("unicode.db")).use { db ->
            val error = runCatching { db.collection("notes").dropIndex("🚀") }.exceptionOrNull()
            assertTrue("expected TalaDBException, got $error", error is TalaDBException)
            assertTrue("Unicode field name was corrupted: ${error?.message}", error?.message?.contains("notes::🚀") == true)
        }
    }
}
