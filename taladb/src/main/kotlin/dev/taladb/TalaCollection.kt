package dev.taladb

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

    private fun decodeScored(result: JsonElement): List<ScoredDocument<T>> =
        result.jsonArray.map { hit ->
            val o = hit.jsonObject
            ScoredDocument(decode(o.getValue("document")), o.getValue("score").jsonPrimitive.double)
        }
}
