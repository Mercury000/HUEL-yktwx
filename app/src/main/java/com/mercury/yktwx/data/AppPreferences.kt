package com.mercury.yktwx.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.mercury.yktwx.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream

@Serializable internal data class UiPreferences(
    val themeMode: Int = 0, val accentMode: Int = 0,
    val floatingBottomBar: Boolean = false, val floatingBottomBarStyle: Int = 0,
    val blurBottomBar: Boolean = false, val blurTopBar: Boolean = false,
    val swipeBack: Boolean = false, val navTransitionStyle: Int = 0,
    val paymentMotionDetection: Boolean = false, val paymentBrightness: Boolean = true,
    val preventScreenshots: Boolean = true, val automaticUpdates: Boolean = true,
    val pangguiLoginNoticeDismissed: Boolean = false, val hui798LoginNoticeDismissed: Boolean = false,
    val navigationRailExpanded: Boolean? = null,
)

@Serializable internal data class UpdateReminder(
    val checkedAt: Long = 0, val checkInterval: Long = UpdateCheckStore.DAY,
    val dismissedVersion: Int = 0, val dismissedAt: Long = 0,
)

@Serializable internal data class AcceptedUpdatePolicy(
    val revision: Long, val digest: String, val forcedBoundary: Int,
    val versionCode: Int, val signedManifest: String,
    // Local presentation state; the signed policy remains the rollback/forced-update ledger.
    val cachedAvailable: Boolean = true,
) {
    fun validate() {
        requirePreferences(revision > 0, PreferencesFailureKind.Policy, "更新策略修订号必须大于 0（revision）")
        requirePreferences(digest.matches(Regex("[a-f0-9]{64}")), PreferencesFailureKind.Policy,
            "更新策略摘要格式无效（digest）")
        requirePreferences(versionCode > 0, PreferencesFailureKind.Policy, "更新策略版本号必须大于 0（versionCode）")
        requirePreferences(forcedBoundary in 0 until versionCode, PreferencesFailureKind.Policy,
            "强制更新边界必须非负且小于目标版本号（forcedBoundary）")
        requirePreferences(signedManifest.isNotBlank(), PreferencesFailureKind.Policy, "更新策略缺少签名清单（signedManifest）")
    }
    override fun toString() = "AcceptedUpdatePolicy(revision=$revision, versionCode=$versionCode)"
}

@Serializable internal data class AppPreferences(
    val schemaVersion: Int = 1,
    val legacyMigrated: Boolean = false,
    val writeRevision: Long = 0,
    val ui: UiPreferences = UiPreferences(),
    val roomSelections: Map<String, Set<String>> = emptyMap(),
    val updateChannel: String = BuildConfig.UPDATE_CHANNEL,
    val reminders: Map<String, UpdateReminder> = emptyMap(),
    val skippedVersions: Map<String, Int> = emptyMap(),
    val policies: Map<String, AcceptedUpdatePolicy> = emptyMap(),
) {
    fun validate(): AppPreferences {
        requirePreferences(writeRevision >= 0, PreferencesFailureKind.Record, "设置写入序号不能为负数（writeRevision）")
        requirePreferences(schemaVersion == 1, PreferencesFailureKind.Record,
            "设置文件版本不受当前应用支持（schemaVersion=$schemaVersion）")
        requirePreferences(updateChannel in setOf("stable", "beta"), PreferencesFailureKind.Record,
            "保存的更新渠道无效（updateChannel）")
        // Appearance options are extensible. An older renderer falls back in ThemeSettings;
        // it must not reject the signed ledger or erase the option saved by a newer build.
        policies.forEach { (key, policy) ->
            try { policy.validate() }
            catch (error: PreferencesDataException) {
                throw PreferencesDataException(error.failure.copy(detail =
                    "${policyChannelLabel(key)}：${error.failure.detail}"), error)
            }
        }
        return this
    }
    override fun toString() = "AppPreferences(schemaVersion=$schemaVersion)"
}

/** Corruption remains a read failure; forced policy must never be replaced with empty defaults. */
internal object AppPreferencesSerializer : Serializer<AppPreferences> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    override val defaultValue = AppPreferences()
    override suspend fun readFrom(input: InputStream): AppPreferences {
        // Keep IO failures distinct from parsing failures. Never surface a parser's JSON excerpt.
        val bytes = input.readBytes()
        try {
            val element = try { json.parseToJsonElement(bytes.decodeToString()) }
            catch (error: SerializationException) {
                throw PreferencesDataException(PreferencesFailure(PreferencesFailureKind.Format,
                    "本机设置文件不是有效 JSON，内容可能不完整或已损坏"), error)
            }
            val record = element as? JsonObject ?: throw PreferencesDataException(
                PreferencesFailure(PreferencesFailureKind.Format, "本机设置文件顶层必须是 JSON 对象"))
            val required = listOf("schemaVersion", "legacyMigrated", "writeRevision", "ui", "roomSelections",
                "updateChannel", "reminders", "skippedVersions", "policies")
            val missing = required.filter { it !in record }
            requirePreferences(missing.isEmpty(), PreferencesFailureKind.Record,
                "设置文件缺少持久字段：${missing.joinToString("、")}")
            checkFieldTypes(record)
            val decoded = try { json.decodeFromJsonElement<AppPreferences>(record) }
            catch (error: SerializationException) {
                throw PreferencesDataException(PreferencesFailure(PreferencesFailureKind.Format,
                    "设置文件字段结构或类型无效"), error)
            } catch (error: IllegalArgumentException) {
                throw PreferencesDataException(PreferencesFailure(PreferencesFailureKind.Format,
                    "设置文件字段结构或类型无效"), error)
            }
            return decoded.validate()
        } catch (error: PreferencesDataException) {
            throw CorruptionException(error.failure.detail, error)
        }
    }
    override suspend fun writeTo(t: AppPreferences, output: OutputStream) {
        output.write(json.encodeToString(t.validate()).encodeToByteArray())
    }

    private fun checkFieldTypes(record: JsonObject) {
        fun check(value: JsonElement?, path: String, valid: (JsonElement) -> Boolean) {
            if (value != null) requirePreferences(valid(value), PreferencesFailureKind.Format,
                "设置文件字段类型无效（$path）")
        }
        val integer: (JsonElement) -> Boolean = { it is JsonPrimitive && !it.isString && it.intOrNull != null }
        val long: (JsonElement) -> Boolean = { it is JsonPrimitive && !it.isString && it.longOrNull != null }
        val boolean: (JsonElement) -> Boolean = { it is JsonPrimitive && !it.isString && it.booleanOrNull != null }
        check(record["schemaVersion"], "schemaVersion", integer)
        check(record["legacyMigrated"], "legacyMigrated", boolean)
        check(record["writeRevision"], "writeRevision", long)
        check(record["updateChannel"], "updateChannel") { it is JsonPrimitive && it.isString }
        check(record["ui"], "ui") { it is JsonObject }
        val ui = record.getValue("ui").jsonObject
        listOf("themeMode", "accentMode", "floatingBottomBarStyle", "navTransitionStyle").forEach {
            check(ui[it], "ui.$it", integer)
        }
        listOf("floatingBottomBar", "blurBottomBar", "blurTopBar", "glassStyle", "swipeBack", "paymentMotionDetection",
            "paymentBrightness", "preventScreenshots", "automaticUpdates", "pangguiLoginNoticeDismissed",
            "hui798LoginNoticeDismissed", "hellobikeLoginNoticeDismissed").forEach { check(ui[it], "ui.$it", boolean) }
        check(ui["navigationRailExpanded"], "ui.navigationRailExpanded") { it == JsonNull || boolean(it) }
        listOf("roomSelections", "reminders", "skippedVersions", "policies").forEach { name ->
            check(record[name], name) { it is JsonObject }
        }
        record.getValue("roomSelections").jsonObject.values.forEach { selection ->
            check(selection, "roomSelections") { it is JsonArray && it.all { value -> value is JsonPrimitive && value.isString } }
        }
        record.getValue("skippedVersions").jsonObject.values.forEach { check(it, "skippedVersions", integer) }
        record.getValue("reminders").jsonObject.values.forEach { reminder ->
            check(reminder, "reminders") { it is JsonObject }
            val fields = reminder.jsonObject
            listOf("checkedAt", "checkInterval", "dismissedAt").forEach { check(fields[it], "reminders.$it", long) }
            check(fields["dismissedVersion"], "reminders.dismissedVersion", integer)
        }
        record.getValue("policies").jsonObject.values.forEach { policy ->
            check(policy, "policies") { it is JsonObject }
            val fields = policy.jsonObject
            listOf("revision", "digest", "forcedBoundary", "versionCode", "signedManifest").forEach {
                requirePreferences(it in fields, PreferencesFailureKind.Policy, "更新策略缺少字段（policies.$it）")
            }
            check(fields["revision"], "policies.revision", long)
            listOf("forcedBoundary", "versionCode").forEach { check(fields[it], "policies.$it", integer) }
            listOf("digest", "signedManifest").forEach { name ->
                check(fields[name], "policies.$name") { it is JsonPrimitive && it.isString }
            }
            check(fields["cachedAvailable"], "policies.cachedAvailable", boolean)
        }
    }
}
