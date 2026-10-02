package dev.taladb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CancellationTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun cancellationBeforeOpenReturnsReleasesTheFile() = runBlocking {
        CompletionDispatcher().use { dispatcher ->
            val file = tmp.root.resolve("open.db")
            cancelAfterNativeCall(dispatcher) { TalaDB.open(file, dispatcher = dispatcher).close() }
            TalaDB.open(file).use { assertEquals(0L, it.userVersion()) }
        }
    }

    @Test
    fun cancellationBeforeWatchReturnsClosesTheSubscription() = runBlocking {
        CompletionDispatcher().use { dispatcher ->
            TalaDB.open(tmp.root.resolve("watch.db"), dispatcher = dispatcher).use { db ->
                cancelAfterNativeCall(dispatcher) { db.collection("notes").watch().collect {} }
                val watches = TalaDB::class.java.getDeclaredField("watches").apply { isAccessible = true }
                assertTrue("no cancelled subscription may remain registered", (watches.get(db) as Set<*>).isEmpty())
            }
        }
    }

    @Test
    fun cancellationBeforeRebuildStartsDoesNotLeaveAPersistentBuild() = runBlocking {
        CompletionDispatcher().use { dispatcher ->
            val file = tmp.root.resolve("build.db")
            TalaDB.open(file, dispatcher = dispatcher).use { db ->
                val vectors = db.collection("vectors")
                vectors.createVectorIndex("v", 2)
                cancelAfterNativeCall(dispatcher) { vectors.rebuildVectorIndex("v") }
            }
            TalaDB.open(file).use { db ->
                val vectors = db.collection("vectors")
                assertEquals(VectorBuildState.Cancelled, vectors.vectorIndexStatus("v").build?.state)
                assertEquals(VectorBuildState.Ready, vectors.rebuildVectorIndex("v").state)
            }
        }
    }

    /** Block the caller until the native call finishes, then cancel before its return is delivered. */
    private suspend fun CoroutineScope.cancelAfterNativeCall(
        dispatcher: CompletionDispatcher,
        operation: suspend () -> Unit,
    ) {
        val completed = CountDownLatch(1)
        dispatcher.completed = completed
        val job = launch(start = CoroutineStart.UNDISPATCHED) { operation() }
        try {
            assertTrue("native operation did not finish", completed.await(10, TimeUnit.SECONDS))
        } finally {
            job.cancelAndJoin()
            dispatcher.completed = null
        }
    }

    private class CompletionDispatcher : CoroutineDispatcher(), AutoCloseable {
        private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        @Volatile var completed: CountDownLatch? = null

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            worker.dispatch(context) {
                block.run()
                completed?.countDown()
            }
        }

        override fun close() = worker.close()
    }
}
