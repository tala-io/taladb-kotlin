package dev.taladb

/**
 * An application schema migration, run once by [TalaDB.open] when [version] is
 * above the database's stored [TalaDB.userVersion].
 *
 * ```kotlin
 * val db = TalaDB.open(file, migrations = listOf(
 *     Migration(1, "Index users by email") { db ->
 *         db.collection("users").createIndex("email")
 *     },
 *     Migration(2, "Default role") { db ->
 *         db.collection("users").updateMany(
 *             buildJsonObject { putJsonObject("role") { put("\$exists", false) } },
 *             buildJsonObject { putJsonObject("\$set") { put("role", "user") } },
 *         )
 *     },
 * ))
 * ```
 *
 * The stored version advances after each migration completes — a checkpoint
 * per version, not one transaction for the batch. A migration that fails
 * halfway keeps the writes it made and runs again from the top on the next
 * open, so write [up] to be safe to repeat. Index creation already is.
 *
 * Never change a migration that has shipped: a device that ran it will not run
 * it again. Add one with a higher version instead. Versions may have gaps;
 * version 1 is effectively the initial schema of a fresh install.
 *
 * Separate from the engine's own storage-format upgrades, which run
 * automatically on every open.
 */
public class Migration(
    public val version: Long,
    public val description: String = "",
    public val up: suspend (TalaDB) -> Unit,
) {
    override fun toString(): String = "Migration($version, \"$description\")"

    internal companion object {
        /** Sorted by version; fails before anything is opened if the list is malformed. */
        fun validated(migrations: List<Migration>): List<Migration> {
            val sorted = migrations.sortedBy { it.version }
            for ((i, m) in sorted.withIndex()) {
                require(m.version in 1..UInt.MAX_VALUE.toLong()) {
                    "migration version must be in 1..${UInt.MAX_VALUE}, got ${m.version}"
                }
                require(i == 0 || sorted[i - 1].version != m.version) {
                    "duplicate migration version ${m.version}"
                }
            }
            return sorted
        }
    }
}
