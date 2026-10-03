package dev.taladb

/**
 * JNI entry points implemented in `src/main/cpp/taladb_jni.c`.
 *
 * Every string argument is NUL-terminated UTF-8 produced by [cString], and
 * every string result is UTF-8 to be decoded with `decodeToString()`. JNI's own
 * string conversions use modified UTF-8, which the engine rejects for any
 * character outside the Basic Multilingual Plane — see the shim's header.
 *
 * Failures throw [TalaDBException] carrying the engine's message.
 */
internal object Native {
    init {
        try {
            // Load order matters on older Android linkers: the shim declares a
            // dependency on libtaladb_ffi.so, so load that first.
            System.loadLibrary("taladb_ffi")
            System.loadLibrary("taladb_jni")
        } catch (e: UnsatisfiedLinkError) {
            throw UnsatisfiedLinkError(
                "TalaDB native libraries failed to load: ${e.message}. An 'undefined symbol " +
                    "taladb_*' here means libtaladb_ffi.so is older than this package expects.",
            )
        }
    }

    /**
     * Fails unless the engine library and the header the shim was compiled
     * against agree on the C ABI version. A signature that changed between the
     * two still links, and then corrupts the stack on the first call — so this
     * runs before any database is opened.
     */
    fun ensureCompatible() {
        compatibility.value
    }

    private val compatibility = lazy {
        val header = headerAbiVersion()
        val library = libraryAbiVersion()
        check(header == library) {
            "TalaDB native library ABI version $library does not match the version " +
                "$header this package was built for. The bundled libtaladb_ffi.so comes " +
                "from a different engine release than the JNI shim."
        }
    }

    external fun headerAbiVersion(): Int

    external fun libraryAbiVersion(): Int

    /** Returns a non-zero handle. `config` may be null for defaults. */
    external fun open(path: ByteArray, config: ByteArray?): Long

    external fun close(handle: Long)

    /** Runs a dispatch-table operation; `args` is a JSON array. */
    external fun call(handle: Long, op: ByteArray, args: ByteArray): ByteArray

    external fun findNearest(
        handle: Long,
        collection: ByteArray,
        field: ByteArray,
        query: FloatArray,
        topK: Int,
        filter: ByteArray?,
    ): ByteArray

    /**
     * Returns a non-zero watch handle; `filter` may be null for all documents,
     * `options` null for no projection.
     */
    external fun watchOpen(handle: Long, collection: ByteArray, filter: ByteArray?, options: ByteArray?): Long

    /** The next snapshot (a JSON array), or null if [timeoutMs] passed without a write. */
    external fun watchNext(watch: Long, timeoutMs: Int): ByteArray?

    external fun watchClose(watch: Long)

    external fun hybridSearch(
        handle: Long,
        collection: ByteArray,
        textField: ByteArray,
        text: ByteArray,
        vectorField: ByteArray,
        vector: FloatArray,
        topK: Int,
        filter: ByteArray?,
        options: ByteArray?,
    ): ByteArray
}

/**
 * Encode as NUL-terminated UTF-8 for the C interface.
 *
 * A string containing U+0000 is rejected rather than passed on: C would see it
 * end at the first NUL, so a collection named "a\u0000b" would silently become
 * "a". JSON text never reaches this check with a raw NUL — the encoder escapes
 * it as `\u0000` — so this only constrains names and search text.
 */
internal fun String.cString(): ByteArray {
    require('\u0000' !in this) { "TalaDB strings must not contain U+0000" }
    val utf8 = encodeToByteArray()
    return utf8.copyOf(utf8.size + 1)
}
