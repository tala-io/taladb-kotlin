package dev.taladb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The filter that matches every document: `{}`.
 */
public val MatchAll: JsonObject = JsonObject(emptyMap())

/**
 * How documents are encoded on the way in and decoded on the way out.
 *
 * - `ignoreUnknownKeys`: every stored document carries an `_id`, and a model
 *   class need not declare it.
 * - `explicitNulls = false`: a model's `val id: String? = null` mapped to `_id`
 *   is omitted on insert, so the engine assigns one, rather than sent as
 *   `"_id": null`, which it rejects ("_id must be a string").
 * - `encodeDefaults`: a property left at its default is still stored, so a
 *   filter on it matches.
 */
internal val TalaJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
