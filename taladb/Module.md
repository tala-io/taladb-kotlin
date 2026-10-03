# Module taladb-android

Kotlin bindings for [TalaDB](https://github.com/taladb/taladb), an embedded
document and vector database. Everything runs on the device, in one file.

Start with [dev.taladb.TalaDB.Companion.open], then
[dev.taladb.TalaDB.collection] for a [dev.taladb.TalaCollection].

```kotlin
@Serializable
data class Note(@SerialName("_id") val id: String? = null, val title: String)

val db = TalaDB.open(context.filesDir.resolve("app.db"))
val notes = db.collection<Note>("notes")
notes.insert(Note(title = "Groceries"))
notes.watch().collect { all -> render(all) }
```

Every operation is a `suspend` function that runs on the dispatcher given to
`open` (`Dispatchers.IO` by default), so it is safe to call from the main
thread. Filters, updates and aggregation pipelines use TalaDB's JSON operators,
built with kotlinx.serialization's `buildJsonObject`.

# Package dev.taladb

The database, its collections, live queries, migrations, and full-text and
vector search.
