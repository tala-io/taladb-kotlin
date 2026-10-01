package dev.taladb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

class WatchTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: TalaDB

    @Before
    fun open() = runBlocking { db = TalaDB.open(tmp.root.resolve("watch.db")) }

    @After
    fun close() = db.close()

    @Test
    fun emitsTheCurrentStateThenEachChange() =
        runBlocking {
            val notes = db.collection<Note>("notes")
            notes.insert(Note(title = "a"))
            val seen = Channel<List<Note>>(Channel.UNLIMITED)
            val job = launch(Dispatchers.Default) { notes.watch().collect { seen.send(it) } }

            withTimeout(10_000) {
                assertEquals(listOf("a"), seen.receive().map { it.title })
                // Written through a different handle on the same database.
                db.collection<Note>("notes").insert(Note(title = "b"))
                assertEquals(setOf("a", "b"), seen.receive().map { it.title }.toSet())
            }
            job.cancel()
        }

    @Test
    fun appliesTheFilterAndSkipsWritesThatChangeNothing() =
        runBlocking {
            val notes = db.collection<Note>("notes")
            val seen = Channel<List<Note>>(Channel.UNLIMITED)
            val job =
                launch(Dispatchers.Default) {
                    notes.watch(buildJsonObject { put("stars", 5) }).collect { seen.send(it) }
                }

            withTimeout(10_000) {
                assertEquals(emptyList<Note>(), seen.receive())
                notes.insert(Note(title = "meh", stars = 1))
                assertNull("a non-matching write must not emit", withTimeoutOrNull(800) { seen.receive() })
                notes.insert(Note(title = "great", stars = 5))
                assertEquals(listOf("great"), seen.receive().map { it.title })
            }
            job.cancel()
        }

    /** More writes than the engine's 64-event channel, while the collector is behind. */
    @Test
    fun survivesABurstOfWritesAndEndsOnTheLatestState() =
        runBlocking {
            val notes = db.collection<Note>("notes")
            val seen = Channel<List<Note>>(Channel.UNLIMITED)
            val job = launch(Dispatchers.Default) { notes.watch().collect { seen.send(it) } }
            withTimeout(10_000) { seen.receive() }

            repeat(200) { notes.insert(Note(title = "n$it")) }

            withTimeout(10_000) {
                while (seen.receive().size != 200) Unit
            }
            job.cancel()
        }

    /**
     * close() must end live queries and release the file — a watch holds the
     * storage open, so a lingering one would make the reopen below fail.
     */
    @Test
    fun closingTheDatabaseEndsTheFlowAndReleasesTheFile() =
        runBlocking {
            val notes = db.collection<Note>("notes")
            val ended = Channel<Throwable?>(1)
            val started = Channel<Unit>(1)
            launch(Dispatchers.Default) {
                val error = runCatching { notes.watch().collect { started.trySend(Unit) } }.exceptionOrNull()
                ended.send(error)
            }
            withTimeout(10_000) { started.receive() }

            db.close()
            val error = withTimeout(10_000) { ended.receive() }
            assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)

            db = TalaDB.open(tmp.root.resolve("watch.db"))
            assertEquals(0L, db.collection<Note>("notes").count())
        }

    @Test
    fun cancellingTheCollectorClosesTheSubscription() =
        runBlocking {
            val notes = db.collection<Note>("notes")
            val seen = Channel<List<Note>>(Channel.UNLIMITED)
            val job = launch(Dispatchers.Default) { notes.watch().collect { seen.send(it) } }
            withTimeout(10_000) { seen.receive() }

            withTimeout(5_000) { job.cancelAndJoinWithin() }

            db.close()
            db = TalaDB.open(tmp.root.resolve("watch.db"))
        }

    private suspend fun kotlinx.coroutines.Job.cancelAndJoinWithin() {
        cancel()
        join()
    }
}
