package com.mercury.yktwx.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.mercury.yktwx.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
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
        require(revision > 0 && digest.matches(Regex("[a-f0-9]{64}")))
        require(versionCode > 0 && forcedBoundary in 0 until versionCode && signedManifest.isNotBlank())
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
        require(writeRevision >= 0)
        require(schemaVersion == 1 && updateChannel in setOf("stable", "beta"))
        require(ui.themeMode in 0..2 && ui.accentMode in 0..1 && ui.floatingBottomBarStyle in 0..1 && ui.navTransitionStyle in 0..1)
        policies.values.forEach(AcceptedUpdatePolicy::validate)
        return this
    }
    override fun toString() = "AppPreferences(schemaVersion=$schemaVersion)"
}

/** Corruption remains a read failure; forced policy must never be replaced with empty defaults. */
internal object AppPreferencesSerializer : Serializer<AppPreferences> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    override val defaultValue = AppPreferences()
    override suspend fun readFrom(input: InputStream): AppPreferences = try {
        val record = json.parseToJsonElement(input.readBytes().decodeToString()).jsonObject
        require(record.keys.containsAll(setOf("schemaVersion", "legacyMigrated", "writeRevision", "ui", "roomSelections",
            "updateChannel", "reminders", "skippedVersions", "policies"))) { "设置文件缺少持久字段" }
        json.decodeFromJsonElement<AppPreferences>(record).validate()
    } catch (error: SerializationException) {
        throw CorruptionException("设置文件格式异常", error)
    } catch (error: IllegalArgumentException) {
        throw CorruptionException("设置或更新策略无效", error)
    }
    override suspend fun writeTo(t: AppPreferences, output: OutputStream) {
        output.write(json.encodeToString(t.validate()).encodeToByteArray())
    }
}
