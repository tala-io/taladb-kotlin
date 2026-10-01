package dev.taladb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Options for [TalaDB.open].
 *
 * @property passphrase Encrypts the database at rest. Opening an encrypted
 *   database requires the same passphrase; opening it without one, or with the
 *   wrong one, throws [TalaDBException].
 * @property flushEveryWrite `true` (the default) fsyncs every write, so an
 *   acknowledged write survives a crash. `false` batches commits for higher
 *   write throughput; call [TalaDB.flush] to force a durable sync.
 */
public class TalaDBConfig(
    public val passphrase: String? = null,
    public val flushEveryWrite: Boolean = true,
) {
    internal fun toJson(): String =
        buildJsonObject {
            putJsonObject("durability") { put("flush_every_write", flushEveryWrite) }
            passphrase?.let { put("passphrase", it) }
        }.toString()

    /** Never prints the passphrase, so a config can be logged safely. */
    override fun toString(): String =
        "TalaDBConfig(passphrase=${if (passphrase == null) "null" else "<redacted>"}, " +
            "flushEveryWrite=$flushEveryWrite)"
}

/** Similarity measure for a vector index. HNSW supports [Cosine] and [Euclidean]. */
public enum class VectorMetric(internal val wire: String) {
    Cosine("cosine"),
    Dot("dot"),
    Euclidean("euclidean"),
}

/** Vector compression used inside an HNSW graph. [Binary] requires [VectorMetric.Cosine]. */
@Serializable
public enum class Quantization {
    @SerialName("none") None,
    @SerialName("scalar") Scalar,
    @SerialName("binary") Binary,
}

/**
 * Build a persistent HNSW graph instead of using exact search.
 *
 * Exact search is faster at small sizes and always exact; reach for HNSW once a
 * collection holds tens of thousands of vectors. Requires `2 <= m <= 128` and
 * `m <= efConstruction <= 100000`.
 */
@Serializable
public data class HnswOptions(
    val m: Int = 32,
    val efConstruction: Int = 200,
    val quantization: Quantization = Quantization.None,
) {
    init {
        require(m in 2..128) { "m must be in 2..128" }
        require(efConstruction in m..100_000) { "efConstruction must be in m..100000" }
    }

    internal fun toJson(): JsonObject = TalaJson.encodeToJsonElement(serializer(), this).jsonObject
}

/** BM25 parameters for [TalaCollection.searchText] and [TalaCollection.hybridSearch]. `null` keeps the engine default. */
public data class Bm25Options(
    val k1: Double? = null,
    val b: Double? = null,
)

/**
 * Reciprocal rank fusion parameters for [TalaCollection.hybridSearch]. `null`
 * keeps the engine default.
 *
 * @property candidates How many results each of the text and vector searches
 *   contributes before fusion.
 */
public data class HybridOptions(
    val rrfK: Double? = null,
    val textWeight: Double? = null,
    val vectorWeight: Double? = null,
    val candidates: Int? = null,
    val bm25: Bm25Options = Bm25Options(),
) {
    internal fun toJson(): JsonObject =
        buildJsonObject {
            rrfK?.let { put("rrfK", it) }
            textWeight?.let { put("textWeight", it) }
            vectorWeight?.let { put("vectorWeight", it) }
            candidates?.let { put("candidates", it) }
            bm25.k1?.let { put("k1", it) }
            bm25.b?.let { put("b", it) }
        }
}

internal fun Bm25Options.toJson(): JsonObject =
    buildJsonObject {
        k1?.let { put("k1", it) }
        b?.let { put("b", it) }
    }

/** A document with its similarity or relevance score. Higher is closer. */
public data class ScoredDocument<T>(
    val document: T,
    val score: Double,
)

/**
 * A [TalaCollection.hybridSearch] result.
 *
 * @property score The fused reciprocal-rank score. It is small by construction
 *   and meaningful only for ordering within one result list — not as a
 *   similarity or a confidence.
 * @property textRank Zero-based position in the text ranking, or `null` if the
 *   text search did not return this document.
 * @property vectorRank Zero-based position in the vector ranking, or `null` if
 *   the vector search did not return this document.
 */
public data class HybridHit<T>(
    val document: T,
    val score: Double,
    val textRank: Int?,
    val vectorRank: Int?,
)

/** The indexes on one collection, by field. */
public data class IndexInfo(
    val btree: List<String>,
    val fts: List<String>,
    val vector: List<String>,
)
