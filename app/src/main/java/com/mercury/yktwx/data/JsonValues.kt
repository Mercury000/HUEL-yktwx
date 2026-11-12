package com.mercury.yktwx.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Unknown service fields are harmless; malformed JSON and missing required values are not. */
internal val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

internal fun parseObject(source: String): JsonObject = AppJson.parseToJsonElement(source) as? JsonObject
    ?: throw SerializationException("Expected a JSON object")

internal fun parseArray(source: String): JsonArray = AppJson.parseToJsonElement(source) as? JsonArray
    ?: throw SerializationException("Expected a JSON array")

/** Request fields contain only primitives or library JSON values, never arbitrary domain objects. */
internal fun jsonObjectOf(vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
    fields.forEach { (key, value) -> if (value != null) put(key, jsonValue(value)) }
}

internal fun jsonArrayOf(values: Iterable<Any?> = emptyList()): JsonArray = JsonArray(values.map(::jsonValue))
internal fun jsonArrayOf(source: String): JsonArray = parseArray(source)

/** Existing Android identities escaped every slash. Keep their bytes stable across the migration. */
internal fun stableIdentityArray(values: Iterable<Any?>): String = jsonArrayOf(values).toString().replace("/", "\\/")

private fun jsonValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> {
        require(value !is Double || value.isFinite())
        require(value !is Float || value.isFinite())
        JsonPrimitive(value)
    }
    else -> throw SerializationException("Unsupported JSON field type")
}

internal fun JsonObject.withField(key: String, value: Any?): JsonObject = JsonObject(toMutableMap().apply {
    if (value == null) remove(key) else put(key, jsonValue(value))
})
internal fun JsonArray.withValue(value: Any?): JsonArray = JsonArray(this + jsonValue(value))

/** Preserve the observed service distinction between quoted numbers, numbers, booleans and null. */
internal fun JsonObject.scalarOrElement(key: String): Any? = this[key]?.scalarOrElement()
internal fun JsonArray.scalarOrElement(index: Int): Any? = getOrNull(index)?.scalarOrElement()
private fun JsonElement.scalarOrElement(): Any = when {
    this === JsonNull -> JsonNull
    this !is JsonPrimitive -> this
    isString -> content
    booleanOrNull != null -> boolean
    longOrNull != null -> long.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
    doubleOrNull != null -> double
    else -> throw SerializationException("Invalid JSON primitive")
}

internal fun JsonObject.isNull(key: String): Boolean = this[key] == null || this[key] === JsonNull
internal fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonArray.objectOrNull(index: Int): JsonObject? = getOrNull(index) as? JsonObject
internal fun JsonObject.objectValue(key: String): JsonObject = objectOrNull(key)
    ?: throw SerializationException("Expected a JSON object field")
internal fun JsonArray.objectValue(index: Int): JsonObject = objectOrNull(index)
    ?: throw SerializationException("Expected a JSON object item")
internal fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.arrayValue(key: String): JsonArray = arrayOrNull(key)
    ?: throw SerializationException("Expected a JSON array field")
internal fun JsonArray.arrayValue(index: Int): JsonArray = getOrNull(index) as? JsonArray
    ?: throw SerializationException("Expected a JSON array item")

private fun JsonElement.serviceString(): String = if (this is JsonPrimitive) content else toString()
internal fun JsonObject.stringOrDefault(key: String, default: String = ""): String = this[key]?.serviceString() ?: default
internal fun JsonObject.stringValue(key: String): String = this[key]?.serviceString()
    ?: throw SerializationException("Missing JSON string field")
internal fun JsonArray.stringValue(index: Int): String = getOrNull(index)?.serviceString()
    ?: throw SerializationException("Missing JSON string item")

private fun Any?.serviceNumber(): Number? = when (this) {
    is Number -> this
    is String -> toDoubleOrNull()
    else -> null
}
internal fun JsonObject.intOrDefault(key: String, default: Int = 0): Int = scalarOrElement(key).serviceNumber()?.toInt() ?: default
internal fun JsonObject.longOrDefault(key: String, default: Long = 0): Long = scalarOrElement(key).serviceNumber()?.toLong() ?: default
internal fun JsonObject.doubleOrDefault(key: String, default: Double = Double.NaN): Double = scalarOrElement(key).serviceNumber()?.toDouble() ?: default
internal fun JsonObject.intValue(key: String): Int = scalarOrElement(key).serviceNumber()?.toInt()
    ?: throw SerializationException("Expected a JSON integer field")
internal fun JsonObject.longValue(key: String): Long = scalarOrElement(key).serviceNumber()?.toLong()
    ?: throw SerializationException("Expected a JSON long field")
internal fun JsonObject.strictLongValue(key: String): Long {
    val value = this[key] as? JsonPrimitive
    require(value != null && !value.isString && value.content.matches(Regex("-?(0|[1-9][0-9]*)"))) { "更新字段类型无效" }
    return requireNotNull(value.longOrNull) { "更新字段类型无效" }
}
internal fun JsonArray.intValue(index: Int): Int = scalarOrElement(index).serviceNumber()?.toInt()
    ?: throw SerializationException("Expected a JSON integer item")

private fun Any?.serviceBoolean(): Boolean? = when (this) {
    is Boolean -> this
    is String -> when { equals("true", true) -> true; equals("false", true) -> false; else -> null }
    else -> null
}
internal fun JsonObject.booleanOrDefault(key: String, default: Boolean = false): Boolean = scalarOrElement(key).serviceBoolean() ?: default
internal fun JsonObject.booleanValue(key: String): Boolean = scalarOrElement(key).serviceBoolean()
    ?: throw SerializationException("Expected a JSON boolean field")
