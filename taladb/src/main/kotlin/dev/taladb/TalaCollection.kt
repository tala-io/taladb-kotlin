package dev.taladb

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext

/**
 * A named set of documents in a [TalaDB] database, decoded as [T].
 *
 * Filters, updates and pipelines use TalaDB's MongoDB-style JSON operators —
 * `{"age": {"$gte": 18}}`, `{"$set": {"active": true}}` — built with
 * kotlinx.serialization's `buildJsonObject`. Every stored document has a
 * string `_id`; a model class that wants it declares
 * `@SerialName("_id") val id: String? = null`. Leave it `null` on insert and
 * the engine assigns one; a supplied `_id` must be a ULID, and inserting one
 * that already exists fails rather than overwriting.
 */
public class TalaCollection<T> internal constructor(
    public val database: TalaDB,
    public val name: String,
    private val serializer: KSerializer<T>,
) {
    private val nameJson = JsonPrimitive(name)

    // -- Documents ------------------------------------------------------------

    /** Insert [document] and return its `_id`. */
    public suspend fun insert(document: T): String =
        call("insert", { listOf(encode(document)) }) { it.jsonPrimitive.content }

    /**
     * Insert [documents] in one transaction and return their ids in the same
     * order. All or nothing: if any document is rejected, none are written.
     */
    public suspend fun insertMany(documents: List<T>): List<String> =
        call("insertMany", { listOf(JsonArray(documents.map(::encode))) }) { result ->
            result.jsonArray.map { it.jsonPrimitive.content }
        }

    /** Every document matching [filter]. */
    public suspend fun find(filter: JsonObject = MatchAll): List<T> =
        call("find", { listOf(filter) }) { result -> result.jsonArray.map(::decode) }

    /**
     * The documents matching [filter], with only the fields [projection]
     * selects. Leaving out a large field — an embedding — means it is never
     * copied across from the engine at all.
     *
     * For a typed collection, every field the projection removes must be
     * nullable or have a default in [T], or decoding fails.
     *
     * ```kotlin
     * notes.find(projection = Projection.exclude("embedding"))
     * ```
     */
    public suspend fun find(
        filter: JsonObject = MatchAll,
        projection: Projection?,
    ): List<T> =
        call("find", { listOfNotNull(filter, projection?.toJson()) }) { result -> result.jsonArray.map(::decode) }

    /** The first document matching [filter], or `null`. */
    public suspend fun findOne(filter: JsonObject = MatchAll): T? =
        call("findOne", { listOf(filter) }) { if (it is JsonNull) null else decode(it) }

    /** How many documents match [filter]. */
    public suspend fun count(filter: JsonObject = MatchAll): Long =
        call("count", { listOf(filter) }) { it.jsonPrimitive.long }

    /** Apply [update] to the first document matching [filter]. Returns whether one matched. */
    public suspend fun updateOne(
        filter: JsonObject,
        update: JsonObject,
    ): Boolean = call("updateOne", { listOf(filter, update) }) { it.jsonPrimitive.boolean }

    /** Apply [update] to every document matching [filter]. Returns how many were updated. */
    public suspend fun updateMany(
        filter: JsonObject,
        update: JsonObject,
    ): Long = call("updateMany", { listOf(filter, update) }) { it.jsonPrimitive.long }

    /** Delete the first document matching [filter]. Returns whether one matched. */
    public suspend fun deleteOne(filter: JsonObject): Boolean =
        call("deleteOne", { listOf(filter) }) { it.jsonPrimitive.boolean }

    /**
     * Delete every document matching [filter]. Returns how many were deleted.
     * [MatchAll] empties the collection.
     */
    public suspend fun deleteMany(filter: JsonObject): Long =
        call("deleteMany", { listOf(filter) }) { it.jsonPrimitive.long }

    /**
     * Run an aggregation [pipeline] of `$match`, `$group`, `$sort`, `$skip`,
     * `$limit` and `$project` stages. Results are raw JSON because their shape
     * is set by the pipeline, not by [T].
     */
    public suspend fun aggregate(pipeline: List<JsonObject>): List<JsonObject> =
        call("aggregate", { listOf(JsonArray(pipeline)) }) { result -> result.jsonArray.map { it.jsonObject } }

    // -- Live queries ---------------------------------------------------------

    /**
     * The documents matching [filter], now and after every write that changes
     * them.
     *
     * Emits the current result first, then a fresh result whenever a write —
     * through any [TalaDB] handle on this database — changes it. Rapid writes
     * coalesce into one emission of the latest state; nothing is skipped.
     *
     * Cold and independent per collector: collecting opens a native
     * subscription, and cancelling the collector closes it within a fraction
     * of a second. Closing the database ends every collection with
     * [IllegalStateException].
     *
     * ```kotlin
     * notes.watch(buildJsonObject { put("done", false) })
     *     .collect { open -> render(open) }
     * ```
     */
    public fun watch(filter: JsonObject = MatchAll): Flow<List<T>> = watch(filter, projection = null)

    /**
     * [watch], with every emission limited to the fields [projection]
     * selects. A live query sends a fresh result across on every write, so
     * excluding a large field the screen does not show — an embedding — keeps
     * each update small. The same decoding rule as [find] applies.
     */
    public fun watch(
        filter: JsonObject = MatchAll,
        projection: Projection?,
    ): Flow<List<T>> =
        flow {
            // Subscribe before reading the initial state, so a write that
            // lands between the two still wakes the loop below.
            val watch = database.watchOpen(name, filter, projection)
            // The collector's thread is an app's main thread. Comparing and
            // decoding each snapshot, and unsubscribing, run on the database's
            // dispatcher instead: unsubscribing waits for the database lock,
            // which every other running live query takes for up to a poll, so
            // on the main thread leaving a screen froze the UI for a poll per
            // live query it had.
            var last: JsonElement? = null
            suspend fun publish(snapshot: JsonElement) {
                val changed =
                    withContext(database.dispatcher) {
                        if (snapshot == last) null else snapshot.jsonArray.map(::decode).also { last = snapshot }
                    }
                if (changed != null) emit(changed)
            }
            try {
                publish(call("find", { listOfNotNull(filter, projection?.toJson()) }) { it })
                while (true) {
                    coroutineContext.ensureActive()
                    database.watchNext(watch)?.let { publish(it) }
                }
            } finally {
                withContext(NonCancellable + database.dispatcher) { database.watchClose(watch) }
            }
        }

    // -- Indexes --------------------------------------------------------------

    /** Index [field] for equality and range filters. A no-op if it exists. */
    public suspend fun createIndex(field: String) {
        call("createIndex", { listOf(JsonPrimitive(field)) }) {}
    }

    /** @throws TalaDBException if there is no index on [field]. */
    public suspend fun dropIndex(field: String) {
        call("dropIndex", { listOf(JsonPrimitive(field)) }) {}
    }

    /** Index [fields] together, in order, for filters and sorts that use them jointly. */
    public suspend fun createCompoundIndex(fields: List<String>) {
        call("createCompoundIndex", { listOf(JsonArray(fields.map(::JsonPrimitive))) }) {}
    }

    public suspend fun dropCompoundIndex(fields: List<String>) {
        call("dropCompoundIndex", { listOf(JsonArray(fields.map(::JsonPrimitive))) }) {}
    }

    /** Index [field] for [searchText] and the `$contains` filter. A no-op if it exists. */
    public suspend fun createFtsIndex(field: String) {
        call("createFtsIndex", { listOf(JsonPrimitive(field)) }) {}
    }

    /** @throws TalaDBException if there is no full-text index on [field]. */
    public suspend fun dropFtsIndex(field: String) {
        call("dropFtsIndex", { listOf(JsonPrimitive(field)) }) {}
    }

    /** The fields indexed on this collection, by index kind. */
    public suspend fun listIndexes(): IndexInfo =
        call("listIndexes") { result ->
            val info = result.jsonObject
            fun names(key: String) = info.getValue(key).jsonArray.map { it.jsonPrimitive.content }
            IndexInfo(btree = names("btree"), fts = names("fts"), vector = names("vector"))
        }

    // -- Vectors --------------------------------------------------------------

    /**
     * Index the float-array [field] for [findNearest].
     *
     * Exact search (the default, [hnsw] = `null`) is faster below tens of
     * thousands of vectors and always exact. Pass [HnswOptions] to build a
     * persistent approximate graph instead.
     */
    public suspend fun createVectorIndex(
        field: String,
        dimensions: Int,
        metric: VectorMetric = VectorMetric.Cosine,
        hnsw: HnswOptions? = null,
    ) {
        require(dimensions > 0) { "dimensions must be positive" }
        call("createVectorIndex", {
            val options =
                buildJsonObject {
                    put("metric", metric.wire)
                    hnsw?.let { put("hnsw", it.toJson()) }
                }
            listOf(JsonPrimitive(field), JsonPrimitive(dimensions), options)
        }) {}
    }

    public suspend fun dropVectorIndex(field: String) {
        call("dropVectorIndex", { listOf(JsonPrimitive(field)) }) {}
    }

    /** Promote a flat or legacy vector index, or compact its HNSW graph. */
    public suspend fun upgradeVectorIndex(field: String) {
        call("upgradeVectorIndex", { listOf(JsonPrimitive(field)) }) {}
    }

    /**
     * The [topK] documents whose [field] is most similar to [vector], best
     * first. [filter] narrows the candidates before ranking.
     */
    public suspend fun findNearest(
        field: String,
        vector: FloatArray,
        topK: Int,
        filter: JsonObject? = null,
    ): List<ScoredDocument<T>> {
        require(topK >= 0) { "topK must not be negative" }
        return database.execute(
            prepare = { filter?.toString()?.cString() },
            invoke = { h, filterBytes ->
                Native.findNearest(h, name.cString(), field.cString(), vector, topK, filterBytes)
            },
            decode = ::decodeScored,
        )
    }

    /**
     * Vector search with execution control: exact or approximate mode,
     * `efSearch`, a score threshold, offset pagination and grouping. Returns
     * how the query ran alongside the hits. [findNearest] covers the common
     * case.
     */
    public suspend fun searchVectors(
        field: String,
        vector: FloatArray,
        topK: Int,
        filter: JsonObject? = null,
        options: VectorQueryOptions = VectorQueryOptions(),
    ): VectorQueryResult<T> {
        require(topK >= 0) { "topK must not be negative" }
        val request =
            buildJsonObject {
                put("op", "search")
                put("field", field)
                put("query", vector.toJsonArray())
                put("topK", topK)
                filter?.let { put("filter", it) }
                put("options", options.toJson())
            }
        return vectorCommand(request) { result ->
            val o = result.jsonObject
            VectorQueryResult(
                hits = decodeScored(o.getValue("hits")),
                execution = TalaJson.decodeFromJsonElement(VectorExecution.serializer(), o.getValue("execution")),
                nextOffset = o["nextOffset"]?.jsonPrimitive?.intOrNull,
            )
        }
    }

    /** Every document whose [field] scores at least [scoreThreshold] against [vector], by exact search. */
    public suspend fun findWithin(
        field: String,
        vector: FloatArray,
        scoreThreshold: Float,
        filter: JsonObject? = null,
    ): VectorQueryResult<T> =
        searchVectors(
            field,
            vector,
            topK = Int.MAX_VALUE,
            filter = filter,
            options = VectorQueryOptions(mode = VectorSearchMode.Exact, scoreThreshold = scoreThreshold),
        )

    /** Whether [field]'s vector index is flat, ready, stale or needs a rebuild, and any build in progress. */
    public suspend fun vectorIndexStatus(field: String): VectorIndexStatus =
        vectorCommand(buildJsonObject { put("op", "status"); put("field", field) }) {
            TalaJson.decodeFromJsonElement(VectorIndexStatus.serializer(), it)
        }

    /**
     * Build or rebuild [field]'s HNSW graph in batches of [batchSize]
     * insertions, reporting progress after each. The index stays queryable
     * throughout — searches use exact scan until the graph is ready — so this
     * is safe to run while the app is in use.
     *
     * Cancelling the calling coroutine cancels the build.
     *
     * @throws TalaDBException if the build fails.
     */
    public suspend fun rebuildVectorIndex(
        field: String,
        options: HnswOptions? = null,
        batchSize: Int = 32,
        onProgress: (VectorBuildProgress) -> Unit = {},
    ): VectorBuildProgress {
        require(batchSize in 1..1024) { "batchSize must be in 1..1024" }
        var progress = beginVectorBuild(field, options)
        try {
            onProgress(progress)
            while (progress.state == VectorBuildState.Building) {
                coroutineContext.ensureActive()
                progress = stepVectorBuild(field, progress.id, batchSize)
                onProgress(progress)
            }
        } catch (e: Throwable) {
            if (progress.state == VectorBuildState.Building) {
                withContext(NonCancellable) { runCatching { cancelVectorBuild(field, progress.id) } }
            }
            throw e
        }
        if (progress.state == VectorBuildState.Failed) {
            throw TalaDBException(progress.error ?: "vector index rebuild failed")
        }
        return progress
    }

    /** Start a batched HNSW build; drive it with [stepVectorBuild]. [rebuildVectorIndex] does both. */
    public suspend fun beginVectorBuild(
        field: String,
        options: HnswOptions? = null,
    ): VectorBuildProgress =
        database.acquireResource(
            acquire = {
                vectorCommand(
                    buildJsonObject {
                        put("op", "beginBuild")
                        put("field", field)
                        options?.let { put("options", it.toJson()) }
                    },
                    ::decodeProgress,
                )
            },
            release = { cancelVectorBuild(field, it.id) },
        )

    /** Insert up to [batchSize] more vectors into the build [id]. */
    public suspend fun stepVectorBuild(
        field: String,
        id: String,
        batchSize: Int = 32,
    ): VectorBuildProgress {
        require(batchSize in 1..1024) { "batchSize must be in 1..1024" }
        return vectorCommand(
            buildJsonObject {
                put("op", "stepBuild")
                put("field", field)
                put("id", id)
                put("batchSize", batchSize)
            },
            ::decodeProgress,
        )
    }

    public suspend fun cancelVectorBuild(
        field: String,
        id: String,
    ): VectorBuildProgress =
        vectorCommand(
            buildJsonObject {
                put("op", "cancelBuild")
                put("field", field)
                put("id", id)
            },
            ::decodeProgress,
        )

    /**
     * Measure how often approximate search finds the exact top [topK] for
     * [queries] — use real query embeddings, not stored vectors — and how
     * long each path takes. For tuning `efSearch` and graph options.
     */
    public suspend fun measureVectorRecall(
        field: String,
        queries: List<FloatArray>,
        topK: Int,
        filter: JsonObject? = null,
        options: VectorQueryOptions = VectorQueryOptions(),
    ): VectorRecall {
        require(queries.size in 1..1000) { "recall needs 1..1000 queries" }
        require(topK >= 1) { "topK must be positive" }
        return vectorCommand(
            buildJsonObject {
                put("op", "recall")
                put("field", field)
                put("queries", JsonArray(queries.map { it.toJsonArray() }))
                put("topK", topK)
                filter?.let { put("filter", it) }
                put("options", options.toJson())
            },
        ) { TalaJson.decodeFromJsonElement(VectorRecall.serializer(), it) }
    }

    // -- Full-text ------------------------------------------------------------

    /**
     * The [topK] documents whose full-text-indexed [field] best matches
     * [query], ranked by BM25 with OR semantics. Requires [createFtsIndex].
     */
    public suspend fun searchText(
        field: String,
        query: String,
        topK: Int,
        filter: JsonObject? = null,
        options: Bm25Options = Bm25Options(),
    ): List<ScoredDocument<T>> {
        require(topK >= 0) { "topK must not be negative" }
        return call("searchText", {
            listOf(JsonPrimitive(field), JsonPrimitive(query), JsonPrimitive(topK), filter ?: JsonNull, options.toJson())
        }, ::decodeScored)
    }

    /**
     * Rank by both text relevance and vector similarity, fused with
     * reciprocal rank fusion — for queries where exact terms and meaning both
     * matter, such as retrieval for on-device RAG.
     */
    public suspend fun hybridSearch(
        textField: String,
        text: String,
        vectorField: String,
        vector: FloatArray,
        topK: Int,
        filter: JsonObject? = null,
        options: HybridOptions = HybridOptions(),
    ): List<HybridHit<T>> {
        require(topK >= 0) { "topK must not be negative" }
        return database.execute(
            prepare = { Pair(filter?.toString()?.cString(), options.toJson().toString().cString()) },
            invoke = { h, (filterBytes, optionBytes) ->
                Native.hybridSearch(
                    h,
                    name.cString(),
                    textField.cString(),
                    text.cString(),
                    vectorField.cString(),
                    vector,
                    topK,
                    filterBytes,
                    optionBytes,
                )
            },
            decode = { result ->
                result.jsonArray.map { hit ->
                    val o = hit.jsonObject
                    HybridHit(
                        document = decode(o.getValue("document")),
                        score = o.getValue("score").jsonPrimitive.double,
                        textRank = o["textRank"]?.jsonPrimitive?.intOrNull,
                        vectorRank = o["vectorRank"]?.jsonPrimitive?.intOrNull,
                    )
                }
            },
        )
    }

    // -- Plumbing -------------------------------------------------------------

    /** A dispatch-table call whose first argument is this collection's name. */
    private suspend fun <R> call(
        op: String,
        args: () -> List<JsonElement> = { emptyList() },
        decode: (JsonElement) -> R,
    ): R = database.call(op, { listOf(nameJson) + args() }, decode)

    private fun encode(document: T): JsonObject =
        TalaJson.encodeToJsonElement(serializer, document) as? JsonObject
            ?: throw IllegalArgumentException(
                "TalaDB documents must serialize to a JSON object; ${serializer.descriptor.serialName} does not",
            )

    private fun decode(element: JsonElement): T = TalaJson.decodeFromJsonElement(serializer, element)

    private suspend fun <R> vectorCommand(
        request: JsonObject,
        decode: (JsonElement) -> R,
    ): R = call("vectorCommand", { listOf(request) }, decode)

    private fun decodeProgress(element: JsonElement): VectorBuildProgress =
        TalaJson.decodeFromJsonElement(VectorBuildProgress.serializer(), element)

    private fun decodeScored(result: JsonElement): List<ScoredDocument<T>> =
        result.jsonArray.map { hit ->
            val o = hit.jsonObject
            ScoredDocument(decode(o.getValue("document")), o.getValue("score").jsonPrimitive.double)
        }
}
