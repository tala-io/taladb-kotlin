package dev.taladb

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val file: File get() = tmp.root.resolve("migrate.db")

    @Test
    fun runsPendingMigrationsInVersionOrderOnce() =
        runBlocking {
            val ran = mutableListOf<Long>()
            fun m(v: Long) = Migration(v, "v$v") { ran += v }

            // Declared out of order on purpose; they run sorted.
            TalaDB.open(file, migrations = listOf(m(3), m(1))).use { assertEquals(3L, it.userVersion()) }
            assertEquals(listOf(1L, 3L), ran)

            ran.clear()
            TalaDB.open(file, migrations = listOf(m(1), m(3), m(4))).use { assertEquals(4L, it.userVersion()) }
            assertEquals("only the new migration runs", listOf(4L), ran)
        }

    @Test
    fun migrationsSeeTheDatabaseAndTheirWritesPersist() =
        runBlocking {
            val seed =
                Migration(1, "seed") { db ->
                    db.collection("users").createIndex("email")
                    db.collection("users").insert(buildJsonObject { put("email", "a@b.c") })
                }
            TalaDB.open(file, migrations = listOf(seed)).use {
                assertEquals(listOf("email"), it.collection("users").listIndexes().btree)
                assertEquals(1L, it.collection("users").count())
            }
        }

    @Test
    fun aFailedMigrationClosesTheDatabaseAndResumesThereNextTime() =
        runBlocking {
            val ran = mutableListOf<Long>()
            var failTwo = true
            val migrations =
                listOf(
                    Migration(1) { ran += 1 },
                    Migration(2) {
                        ran += 2
                        if (failTwo) error("boom")
                    },
                    Migration(3) { ran += 3 },
                )

            val error = runCatching { TalaDB.open(file, migrations = migrations) }.exceptionOrNull()
            assertEquals("boom", error?.message)
            assertEquals(listOf(1L, 2L), ran)

            // The failed open closed its handle, so the file can be reopened.
            ran.clear()
            failTwo = false
            TalaDB.open(file, migrations = migrations).use { assertEquals(3L, it.userVersion()) }
            assertEquals("resumes at the failed migration", listOf(2L, 3L), ran)
        }

    @Test
    fun malformedMigrationListsFailBeforeAnythingOpens() {
        for (bad in listOf(listOf(Migration(1) {}, Migration(1) {}), listOf(Migration(0) {}))) {
            val error = runCatching { runBlocking { TalaDB.open(file, migrations = bad) } }.exceptionOrNull()
            assertTrue("expected IllegalArgumentException, got $error", error is IllegalArgumentException)
        }
        assertFalse("validation must happen before the file is created", file.exists())
    }
}
