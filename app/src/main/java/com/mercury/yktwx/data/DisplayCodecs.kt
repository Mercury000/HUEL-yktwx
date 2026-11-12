package com.mercury.yktwx.data

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.LocalDate

internal object DisplayKeys {
    const val Account = "account"
    const val Profile = "profile"
    fun identity(sno: String) = "identity:$sno"
    fun avatar(sno: String, path: String) = "avatar:" + stableIdentityArray(listOf(sno, path))
    fun room(project: FeeProject) = "room:${project.id}"
    const val Network = "network"
    fun summary(from: String, to: String) = "summary:$from:$to"
    fun counts(from: String, to: String) = "counts:$from:$to"
    fun bills(date: BillDateFilter, type: String?, from: Long?, to: Long?, info: String) =
        "bills:" + stableIdentityArray(listOf(date.timeFrom, date.timeTo, type, from, to, info))
    fun stats(date: BillDateFilter, type: Int, part: String) = "stats:${date.timeFrom}:${date.timeTo}:$type:$part"
}

private inline fun <T, reified S> codec(noinline store: (T) -> S, noinline restore: (S) -> T): DisplayCodec<T> =
    object : DisplayCodec<T> {
        override fun encode(value: T): JsonObject = AppJson.encodeToJsonElement(store(value)).jsonObject
        override fun decode(json: JsonObject): T = restore(AppJson.decodeFromJsonElement<S>(json))
    }

private fun <T> listCodec(item: DisplayCodec<T>): DisplayCodec<List<T>> = object : DisplayCodec<List<T>> {
    override fun encode(value: List<T>) = buildJsonObject { put("items", JsonArray(value.map(item::encode))) }
    override fun decode(json: JsonObject) = json.arrayValue("items").map { item.decode(it.jsonObject) }
}

/** Explicit named display-only DTOs; domain/server fields cannot silently enter the cache. */
internal object DisplayCodecs {
    val Avatar = codec<ByteArray, AvatarSnapshot>(
        { require(it.size <= 256 * 1024); AvatarSnapshot(Base64.encodeToString(it, Base64.NO_WRAP)) },
        { Base64.decode(it.bytes, Base64.NO_WRAP).also { bytes -> require(bytes.size <= 256 * 1024) } })
    val Account = codec<PaymentAccount, AccountSnapshot>(
        { AccountSnapshot(it.account, it.payacc, it.paytype, it.name, it.balanceCents) },
        { PaymentAccount(it.account, it.payacc, it.paytype, it.name, it.balanceCents) })
    val Profile = codec<PersonalProfile, ProfileSnapshot>(
        { ProfileSnapshot(it.name, it.sno, it.departmentName, it.professionName, it.avatarPath, it.mobile) },
        { PersonalProfile(it.name, it.sno, it.departmentName, it.professionName, it.avatarPath, it.mobile) })
    val Identity = codec<HomeCardIdentity?, IdentitySnapshot>(
        { IdentitySnapshot(it?.let { v -> IdentityFields(v.name, v.sno, v.thirdLevelDepartment, v.fourthLevelDepartment) }) },
        { it.value?.let { v -> HomeCardIdentity(v.name, v.sno, v.thirdLevelDepartment, v.fourthLevelDepartment) } })
    val Summary = codec<BillSummary, SummarySnapshot>(
        { SummarySnapshot(it.incomeCents, it.expensesCents) }, { BillSummary(it.incomeCents, it.expensesCents) })
    val Counts = codec<BillCounts, CountsSnapshot>(
        { CountsSnapshot(it.income, it.expenses) }, { BillCounts(it.income, it.expenses) })
    val Record = codec<BillRecord, RecordSnapshot>(::recordSnapshot, RecordSnapshot::record)
    val Records = listCodec(Record)
    val Page = codec<BillPageData, PageSnapshot>(
        { PageSnapshot(it.items.map(::recordSnapshot), it.current, it.pages) },
        { BillPageData(it.items.map(RecordSnapshot::record), it.current, it.pages) })
    val Daily = listCodec(codec<BillDailyPoint, DailySnapshot>(
        { DailySnapshot(it.date.toString(), it.amountCents) }, { BillDailyPoint(LocalDate.parse(it.date), it.amountCents) }))
    val Categories = listCodec(codec<BillCategory, CategorySnapshot>(
        { CategorySnapshot(it.name, it.amountCents) }, { BillCategory(it.name, it.amountCents) }))
    // A restored display never provides a payment capability.
    val Query = codec<FeeQuery, QuerySnapshot>(
        { QuerySnapshot(it.display) }, { FeeQuery(display = it.display, canPay = false) })
    val Room = codec<DormRoom, RoomSnapshot>(
        { room -> RoomSnapshot(room.project.name, room.binding.sceneInfo(), room.updatedAtMillis, room.notice,
            room.meters.filter { it.query != null }.map { meter -> MeterSnapshot(meter.kind.name, meter.room.sceneInfo(),
                (meter.project ?: room.project).name, QuerySnapshot(meter.query!!.display)) }) },
        { room -> DormRoom(FeeProject.valueOf(room.project), requireNotNull(FeeRoomBinding.parse(room.binding)),
            room.meters.map { meter -> DormMeter(DormMeterKind.valueOf(meter.kind), requireNotNull(FeeRoomBinding.parse(meter.binding)),
                FeeQuery(display = meter.query.display, canPay = false), project = FeeProject.valueOf(meter.project)) },
            room.updatedAtMillis, room.notice) })
}

@Serializable private data class AvatarSnapshot(val bytes: String)
@Serializable private data class AccountSnapshot(val account: String, val payacc: String, val paytype: String, val name: String, val balanceCents: Long) {
    override fun toString() = "AccountSnapshot([redacted])"
}
@Serializable private data class ProfileSnapshot(val name: String, val sno: String, val departmentName: String, val professionName: String, val avatarPath: String?, val mobile: String) {
    override fun toString() = "ProfileSnapshot([redacted])"
}
@Serializable private data class IdentityFields(val name: String, val sno: String, val thirdLevelDepartment: String, val fourthLevelDepartment: String) {
    override fun toString() = "IdentityFields([redacted])"
}
@Serializable private data class IdentitySnapshot(val value: IdentityFields?)
@Serializable private data class SummarySnapshot(val incomeCents: Long, val expensesCents: Long)
@Serializable private data class CountsSnapshot(val income: Int, val expenses: Int)
@Serializable private data class PageSnapshot(val items: List<RecordSnapshot>, val current: Int, val pages: Int)
@Serializable private data class DailySnapshot(val date: String, val amountCents: Long)
@Serializable private data class CategorySnapshot(val name: String, val amountCents: Long)
@Serializable private data class QuerySnapshot(val display: List<Pair<String, String>>)
@Serializable private data class MeterSnapshot(val kind: String, val binding: String, val project: String, val query: QuerySnapshot)
@Serializable private data class RoomSnapshot(val project: String, val binding: String, val updatedAtMillis: Long, val notice: String?, val meters: List<MeterSnapshot>)
@Serializable private data class RecordSnapshot(
    val orderId: String, val resume: String, val payName: String, val turnoverType: String, val icon: String,
    val typeId: String, val typeFrom: String, val amountCents: Long, val balanceCents: Long, val feeCents: Long,
    val effectTime: String, val bookTime: String, val fromAccount: String, val position: String, val remark: String,
    val merchant: String, val serialNumber: String, val labelName: String,
) {
    fun record() = BillRecord(orderId, resume, payName, turnoverType, icon, typeId, typeFrom, amountCents,
        balanceCents, feeCents, effectTime, bookTime, fromAccount, position, remark, merchant, serialNumber, labelName)
    override fun toString() = "RecordSnapshot([redacted])"
}
private fun recordSnapshot(record: BillRecord) = with(record) {
    RecordSnapshot(orderId, resume, payName, turnoverType, icon, typeId, typeFrom, amountCents, balanceCents,
        feeCents, effectTime, bookTime, fromAccount, position, remark, merchant, serialNumber, labelName)
}
