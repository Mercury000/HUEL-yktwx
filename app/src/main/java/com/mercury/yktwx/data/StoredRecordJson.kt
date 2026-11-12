package com.mercury.yktwx.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Historical storage rules live here; retirement conditions: docs/json-storage-compatibility.md. */
internal object StoredRecordJson {
    const val Version = 2

    fun readable(record: JsonObject): JsonObject {
        // Released 1.4.1 records have no schemaVersion. Intermediate unreleased formats need no reader.
        require(!record.containsKey("schemaVersion") || record.strictLongValue("schemaVersion") == Version.toLong()) {
            "不支持的本地记录版本"
        }
        return record
    }

    fun singleId(record: JsonObject, kind: String): String {
        readable(record)
        val id = record.stringOrDefault("sessionId").trim()
        if (id.isNotBlank()) return id
        require(!record.containsKey("schemaVersion")) { "本地记录缺少标识" }
        // Before sessionId existed, one record per device kind was stored. Never generate a new ID on read.
        val order = record.stringOrDefault("orderNo").trim()
        return "legacy-$kind-$order-${record.stringValue("skuId")}"
    }

    fun single(record: JsonObject, kind: String): JsonObject = record.withField("sessionId", singleId(record, kind))

    fun listSource(current: JsonObject?, oldSingle: () -> JsonObject?, kind: String): JsonObject? {
        if (current != null) return readable(current) // An empty current list is authoritative.
        return runCatching {
            oldSingle()?.let { record -> jsonObjectOf("owner" to record.stringValue("owner"),
                "sessions" to jsonArrayOf(listOf(single(record, kind)))) }
        }.getOrNull()
    }

    fun updated(record: JsonObject): JsonObject = readable(record).withField("schemaVersion", Version)

    inline fun <reified T> decode(record: JsonObject): T = AppJson.decodeFromJsonElement(readable(record))
    inline fun <reified T> encode(value: T): JsonObject =
        AppJson.encodeToJsonElement(value).jsonObject.withField("schemaVersion", Version)
}

@Serializable internal data class StoredCredentials(val username: String = "", val password: String = "") {
    override fun toString() = "StoredCredentials([redacted])"
}

@Serializable internal data class StoredSession(val token: String = "", val expires: Long = 0) {
    override fun toString() = "StoredSession([redacted])"
}
