package dev.taladb

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Projection on find and watch, and stopwords in ranked search — both added
 * because an app on this package needed them: notes carrying embeddings made
 * every live update heavy, and "kind to a classmate" matched every note with
 * "to" in it.
 */
class ProjectionAndStopwordsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Serializable
    data class Note(
        @SerialName("_id") val id: String? = null,
        val text: String,
        val tags: List<String> = emptyList(),
        val embedding: List<Float>? = null,
    )

    private lateinit var db: TalaDB
    private lateinit var notes: TalaCollection<Note>

    @Before
    fun setUp() = runBlocking {
        db = TalaDB.open(tmp.root.resolve("p.db"))
        notes = db.collection<Note>("notes")
    }

    @After
    fun tearDown() = db.close()

    private fun vector(x: Float) = List(384) { x }

    @Test
    fun findCanExcludeALargeField() = runBlocking {
        notes.insert(Note(text = "Read aloud", tags = listOf("Literacy"), embedding = vector(0.1f)))
        val note = notes.find(projection = Projection.exclude("embedding")).single()
        assertNull(note.embedding)
        assertEquals("Read aloud", note.text)
        assertEquals(listOf("Literacy"), note.tags)
        assertNotNull("_id is always returned", note.id)
        assertNotNull("without a projection the field is still there", notes.find().single().embedding)
    }

    @Test
    fun findCanIncludeOnlySomeFields() = runBlocking {
        notes.insert(Note(text = "Read aloud", tags = listOf("Literacy"), embedding = vector(0.1f)))
        val raw = db.collection("notes").find(projection = Projection.include("text")).single()
        assertEquals(setOf("_id", "text"), raw.keys)
    }

    @Test
    fun watchExcludesTheFieldFromEveryEmission() = runBlocking {
        notes.insert(Note(text = "First", embedding = vector(0.1f)))
        val second = async {
            withTimeout(5_000) {
                notes.watch(projection = Projection.exclude("embedding")).first { batch ->
                    assertTrue(batch.all { it.embedding == null })
                    batch.size == 2
                }
            }
        }
        delay(300)
        notes.insert(Note(text = "Second", embedding = vector(0.2f)))
        assertEquals(setOf("First", "Second"), second.await().map { it.text }.toSet())
    }

    @Test
    fun anEmptyExcludeListChangesNothing() = runBlocking {
        notes.insert(Note(text = "x"))
        val error = runCatching { notes.find(projection = Projection(exclude = listOf())) }.exceptionOrNull()
        assertNull(error)
    }

    @Test
    fun stopwordsDoNotMatchEveryNoteByDefault() = runBlocking {
        notes.createFtsIndex("text")
        notes.insertMany(
            listOf(
                Note(text = "Invited a new classmate to join her group"),
                Note(text = "Counted by fives to 100"),
                Note(text = "Reminded everyone to put goggles on"),
            ),
        )
        val hits = notes.searchText("text", "kind to a classmate", topK = 10)
        assertEquals(listOf("Invited a new classmate to join her group"), hits.map { it.document.text })

        val all = notes.searchText("text", "kind to a classmate", topK = 10, options = Bm25Options(stopwords = false))
        assertEquals(3, all.size)
        assertEquals("Invited a new classmate to join her group", all.first().document.text)
    }

    @Test
    fun hybridSearchFiltersStopwordsOnItsTextSideAndCanBeToldNotTo() = runBlocking {
        notes.createFtsIndex("text")
        notes.createVectorIndex("embedding", dimensions = 2)
        notes.insertMany(
            listOf(
                Note(text = "Invited a new classmate to join", embedding = listOf(1f, 0f)),
                Note(text = "Counted by fives to 100", embedding = listOf(0f, 1f)),
            ),
        )
        val query = floatArrayOf(0f, 1f)
        val filtered = notes.hybridSearch("text", "to a classmate", "embedding", query, topK = 2)
        assertNull(filtered.single { it.document.text.startsWith("Counted") }.textRank)

        val unfiltered = notes.hybridSearch(
            "text", "to a classmate", "embedding", query, topK = 2,
            options = HybridOptions(bm25 = Bm25Options(stopwords = false)),
        )
        assertNotNull(unfiltered.single { it.document.text.startsWith("Counted") }.textRank)
    }
}
