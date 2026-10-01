package dev.taladb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How [TalaCollection.searchVectors] chooses between exact scan and the HNSW graph. */
public enum class VectorSearchMode(internal val wire: String) {
    /** The graph when it is ready and there is no filter; otherwise exact. Filtered queries stay exact unless [Ann] is requested. */
    Auto("auto"),
    Exact("exact"),

    /** Approximate; fails with [TalaDBException] if the graph is missing or stale. */
    Ann("ann"),
}

/**
 * Execution controls for [TalaCollection.searchVectors]. `null` keeps the
 * engine default.
 *
 * @property efSearch HNSW candidate list size: higher is slower and more exact.
 * @property scoreThreshold Drop hits scoring below this.
 * @property offset Skip this many hits, for pagination. Pages are taken from
 *   live data, so writes between pages can shift results.
 * @property groupBy Keep only the best [groupSize] hits per distinct value of
 *   this field.
 * @property oversampling ANN candidates fetched per result before exact
 *   rescoring (1–100).
 */
public data class VectorQueryOptions(
    val mode: VectorSearchMode = VectorSearchMode.Auto,
    val efSearch: Int? = null,
    val scoreThreshold: Float? = null,
    val offset: Int = 0,
    val groupBy: String? = null,
    val groupSize: Int? = null,
    val oversampling: Int? = null,
) {
    init {
        require(efSearch == null || efSearch >= 1) { "efSearch must be positive" }
        require(scoreThreshold == null || scoreThreshold.isFinite()) { "scoreThreshold must be finite" }
        require(offset >= 0) { "offset must not be negative" }
        require(groupSize == null || groupSize >= 1) { "groupSize must be positive" }
        require(oversampling == null || oversampling in 1..100) { "oversampling must be in 1..100" }
    }

    // The engine rejects unknown keys, so only set fields are sent.
    internal fun toJson(): JsonObject =
        buildJsonObject {
            put("mode", mode.wire)
            efSearch?.let { put("efSearch", it) }
            scoreThreshold?.let { put("scoreThreshold", it) }
            if (offset != 0) put("offset", offset)
            groupBy?.let { put("groupBy", it) }
            groupSize?.let { put("groupSize", it) }
            oversampling?.let { put("oversampling", it) }
        }
}

/** The hits of a [TalaCollection.searchVectors] call and how it ran. */
public data class VectorQueryResult<T>(
    val hits: List<ScoredDocument<T>>,
    val execution: VectorExecution,
    /** Pass as [VectorQueryOptions.offset] for the next page; `null` on the last. */
    val nextOffset: Int?,
)

/** How a vector query executed. */
@Serializable
public data class VectorExecution(
    /** `"exact"` or `"hnsw"`. */
    val path: String,
    /** Why that path was chosen. */
    val reason: String,
    val revision: Long,
    val efSearch: Int? = null,
    val distanceComputations: Long,
)

@Serializable
public enum class VectorBuildState {
    @SerialName("building") Building,
    @SerialName("ready") Ready,
    @SerialName("cancelled") Cancelled,
    @SerialName("failed") Failed,
}

/** Progress of a batched HNSW build. */
@Serializable
public data class VectorBuildProgress(
    val id: String,
    val state: VectorBuildState,
    val processed: Long,
    val total: Long,
    val revision: Long,
    val error: String? = null,
)

@Serializable
public enum class VectorIndexState {
    /** Exact search only; no graph. */
    @SerialName("flat") Flat,

    /** The graph covers every vector. */
    @SerialName("ready") Ready,

    /** The graph is behind the stored vectors; [VectorSearchMode.Auto] uses exact scan until it is rebuilt. */
    @SerialName("stale") Stale,

    /** A legacy-format index from an older engine; rebuild it to get a graph. */
    @SerialName("rebuildRequired") RebuildRequired,
}

/** The state of one vector index. */
@Serializable
public data class VectorIndexStatus(
    val field: String,
    val state: VectorIndexState,
    val persistent: Boolean,
    val indexedVectors: Long,
    val totalVectors: Long,
    val deletedNodes: Long,
    val revision: Long,
    val indexRevision: Long? = null,
    val options: HnswOptions? = null,
    val build: VectorBuildProgress? = null,
)

/** Approximate-search quality against exact ground truth, from [TalaCollection.measureVectorRecall]. */
@Serializable
public data class VectorRecall(
    /** Mean fraction of the exact top-k that approximate search also returned. */
    val recallAtK: Double,
    val queries: Int,
    val topK: Int,
    val exactMs: Double,
    val annMs: Double,
)

internal fun FloatArray.toJsonArray(): JsonArray {
    require(isNotEmpty() && all { it.isFinite() }) { "vector must contain finite numbers" }
    return JsonArray(map { JsonPrimitive(it) })
}
