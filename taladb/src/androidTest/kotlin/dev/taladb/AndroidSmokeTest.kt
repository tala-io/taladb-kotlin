package dev.taladb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@Serializable
data class Memo(
    @SerialName("_id") val id: String? = null,
    val text: String,
    val embedding: List<Float> = emptyList(),
)

/**
 * The same code the host unit tests cover, on a real Android runtime: proves
 * the AAR's libraries load (ABI, 16 KB alignment, dependency names), that ART's
 * JNI behaves like the host JVM's for the byte-array string path, and that the
 * database works in app storage.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun opensWritesAndSearchesInAppStorage() =
        runTest {
            val file = context.filesDir.resolve("smoke-${System.nanoTime()}.db")
            TalaDB.open(file).use { db ->
                assertEquals(2, TalaDB.abiVersion)
                val memos = db.collection<Memo>("memos")
                memos.createFtsIndex("text")
                memos.createVectorIndex("embedding", dimensions = 2)

                val emoji = "on-device 🚀 日本語"
                memos.insertMany(
                    listOf(
                        Memo(text = emoji, embedding = listOf(1f, 0f)),
                        Memo(text = "plain text", embedding = listOf(0f, 1f)),
                    ),
                )

                assertEquals(emoji, memos.findOne(buildJsonObject { put("text", emoji) })?.text)
                assertEquals(emoji, memos.findNearest("embedding", floatArrayOf(1f, 0.1f), topK = 1).single().document.text)
                assertEquals("plain text", memos.searchText("text", "plain", topK = 1).single().document.text)

                val error = runCatching { memos.dropIndex("missing") }.exceptionOrNull()
                assertTrue("expected TalaDBException, got $error", error is TalaDBException)
            }
            file.delete()
        }
}
