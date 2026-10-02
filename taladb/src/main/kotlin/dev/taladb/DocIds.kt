package dev.taladb

import java.math.BigInteger

private val FNV1A128_OFFSET_BASIS = BigInteger("6c62272e07bb014262b821756295c58d", 16)
private val FNV1A128_PRIME = BigInteger("0000000001000000000000000000013b", 16)
private val U128_MASK = BigInteger.ONE.shiftLeft(128) - BigInteger.ONE
private const val CROCKFORD_BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private val FIVE_BITS = BigInteger.valueOf(31)

/**
 * A stable `_id` for a natural key: the same [collection] and [key] always
 * give the same ULID.
 *
 * TalaDB accepts a caller-supplied `_id` only if it is a ULID, so a SKU, a
 * server's primary key, or the name of a singleton document cannot be stored
 * as one directly. Derive one instead, and a seed or a sync from a server can
 * run again without inserting duplicates:
 *
 * ```kotlin
 * val settingsId = deriveDocId("meta", "settings")
 * meta.insert(Settings(id = settingsId, theme = "dark"))
 * meta.findOne(buildJsonObject { put("_id", settingsId) })
 * ```
 *
 * Byte-identical to the engine's `derive_doc_id` and the JavaScript clients'
 * `deriveDocId` — FNV-1a 128 over the UTF-8 of `collection`, a zero byte and
 * `key`, written as a ULID — so every client maps a key to the same document.
 * The result is a hash, so its ULID timestamp is not chronological.
 */
public fun deriveDocId(collection: String, key: String): String {
    var hash = FNV1A128_OFFSET_BASIS
    val preimage = collection.encodeToByteArray() + 0 + key.encodeToByteArray()
    for (byte in preimage) {
        hash = hash.xor(BigInteger.valueOf((byte.toInt() and 0xff).toLong()))
        hash = hash.multiply(FNV1A128_PRIME).and(U128_MASK)
    }
    val ulid = CharArray(26)
    for (i in 25 downTo 0) {
        ulid[i] = CROCKFORD_BASE32[hash.and(FIVE_BITS).toInt()]
        hash = hash.shiftRight(5)
    }
    return String(ulid)
}
