package dev.taladb

import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.serializer

/**
 * An open TalaDB database: an embedded document store with secondary,
 * full-text and vector indexes, stored in a single file.
 *
 * Open one with [TalaDB.open] and keep it for the life of the process — it is
 * safe to share across threads and coroutines. Every operation is a `suspend`
 * function that runs on the dispatcher given to [open], so it is safe to call
 * from the main thread.
 *
 * Call [close] when done. Operations already running finish first; any call
 * after that throws [IllegalStateException].
 */
public class TalaDB private constructor(
    private var handle: Long,
    internal val dispatcher: CoroutineDispatcher,
) : AutoCloseable {
    // Operations hold the read lock for the duration of their native call;
    // close() takes the write lock. Without it, close() on one thread while
    // another is mid-call frees the handle under it — a use-after-free in
    // native code, not an exception.
    private val lock = ReentrantReadWriteLock()

    // Native live-query handles, guarded by `lock`. Each keeps the database's
    // storage open, so close() closes them all rather than leaving the file
    // held until each collector next wakes.
    private val watches = HashSet<Long>()

    /** `true` once [close] has run. */
    public val isClosed: Boolean
        get() = lock.read { handle == 0L }

    /** The collection [name], with documents as raw [JsonObject]s. */
    public fun collection(name: String): TalaCollection<JsonObject> = collection(name, JsonObject.serializer())

    /**
     * The collection [name], with documents decoded as [T] by [serializer].
     * Collections are created on first write; this call does no I/O.
     */
    public fun <T> collection(
        name: String,
        serializer: KSerializer<T>,
    ): TalaCollection<T> {
        require(name.isNotEmpty()) { "collection name must not be empty" }
        require('\u0000' !in name) { "collection name must not contain U+0000" }
        return TalaCollection(this, name, serializer)
    }

    /** Names of every collection that holds data, excluding reserved `_`-prefixed ones. */
    public suspend fun collectionNames(): List<String> =
        call("listCollectionNames") { result -> result.jsonArray.map { it.jsonPrimitive.content } }

    /** The application's schema version, as last set by [setUserVersion]; 0 if never set. */
    public suspend fun userVersion(): Long = call("userVersion") { it.jsonPrimitive.long }

    /** Record the application's schema version, for running migrations once. */
    public suspend fun setUserVersion(version: Long) {
        require(version in 0..UInt.MAX_VALUE.toLong()) { "version must fit in an unsigned 32-bit integer" }
        call("setUserVersion", { listOf(JsonPrimitive(version)) }) {}
    }

    /** Force batched writes to disk. A no-op unless opened with `flushEveryWrite = false`. */
    public suspend fun flush() {
        call("flush") {}
    }

    /** Reclaim unused space in the database file. */
    public suspend fun compact() {
        call("compact") {}
    }

    /**
     * Rebuild every persistent HNSW graph. Reads and re-inserts every indexed
     * vector, so run it for maintenance or migration, not on a hot path.
     */
    public suspend fun rebuildVectorIndexes() {
        call("rebuildVectorIndexes") {}
    }

    /**
     * Close the database. Waits for running operations to finish; idempotent.
     * Blocks the calling thread for that wait, so prefer calling it off the
     * main thread if operations may be in flight.
     */
    override fun close() {
        lock.write {
            if (handle != 0L) {
                watches.forEach(Native::watchClose)
                watches.clear()
                Native.close(handle)
                handle = 0L
            }
        }
    }

    // -- Live queries (used by TalaCollection.watch) ---------------------------

    internal suspend fun watchOpen(
        collection: String,
        filter: JsonObject,
        projection: Projection? = null,
    ): Long =
        acquireResource(
            dispatcher,
            acquire = {
                val name = collection.cString()
                val filterBytes = filter.toString().cString()
                val optionBytes = projection?.toJson()?.toString()?.cString()
                lock.write {
                    check(handle != 0L) { "TalaDB database is closed" }
                    Native.watchOpen(handle, name, filterBytes, optionBytes).also { watches += it }
                }
            },
            release = ::watchClose,
        )

    /**
     * Wait up to [WATCH_POLL_MS] for a write. Returns the new snapshot, or
     * null on timeout. Holds the read lock only for that bounded wait, so
     * close() is delayed by at most one poll.
     */
    internal suspend fun watchNext(watch: Long): JsonElement? =
        withContext(dispatcher) {
            val bytes =
                lock.read {
                    check(handle != 0L && watch in watches) { "TalaDB database is closed" }
                    Native.watchNext(watch, WATCH_POLL_MS)
                }
            bytes?.let { TalaJson.parseToJsonElement(it.decodeToString()) }
        }

    /** Idempotent: close() may already have closed it. */
    internal fun watchClose(watch: Long) {
        lock.write {
            if (watches.remove(watch)) Native.watchClose(watch)
        }
    }

    /**
     * Run [invoke] against the live handle on [dispatcher] and decode its JSON
     * result. Building the arguments and decoding the result both happen on
     * the dispatcher too, so serialising a large batch never runs on the
     * caller's thread, but only the native call itself holds the lock.
     */
    internal suspend fun <A, R> execute(
        prepare: () -> A,
        invoke: (handle: Long, args: A) -> ByteArray,
        decode: (JsonElement) -> R,
    ): R =
        withContext(dispatcher) {
            val args = prepare()
            val bytes =
                lock.read {
                    check(handle != 0L) { "TalaDB database is closed" }
                    invoke(handle, args)
                }
            decode(TalaJson.parseToJsonElement(bytes.decodeToString()))
        }

    /** Run a dispatch-table operation; [args] are JSON values after the op name. */
    internal suspend fun <R> call(
        op: String,
        args: () -> List<JsonElement> = { emptyList() },
        decode: (JsonElement) -> R,
    ): R =
        execute(
            prepare = { JsonArray(args()).toString().cString() },
            invoke = { h, json -> Native.call(h, op.cString(), json) },
            decode = decode,
        )

    internal suspend fun <T : Any> acquireResource(
        acquire: suspend () -> T,
        release: suspend (T) -> Unit,
    ): T = acquireResource(dispatcher, acquire, release)

    public companion object {
        /** How long one native wait for a live-query write lasts; bounds cancellation latency. */
        internal const val WATCH_POLL_MS: Int = 250

        /**
         * Open the database at [file], creating it if it does not exist, and
         * run any pending [migrations] before returning.
         *
         * @param migrations Application schema migrations; see [Migration].
         *   Those with a version above the stored [userVersion] run in version
         *   order, and the stored version advances after each one. If one
         *   throws, the database is closed, the error propagates, and the next
         *   open resumes from that migration.
         * @param dispatcher Where every operation on this database runs.
         *   Defaults to [Dispatchers.IO]; operations block a thread for their
         *   whole duration, so it must be a dispatcher that tolerates that.
         * @throws TalaDBException if the file cannot be opened — including a
         *   missing or wrong passphrase for an encrypted database.
         * @throws IllegalArgumentException if two migrations share a version or
         *   a version is not in `1..4294967295`.
         */
        public suspend fun open(
            file: File,
            config: TalaDBConfig = TalaDBConfig(),
            migrations: List<Migration> = emptyList(),
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): TalaDB {
            val pending = Migration.validated(migrations)
            val db =
                acquireResource(
                    dispatcher,
                    acquire = {
                        Native.ensureCompatible()
                        TalaDB(Native.open(file.path.cString(), config.toJson().cString()), dispatcher)
                    },
                    release = { it.close() },
                )
            try {
                val current = db.userVersion()
                for (migration in pending) {
                    if (migration.version <= current) continue
                    migration.up(db)
                    db.setUserVersion(migration.version)
                }
            } catch (e: Throwable) {
                db.close()
                throw e
            }
            return db
        }

        /** The C ABI version of the loaded engine library. */
        public val abiVersion: Int
            get() = Native.libraryAbiVersion()
    }
}

/** The collection [name], with documents decoded as [T]. */
public inline fun <reified T> TalaDB.collection(name: String): TalaCollection<T> = collection(name, serializer<T>())
