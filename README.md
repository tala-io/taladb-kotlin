<picture>
  <source media="(prefers-color-scheme: dark)" srcset=".github/assets/tala-db-dark.png">
  <img src=".github/assets/tala-db.png" alt="TalaDB" width="240">
</picture>

# TalaDB for Android

Kotlin bindings for [TalaDB](https://github.com/taladb/taladb), an embedded
document and vector database, for native Android apps that do not use React
Native.

Documents, MongoDB-style filters and updates, secondary and compound indexes,
full-text search, vector search and hybrid search, with optional encryption at
rest. Everything runs on the device in a single file.

## Install

```kotlin
dependencies {
    implementation("dev.taladb:taladb-android:0.1.0")
}
```

Requires `minSdk` 24. The AAR ships `arm64-v8a`, `armeabi-v7a` and `x86_64`,
all 16 KB page aligned, as Google Play requires for apps targeting Android 15+.

**Set your app's ABIs to match.** If any other dependency ships a library for
an ABI TalaDB does not — AndroidX's graphics library adds 32-bit `x86`, for
one — Android may install that ABI's directory and find no TalaDB library in
it. Pin the app to the three TalaDB ships:

```kotlin
android {
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
}
```

The build may print `Unable to strip the following libraries … libtaladb_ffi.so,
libtaladb_jni.so`. It is harmless: the AAR's libraries are already stripped,
and AGP says so whenever an app has no NDK configured to strip them again.
Your project also needs the kotlinx.serialization plugin to use typed
collections:

```kotlin
plugins {
    id("org.jetbrains.kotlin.plugin.serialization") version "<your Kotlin version>"
}
```

## Use

```kotlin
@Serializable
data class Note(
    @SerialName("_id") val id: String? = null,
    val title: String,
    val tags: List<String> = emptyList(),
    val embedding: List<Float> = emptyList(),
)

val db = TalaDB.open(context.filesDir.resolve("app.db"))   // keep one per process
val notes = db.collection<Note>("notes")

val id = notes.insert(Note(title = "Groceries", tags = listOf("home")))

val homeNotes = notes.find(buildJsonObject { put("tags", "home") })
notes.updateOne(
    buildJsonObject { put("_id", id) },
    buildJsonObject { putJsonObject("\$set") { put("title", "Weekly groceries") } },
)

// Vector search over embeddings from any on-device model
notes.createVectorIndex("embedding", dimensions = 384)
val similar = notes.findNearest("embedding", queryVector, topK = 5)

// Full-text and hybrid search
notes.createFtsIndex("title")
val hits = notes.hybridSearch("title", "groceries", "embedding", queryVector, topK = 5)
```

Every operation is a `suspend` function that runs on `Dispatchers.IO` (or the
dispatcher you pass to `open`), so it is safe to call from the main thread. One
`TalaDB` can be shared freely across threads and coroutines.

### Live queries

`watch` returns a `Flow` that emits the current result, then a fresh one after
every write that changes it. Rapid writes coalesce into one emission, and
nothing is missed:

```kotlin
notes.watch(buildJsonObject { put("done", false) })
    .collect { open -> render(open) }
```

Each collector holds its own subscription, which closes when the collector is
cancelled. Closing the database ends every collection.

### Projection

`find` and `watch` take a `Projection` to return only some fields. It matters
most for live queries: every write sends a fresh result across, so leaving out
a large field the screen does not show — an embedding — keeps each update
small. The excluded field is never copied out of the engine.

```kotlin
notes.watch(projection = Projection.exclude("embedding")).collect { render(it) }
notes.find(projection = Projection.include("title", "tags"))
```

For a typed collection, give every field a projection can remove a default
(`val embedding: List<Float>? = null`), or decoding fails. `_id` is always
returned.

### Full-text search and stopwords

`searchText` and the text side of `hybridSearch` rank with BM25 and OR
semantics. Common English words ("to", "the", "and", …) are dropped from the
query, so "kind to a classmate" does not match every document containing "to";
a query made only of such words is searched as typed. Turn it off with
`Bm25Options(stopwords = false)` — documents are indexed in full either way, so
the switch needs no reindex.

### Migrations

```kotlin
val db = TalaDB.open(file, migrations = listOf(
    Migration(1, "Index users by email") { db -> db.collection("users").createIndex("email") },
    Migration(2, "Default role") { db ->
        db.collection("users").updateMany(
            buildJsonObject { putJsonObject("role") { put("\$exists", false) } },
            buildJsonObject { putJsonObject("\$set") { put("role", "user") } },
        )
    },
))
```

Pending migrations run in version order at open. The stored version advances
after each one, so a failure resumes from the failed migration on the next
open. Write migrations so they are safe to run again.

### Vector search, in depth

`findNearest` covers the common case. `searchVectors` adds exact or approximate
mode, `efSearch`, score thresholds, pagination and grouping, and reports how the
query ran. For large collections, build an HNSW graph in batches without
blocking the app:

```kotlin
notes.rebuildVectorIndex("embedding", HnswOptions(m = 16)) { progress ->
    showProgress(progress.processed, progress.total)
}
val result = notes.searchVectors("embedding", query, topK = 10,
    options = VectorQueryOptions(efSearch = 128))
println(result.execution.path)          // "hnsw" or "exact", and why in .reason
```

`measureVectorRecall` checks approximate results against exact search on your
own query embeddings. Use it to tune `efSearch` and the graph options.

- **Filters, updates and pipelines** use TalaDB's JSON operators — `$eq`, `$gt`,
  `$in`, `$contains`, `$set`, `$inc`, `$push`, `$group`, … — documented in the
  [engine docs](https://taladb.dev). Build them with `buildJsonObject`.
- **`_id`**: every document has one. Leave it `null` on insert and the engine
  assigns a ULID. An `_id` you supply must be a ULID too; for a natural key —
  a SKU, a server's id, a singleton settings document — use
  `deriveDocId(collection, key)`, which always maps the same key to the same
  ULID (the same function as the engine's and the JavaScript clients').
- **Untyped access**: `db.collection("name")` works with raw `JsonObject`s.
- **Encryption**: `TalaDB.open(file, TalaDBConfig(passphrase = key))`. The
  config's `toString()` never prints the passphrase.
- **Errors**: the engine's errors throw `TalaDBException`, whose `code` is the
  engine's stable error code — the same strings TalaDB's JavaScript clients
  expose as `error.code` — so branch on that, not the message. A wrong
  passphrase is `TalaDBException.ENCRYPTION`; others include `INVALID_FILTER`,
  `DUPLICATE_ID`, `INDEX_NOT_FOUND` and `STORAGE`. A call after `close()`
  throws `IllegalStateException`.

  ```kotlin
  try {
      TalaDB.open(file, TalaDBConfig(passphrase = typed))
  } catch (e: TalaDBException) {
      if (e.code == TalaDBException.ENCRYPTION) showWrongPassphrase() else throw e
  }
  ```
- **Closing**: `close()` waits for running operations and is idempotent.

## API reference

`./gradlew :taladb:dokkaGeneratePublicationHtml` writes the API docs to
`taladb/build/dokka/html`. They also ship in the javadoc jar on Maven Central,
and `docs.yml` publishes them to GitHub Pages from `main`.

## How it works

```
Kotlin API (TalaDB, TalaCollection)        dev.taladb, this repo
  └─ Native (JNI)  ──►  libtaladb_jni.so    src/main/cpp/taladb_jni.c, this repo
                          └─►  libtaladb_ffi.so   the engine's C FFI, from taladb/taladb
```

The JNI shim is small on purpose. Most operations go through the engine's
`taladb_call(op, args_json)`, so a new operation rarely touches C. Only
vector calls pass a `FloatArray` directly. Strings cross JNI as UTF-8 byte
arrays rather than `jstring`, because JNI's modified UTF-8 breaks every emoji.

The engine library is not built here. `engine.properties` pins the engine
release whose `taladb-ffi-android-<version>.zip` gets packed into the AAR,
verified by SHA-256. At load time the shim checks that the library's C ABI
version matches the header it was compiled against, so an engine/package
mismatch fails with a clear error instead of corrupting memory.

## Development

You need JDK 17, the Android SDK with NDK `30.0.16248370` and CMake 3.22.1,
and Rust with `cargo-ndk` to build the engine.

```sh
# 1. Stage the engine in engine/ — from a checkout of taladb/taladb...
scripts/build-engine.sh ../taladb
#    ...or download the release pinned in engine.properties (Android libraries only)
scripts/fetch-engine.sh

# 2. Unit tests run on the host JVM with a host build of the engine — no device
./gradlew :taladb:testDebugUnitTest

# 3. The same suite plus a smoke test on a connected device or emulator
./gradlew :taladb:connectedDebugAndroidTest

# 4. Build the AAR and check its libraries will load on a device
./gradlew :taladb:assembleRelease && scripts/check-aar.sh
```

## License

MIT or Apache-2.0, at your option.
