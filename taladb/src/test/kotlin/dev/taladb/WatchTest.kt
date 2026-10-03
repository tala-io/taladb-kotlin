package dev.taladb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.util.concurrent.Executors

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

    /**
     * An app collects live queries on its main thread, and a screen that goes
     * away cancels several at once while the next screen's are running.
     * Unsubscribing waits for the database lock, which each running query
     * takes for up to a poll; done on the collector's thread, that froze an
     * Android app's UI for over a second when it left a screen with five.
     */
    @Test
    fun aLiveQueryNeverHoldsTheCollectorsThread() =
        runBlocking {
            val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()
            try {
                val notes = db.collection<Note>("notes")
                notes.insert(Note(title = "a"))
                val app = CoroutineScope(main)
                val emitted = Channel<Unit>(Channel.UNLIMITED)
                // Navigating: the next screen's queries are already running
                // when the previous screen's are cancelled.
                val queries = List(5) { app.launch { notes.watch().collect { emitted.send(Unit) } } }
                val nextScreen = List(3) { app.launch { notes.watch().collect { emitted.send(Unit) } } }
                withTimeout(10_000) { repeat(8) { emitted.receive() } }
                delay(300) // every query is now inside a native wait, holding the read lock

                withContext(main) { queries.forEach { it.cancel() } }
                // Hop onto the "main" thread repeatedly while the five
                // unsubscribe; no hop should wait behind one.
                var worstMs = 0L
                repeat(60) {
                    val start = System.nanoTime()
                    withContext(main) {}
                    worstMs = maxOf(worstMs, (System.nanoTime() - start) / 1_000_000)
                    delay(10)
                }
                assertTrue("the collector's thread was held for $worstMs ms", worstMs < 100)
                withTimeout(10_000) { queries.joinAll() }
                nextScreen.forEach { it.cancel() }
                withTimeout(10_000) { nextScreen.joinAll() }
            } finally {
                main.close()
            }
        }

    /**
     * A screen's live queries start while others are running. Each running
     * one waits on the engine in 250 ms polls; when a poll held the database
     * lock, a new live query's first result took 350 ms or more.
     */
    @Test
    fun aNewLiveQueryDoesNotWaitBehindRunningOnes() =
        runBlocking {
            val notes = db.collection<Note>("notes")
            notes.insert(Note(title = "a"))
            val emitted = Channel<Unit>(Channel.UNLIMITED)
            val running = List(5) { launch(Dispatchers.Default) { notes.watch().collect { emitted.send(Unit) } } }
            withTimeout(10_000) { repeat(5) { emitted.receive() } }
            delay(300) // every running query is now inside a native wait

            var worstMs = 0L
            repeat(5) {
                val start = System.nanoTime()
                withTimeout(10_000) { notes.watch().first() }
                worstMs = maxOf(worstMs, (System.nanoTime() - start) / 1_000_000)
                delay(73) // land at different points in the running polls
            }
            assertTrue("a new live query took $worstMs ms to deliver its first result", worstMs < 150)
            running.forEach { it.cancel() }
            withTimeout(10_000) { running.joinAll() }
        }

    private suspend fun kotlinx.coroutines.Job.cancelAndJoinWithin() {
        cancel()
        join()
    }
}
