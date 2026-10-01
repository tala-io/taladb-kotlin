# TalaDB for Android

Kotlin bindings for [TalaDB](https://github.com/tala-io/taladb), an embedded
document and vector database, for native Android apps that do not use React
Native.

Documents, MongoDB-style filters and updates, secondary and compound indexes,
full-text search, vector search and hybrid search, with optional encryption at
rest. Everything runs on the device in a single file.

> **Status: pre-release.** It needs engine ABI version 2, which no TalaDB
> release ships yet, so it is not on Maven Central. See [Development](#development)
> to build it from source.

## Install

```kotlin
dependencies {
    implementation("dev.taladb:taladb-android:0.1.0")
}
```

Requires `minSdk` 24. The AAR ships `arm64-v8a`, `armeabi-v7a` and `x86_64`,
all 16 KB page aligned, as Google Play requires for apps targeting Android 15+.
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

- **Filters, updates and pipelines** use TalaDB's JSON operators — `$eq`, `$gt`,
  `$in`, `$contains`, `$set`, `$inc`, `$push`, `$group`, … — documented in the
  [engine docs](https://taladb.dev). Build them with `buildJsonObject`.
- **`_id`**: every document has one. Leave it `null` on insert and the engine
  assigns a ULID.
- **Untyped access**: `db.collection("name")` works with raw `JsonObject`s.
- **Encryption**: `TalaDB.open(file, TalaDBConfig(passphrase = key))`. The
  config's `toString()` never prints the passphrase.
- **Errors**: the engine's errors (bad filter, missing index, wrong
  passphrase, duplicate `_id`) throw `TalaDBException`; a call after `close()`
  throws `IllegalStateException`.
- **Closing**: `close()` waits for running operations and is idempotent.

## How it works

```
Kotlin API (TalaDB, TalaCollection)        dev.taladb, this repo
  └─ Native (JNI)  ──►  libtaladb_jni.so    src/main/cpp/taladb_jni.c, this repo
                          └─►  libtaladb_ffi.so   the engine's C FFI, from tala-io/taladb
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
# 1. Stage the engine in engine/ — from a checkout of tala-io/taladb...
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

## Releasing

1. When the engine publishes a release, its workflow dispatches to this repo
   and `engine-bump.yml` opens a PR pinning it. CI tests the PR against the
   release archive.
2. Tag `vX.Y.Z` on `main`. `publish.yml` builds from the pinned release and
   publishes `dev.taladb:taladb-android:X.Y.Z` to Maven Central. It refuses to
   publish while no engine release is pinned.

Repository secrets: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`,
`SIGNING_KEY`, `SIGNING_KEY_PASSWORD`. The engine repository needs
`NATIVE_PACKAGES_DISPATCH_TOKEN` to send the release notification.

## License

MIT or Apache-2.0, at your option.
