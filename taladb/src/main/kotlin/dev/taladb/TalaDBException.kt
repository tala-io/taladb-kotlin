package dev.taladb

/**
 * An error reported by the TalaDB engine — an invalid filter, a missing index,
 * a duplicate `_id`, a wrong passphrase, a storage failure.
 *
 * Misuse of this package itself is reported with the standard exceptions
 * instead: [IllegalArgumentException] for a bad argument and
 * [IllegalStateException] for a call on a closed database.
 */
public class TalaDBException(message: String) : RuntimeException(message)
