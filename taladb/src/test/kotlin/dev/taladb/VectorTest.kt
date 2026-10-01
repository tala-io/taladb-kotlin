package dev.taladb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.cos
import kotlin.math.sin

class VectorTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: TalaDB
    private lateinit var docs: TalaCollection<Doc>

    /** 60 unit vectors spread around a circle, alternating kind "a" / "b". */
    @Before
    fun seed(): Unit =
        runBlocking {
            db = TalaDB.open(tmp.root.resolve("vectors.db"))
            docs = db.collection<Doc>("docs")
            docs.createVectorIndex("embedding", dimensions = 2)
            docs.insertMany(
                (0 until 60).map { i ->
                    val angle = i * Math.PI / 30
                    Doc(title = "d$i", kind = if (i % 2 == 0) "a" else "b", embedding = listOf(cos(angle).toFloat(), sin(angle).toFloat()))
                },
            )
        }

    @After
    fun close() = db.close()

    @Test
    fun exactSearchReportsHowItRanAndPaginates() =
        runTest {
            val query = floatArrayOf(1f, 0f)
            val first = docs.searchVectors("embedding", query, topK = 3, options = VectorQueryOptions(mode = VectorSearchMode.Exact))
            assertEquals("exact", first.execution.path)
            assertEquals("requestedExact", first.execution.reason)
            assertEquals("d0", first.hits.first().document.title)

            val offset = first.nextOffset ?: error("a full page must offer a next offset")
            val second =
                docs.searchVectors("embedding", query, topK = 3, options = VectorQueryOptions(mode = VectorSearchMode.Exact, offset = offset))
            assertTrue(first.hits.map { it.document.title }.intersect(second.hits.map { it.document.title }.toSet()).isEmpty())
        }

    @Test
    fun findWithinReturnsEverythingAboveTheThreshold() =
        runTest {
            // Vectors are 6 degrees apart; 0.96 sits between cos 12° (0.978) and
            // cos 18° (0.951), so exactly two neighbours either side qualify.
            val near = docs.findWithin("embedding", floatArrayOf(1f, 0f), scoreThreshold = 0.96f)
            assertEquals(setOf("d0", "d1", "d2", "d58", "d59"), near.hits.map { it.document.title }.toSet())
            assertNull(near.nextOffset)
        }

    @Test
    fun annOnAFlatIndexIsAnEngineError() =
        runTest {
            assertEquals(VectorIndexState.Flat, docs.vectorIndexStatus("embedding").state)
            val error =
                runCatching {
                    docs.searchVectors("embedding", floatArrayOf(1f, 0f), topK = 1, options = VectorQueryOptions(mode = VectorSearchMode.Ann))
                }.exceptionOrNull()
            assertTrue("expected TalaDBException, got $error", error is TalaDBException)
        }

    @Test
    fun batchedRebuildReportsProgressAndEnablesTheGraph() =
        runTest {
            val progress = mutableListOf<VectorBuildProgress>()
            val done = docs.rebuildVectorIndex("embedding", HnswOptions(m = 8, efConstruction = 32), batchSize = 16) { progress += it }

            assertEquals(VectorBuildState.Ready, done.state)
            assertEquals(60L, done.processed)
            assertTrue("several steps were reported: $progress", progress.size >= 4)

            val status = docs.vectorIndexStatus("embedding")
            assertEquals(VectorIndexState.Ready, status.state)
            assertEquals(HnswOptions(m = 8, efConstruction = 32), status.options)

            val hit = docs.searchVectors("embedding", floatArrayOf(1f, 0f), topK = 1)
            assertEquals("hnsw", hit.execution.path)
            assertEquals("d0", hit.hits.single().document.title)

            // A filter keeps Auto on exact search.
            val filtered = docs.searchVectors("embedding", floatArrayOf(1f, 0f), topK = 1, filter = buildJsonObject { put("kind", "b") })
            assertEquals("exact", filtered.execution.path)

            val recall = docs.measureVectorRecall("embedding", listOf(floatArrayOf(0.3f, 0.9f), floatArrayOf(-1f, 0.1f)), topK = 5)
            assertTrue("recall ${recall.recallAtK}", recall.recallAtK in 0.0..1.0)
            assertEquals(2, recall.queries)
        }

    @Test
    fun cancellingARebuildCancelsTheBuild() =
        runTest {
            val error =
                runCatching {
                    docs.rebuildVectorIndex("embedding", HnswOptions(m = 8, efConstruction = 32), batchSize = 4) { p ->
                        if (p.processed >= 8) throw CancellationException("user cancelled")
                    }
                }.exceptionOrNull()
            assertTrue("expected CancellationException, got $error", error is CancellationException)

            val status = docs.vectorIndexStatus("embedding")
            assertTrue("no build left running: $status", status.build?.state != VectorBuildState.Building)
        }
}
