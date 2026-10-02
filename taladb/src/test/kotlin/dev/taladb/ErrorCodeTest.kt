package dev.taladb

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Engine failures carry the engine's stable code; this package's own do not. */
class ErrorCodeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun badFilterIsInvalidFilter() = runBlocking {
        TalaDB.open(tmp.root.resolve("a.db")).use { db ->
            val error = runCatching {
                db.collection("notes").find(buildJsonObject { putJsonObject("n") { put("\$frobnicate", 1) } })
            }.exceptionOrNull()
            assertTrue("expected TalaDBException, got $error", error is TalaDBException)
            assertEquals(TalaDBException.INVALID_FILTER, (error as TalaDBException).code)
        }
    }

    @Test
    fun nonUlidIdIsInvalidDocumentIdAndDeriveDocIdFixesIt() = runBlocking {
        TalaDB.open(tmp.root.resolve("b.db")).use { db ->
            val meta = db.collection("meta")
            val error = runCatching { meta.insert(buildJsonObject { put("_id", "settings") }) }.exceptionOrNull()
            assertTrue("expected TalaDBException, got $error", error is TalaDBException)
            assertEquals(TalaDBException.INVALID_DOCUMENT_ID, (error as TalaDBException).code)

            // What the message tells the caller to do, works.
            val id = deriveDocId("meta", "settings")
            assertEquals(id, meta.insert(buildJsonObject { put("_id", id) }))
            assertEquals(1L, meta.count(buildJsonObject { put("_id", id) }))
        }
    }

    @Test
    fun aFailureThatIsNotTheEnginesHasNoCode() {
        assertNull(TalaDBException("from the wrapper").code)
    }
}
