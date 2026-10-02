package dev.taladb

/**
 * An error reported by the TalaDB engine — an invalid filter, a missing index,
 * a duplicate `_id`, a wrong passphrase, a storage failure.
 *
 * Branch on [code], not on the message: codes are a stable contract shared
 * with TalaDB's JavaScript clients (`error.code`), while messages are written
 * for people and may change. A wrong passphrase at [TalaDB.open], for example,
 * is [ENCRYPTION]:
 *
 * ```kotlin
 * try {
 *     TalaDB.open(file, TalaDBConfig(passphrase = typed))
 * } catch (e: TalaDBException) {
 *     if (e.code == TalaDBException.ENCRYPTION) showWrongPassphrase() else throw e
 * }
 * ```
 *
 * Misuse of this package itself is reported with the standard exceptions
 * instead: [IllegalArgumentException] for a bad argument and
 * [IllegalStateException] for a call on a closed database.
 *
 * @property code The engine's error code, one of the constants below, or
 *   `null` when the failure did not come from the engine. New codes may be
 *   added in later releases, so handle an unrecognised one generically.
 */
public class TalaDBException @JvmOverloads constructor(
    message: String,
    public val code: String? = null,
) : RuntimeException(message) {
    public companion object {
        /** A wrong passphrase, or a file not encrypted with the one given. */
        public const val ENCRYPTION: String = "Encryption"

        /** A filter, update or pipeline the engine cannot parse. */
        public const val INVALID_FILTER: String = "InvalidFilter"

        /** An insert whose `_id` already exists in the collection. */
        public const val DUPLICATE_ID: String = "DuplicateId"

        /** An `_id` that is not a ULID; see [deriveDocId]. */
        public const val INVALID_DOCUMENT_ID: String = "InvalidDocumentId"

        /** A collection or field name the engine does not accept. */
        public const val INVALID_NAME: String = "InvalidName"

        /** An operation that is not valid in the current state. */
        public const val INVALID_OPERATION: String = "InvalidOperation"

        public const val INDEX_EXISTS: String = "IndexExists"
        public const val INDEX_NOT_FOUND: String = "IndexNotFound"
        public const val VECTOR_INDEX_NOT_FOUND: String = "VectorIndexNotFound"

        /** A vector whose length differs from its index's dimensions. */
        public const val VECTOR_DIMENSION_MISMATCH: String = "VectorDimensionMismatch"

        /** A compound index over more than one array field. */
        public const val COMPOUND_INDEX_MULTIPLE_ARRAYS: String = "CompoundIndexMultipleArrays"

        /** A field's value has the wrong type for the operation. */
        public const val TYPE_ERROR: String = "TypeError"

        /** A schema migration failed. */
        public const val MIGRATION: String = "Migration"

        /** The file could not be read or written: disk full, I/O error, corruption. */
        public const val STORAGE: String = "Storage"

        public const val SERIALIZATION: String = "Serialization"
        public const val NOT_FOUND: String = "NotFound"
        public const val CONFIG: String = "Config"
        public const val QUERY_TIMEOUT: String = "QueryTimeout"
    }
}
