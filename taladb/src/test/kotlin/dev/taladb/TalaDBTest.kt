package dev.taladb

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@Serializable
data class Note(
    @SerialName("_id") val id: String? = null,
    val title: String,
    val body: String = "",
    val stars: Int = 0,
)

@Serializable
data class Doc(
    @SerialName("_id") val id: String? = null,
    val title: String,
    val text: String = "",
    val kind: String = "",
    val embedding: List<Float> = emptyList(),
)

private fun filter(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(build)

class TalaDBTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var file: File
    private lateinit var db: TalaDB

    @Before
    fun open() =
        runBlocking {
            file = tmp.root.resolve("test.db")
            db = TalaDB.open(file)
        }

    @After
    fun close() = db.close()

    @Test
    fun engineLibraryMatchesTheHeaderTheShimWasBuiltAgainst() {
        assertEquals(2, TalaDB.abiVersion)
    }

    @Test
    fun typedDocumentsRoundTripWithTheirAssignedId() =
        runTest {
            val notes = db.collection<Note>("notes")
            val id = notes.insert(Note(title = "first", stars = 3))

            val found = notes.findOne(filter { put("title", "first") })
            assertEquals(Note(id = id, title = "first", stars = 3), found)
            assertNull(notes.findOne(filter { put("title", "missing") }))
        }

    /**
     * JNI's own string conversions use modified UTF-8, which encodes every
     * character outside the BMP as a surrogate pair the engine rejects. The
     * shim passes standard UTF-8 byte arrays instead; this pins that.
     */
    @Test
    fun textOutsideTheBasicMultilingualPlaneSurvivesTheRoundTrip() =
        runTest {
            val notes = db.collection<Note>("notes 📓")
            val title = "café 日本語 🎉👩🏽‍💻 𝔘𝔫𝔦𝔠𝔬𝔡𝔢"
            notes.insert(Note(title = title, body = "nul\u0000inside"))

            val found = notes.findOne(filter { put("title", title) })
            assertEquals(title, found?.title)
            assertEquals("nul\u0000inside", found?.body)
            assertTrue("notes 📓" in db.collectionNames())
        }

    /**
     * Strings that cross as raw C strings would be cut at the first NUL, so
     * they are refused instead. Strings inside JSON arguments are unaffected —
     * the encoder escapes NUL — which the round-trip test above covers.
     */
    @Test
    fun nulInACStringArgumentIsRejectedRatherThanTruncated() {
        assertThrows(IllegalArgumentException::class.java) { db.collection("a\u0000b") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { db.collection("notes").findNearest("emb\u0000edding", floatArrayOf(1f), topK = 1) }
        }
    }

    @Test
    fun filtersUpdatesAndDeletesReportWhatTheyTouched() =
        runTest {
            val notes = db.collection<Note>("notes")
            notes.insertMany((1..5).map { Note(title = "n$it", stars = it) })

            assertEquals(5L, notes.count())
            assertEquals(3L, notes.count(filter { putJsonObject("stars") { put("\$gte", 3) } }))

            assertTrue(notes.updateOne(filter { put("title", "n1") }, filter { putJsonObject("\$set") { put("body", "edited") } }))
            assertFalse(notes.updateOne(filter { put("title", "nope") }, filter { putJsonObject("\$set") { put("body", "x") } }))
            assertEquals("edited", notes.findOne(filter { put("title", "n1") })?.body)

            val bumped = notes.updateMany(filter { putJsonObject("stars") { put("\$lt", 3) } }, filter { putJsonObject("\$inc") { put("stars", 10) } })
            assertEquals(2L, bumped)
            assertEquals(5L, notes.count(filter { putJsonObject("stars") { put("\$gte", 3) } }))

            assertTrue(notes.deleteOne(filter { put("title", "n5") }))
            assertFalse(notes.deleteOne(filter { put("title", "n5") }))
            assertEquals(4L, notes.deleteMany(MatchAll))
            assertEquals(0L, notes.count())
        }

    @Test
    fun insertManyWritesNothingWhenAnyDocumentIsRejected() =
        runTest {
            val notes = db.collection<Note>("notes")
            val id = notes.insert(Note(title = "existing"))

            val error =
                runCatching { notes.insertMany(listOf(Note(title = "new"), Note(id = id, title = "duplicate"))) }
                    .exceptionOrNull()
            assertTrue("expected TalaDBException, got $error", error is TalaDBException)
            assertEquals(1L, notes.count())
        }

    @Test
    fun rawJSONCollectionsAcceptAnyObject() =
        runTest {
            val raw = db.collection("raw")
            raw.insert(buildJsonObject { put("a", 1); putJsonArray("xs") { add(1); add(2) } })
            val doc = raw.findOne()
            assertNotNull(doc)
            assertEquals(1, doc!!.getValue("a").jsonPrimitive.int)
            assertTrue("_id" in doc)
        }

    @Test
    fun documentsMustSerializeToAnObject() =
        runTest {
            val strings = db.collection<String>("strings")
            val error = runCatching { strings.insert("not an object") }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }

    @Test
    fun indexErrorsFromTheEngineSurfaceAsTalaDBException() =
        runTest {
            val notes = db.collection<Note>("notes")
            notes.createIndex("title")
            notes.createIndex("title") // idempotent
            notes.createCompoundIndex(listOf("stars", "title"))
            notes.createFtsIndex("body")
            assertEquals(IndexInfo(btree = listOf("title"), fts = listOf("body"), vector = emptyList()), notes.listIndexes().copy(btree = notes.listIndexes().btree.filter { it == "title" }))

            notes.dropIndex("title")
            val error = runCatching { notes.dropIndex("title") }.exceptionOrNull()
            assertTrue("dropping a missing index must throw, got $error", error is TalaDBException)
            notes.dropCompoundIndex(listOf("stars", "title"))
            notes.dropFtsIndex("body")
        }

    @Test
    fun aggregationGroupsAndSorts() =
        runTest {
            val notes = db.collection<Note>("notes")
            notes.insertMany(listOf(Note(title = "a", stars = 1), Note(title = "a", stars = 2), Note(title = "b", stars = 5)))

            val rows =
                notes.aggregate(
                    listOf(
                        filter { putJsonObject("\$group") { put("_id", "\$title"); putJsonObject("total") { put("\$sum", "\$stars") } } },
                        filter { putJsonObject("\$sort") { put("_id", 1) } },
                    ),
                )
            assertEquals(listOf(3, 5), rows.map { it.getValue("total").jsonPrimitive.int })
        }

    @Test
    fun fullTextSearchRanksMatchingDocuments() =
        runTest {
            val docs = db.collection<Doc>("docs")
            docs.createFtsIndex("text")
            docs.insertMany(
                listOf(
                    Doc(title = "rust", text = "rust ownership and borrowing"),
                    Doc(title = "kotlin", text = "kotlin coroutines and flows"),
                    Doc(title = "both", text = "calling rust from kotlin over jni"),
                ),
            )

            val hits = docs.searchText("text", "rust", topK = 10)
            assertEquals(setOf("rust", "both"), hits.map { it.document.title }.toSet())
            assertTrue(hits.all { it.score > 0 })
        }

    @Test
    fun vectorSearchReturnsNearestFirstAndHonoursTheFilter() =
        runTest {
            val docs = db.collection<Doc>("docs")
            docs.createVectorIndex("embedding", dimensions = 3)
            docs.insertMany(
                listOf(
                    Doc(title = "x", kind = "a", embedding = listOf(1f, 0f, 0f)),
                    Doc(title = "y", kind = "b", embedding = listOf(0f, 1f, 0f)),
                    Doc(title = "xy", kind = "a", embedding = listOf(0.7f, 0.7f, 0f)),
                ),
            )

            val nearest = docs.findNearest("embedding", floatArrayOf(1f, 0.1f, 0f), topK = 2)
            assertEquals(listOf("x", "xy"), nearest.map { it.document.title })
            assertTrue(nearest[0].score >= nearest[1].score)

            val onlyB = docs.findNearest("embedding", floatArrayOf(1f, 0f, 0f), topK = 3, filter = filter { put("kind", "b") })
            assertEquals(listOf("y"), onlyB.map { it.document.title })

            assertEquals(listOf("embedding"), docs.listIndexes().vector)
        }

    @Test
    fun wrongVectorDimensionsAreAnEngineError() =
        runTest {
            val docs = db.collection<Doc>("docs")
            docs.createVectorIndex("embedding", dimensions = 3)
            val error = runCatching { docs.findNearest("embedding", floatArrayOf(1f, 0f), topK = 1) }.exceptionOrNull()
            assertTrue("expected TalaDBException, got $error", error is TalaDBException)
        }

    @Test
    fun hybridSearchFusesTextAndVectorRankings() =
        runTest {
            val docs = db.collection<Doc>("docs")
            docs.createFtsIndex("text")
            docs.createVectorIndex("embedding", dimensions = 2)
            docs.insertMany(
                listOf(
                    Doc(title = "both", text = "local first database", embedding = listOf(1f, 0f)),
                    Doc(title = "text", text = "local first sync", embedding = listOf(0f, 1f)),
                    Doc(title = "vector", text = "unrelated words", embedding = listOf(0.9f, 0.1f)),
                ),
            )

            val hits = docs.hybridSearch("text", "local database", "embedding", floatArrayOf(1f, 0f), topK = 3)
            assertEquals("both", hits.first().document.title)
            assertEquals(0, hits.first().textRank)
            assertEquals(0, hits.first().vectorRank)
        }

    @Test
    fun userVersionAndCollectionNames() =
        runTest {
            assertEquals(0L, db.userVersion())
            db.setUserVersion(7)
            assertEquals(7L, db.userVersion())

            db.collection<Note>("alpha").insert(Note(title = "a"))
            assertEquals(listOf("alpha"), db.collectionNames())
        }

    @Test
    fun anEncryptedDatabaseNeedsItsPassphrase() =
        runTest {
            val encrypted = tmp.root.resolve("secret.db")
            TalaDB.open(encrypted, TalaDBConfig(passphrase = "correct horse")).use {
                it.collection<Note>("notes").insert(Note(title = "hidden"))
            }

            val wrong = runCatching { TalaDB.open(encrypted, TalaDBConfig(passphrase = "battery staple")) }.exceptionOrNull()
            assertTrue("expected TalaDBException, got $wrong", wrong is TalaDBException)

            TalaDB.open(encrypted, TalaDBConfig(passphrase = "correct horse")).use {
                assertEquals("hidden", it.collection<Note>("notes").findOne()?.title)
            }
        }

    @Test
    fun configNeverPrintsItsPassphrase() {
        val text = TalaDBConfig(passphrase = "hunter2").toString()
        assertFalse(text, "hunter2" in text)
    }

    @Test
    fun aClosedDatabaseRejectsCallsAndCloseIsIdempotent() =
        runTest {
            db.close()
            db.close()
            assertTrue(db.isClosed)
            val error = runCatching { db.collection<Note>("notes").count() }.exceptionOrNull()
            assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        }

    @Test
    fun oneDatabaseServesManyConcurrentCoroutines() =
        runBlocking {
            val notes = db.collection<Note>("notes")
            (0 until 8)
                .map { worker ->
                    async(Dispatchers.Default) {
                        repeat(50) { notes.insert(Note(title = "w$worker-$it", stars = worker)) }
                    }
                }.awaitAll()
            assertEquals(400L, notes.count())
        }

    /**
     * Closing while operations are in flight must never free the handle under
     * a running native call: each operation either completes or fails with
     * IllegalStateException, and the process survives.
     */
    @Test
    fun closeDuringConcurrentOperationsIsSafe() =
        runBlocking {
            val notes = db.collection<Note>("notes")
            val results =
                (0 until 32).map { i ->
                    async(Dispatchers.IO) { runCatching { notes.insert(Note(title = "n$i")) } }
                }
            db.close()
            val failures = results.awaitAll().mapNotNull { it.exceptionOrNull() }
            assertTrue(failures.toString(), failures.all { it is IllegalStateException })
        }
}
