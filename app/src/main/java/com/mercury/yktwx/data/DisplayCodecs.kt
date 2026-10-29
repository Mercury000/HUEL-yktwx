package com.mercury.yktwx.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal object DisplayKeys {
    const val Account = "account"
    const val Profile = "profile"
    fun identity(sno: String) = "identity:$sno"
    fun avatar(sno: String, path: String) = "avatar:" + JSONArray(listOf(sno, path)).toString()
    fun room(project: FeeProject) = "room:${project.id}"
    const val Network = "network"
    fun summary(from: String, to: String) = "summary:$from:$to"
    fun counts(from: String, to: String) = "counts:$from:$to"
    fun bills(date: BillDateFilter, type: String?, from: Long?, to: Long?, info: String) =
        "bills:" + JSONArray(listOf(date.timeFrom, date.timeTo, type, from, to, info)).toString()
    fun stats(date: BillDateFilter, type: Int, part: String) = "stats:${date.timeFrom}:${date.timeTo}:$type:$part"
}

private fun <T> codec(encode: (T) -> JSONObject, decode: (JSONObject) -> T) = object : DisplayCodec<T> {
    override fun encode(value: T) = encode(value)
    override fun decode(json: JSONObject) = decode(json)
}
private fun JSONObject.strings(key: String): List<String> = getJSONArray(key).let { a -> (0 until a.length()).map(a::getString) }
private fun <T> listCodec(item: DisplayCodec<T>) = codec<List<T>>(
    { JSONObject().put("items", JSONArray(it.map(item::encode))) },
    { it.getJSONArray("items").let { a -> (0 until a.length()).map { i -> item.decode(a.getJSONObject(i)) } } })

/** Explicit display-only formats; adding a server field cannot accidentally persist secrets. */
internal object DisplayCodecs {
    val Avatar = codec<ByteArray>(
        { require(it.size <= 256 * 1024); JSONObject().put("bytes", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)) },
        { android.util.Base64.decode(it.getString("bytes"), android.util.Base64.NO_WRAP).also { bytes -> require(bytes.size <= 256 * 1024) } })
    val Account = codec<PaymentAccount>(
        { JSONObject().put("s", JSONArray(listOf(it.account, it.payacc, it.paytype, it.name))).put("balance", it.balanceCents) },
        { j -> j.strings("s").let { PaymentAccount(it[0], it[1], it[2], it[3], j.getLong("balance")) } })
    val Profile = codec<PersonalProfile>(
        { JSONObject().put("s", JSONArray(listOf(it.name, it.sno, it.departmentName, it.professionName, it.avatarPath.orEmpty(), it.mobile))) },
        { it.strings("s").let { s -> PersonalProfile(s[0], s[1], s[2], s[3], s[4].ifBlank { null }, s[5]) } })
    val Identity = codec<HomeCardIdentity?>(
        { JSONObject().put("s", it?.let { v -> JSONArray(listOf(v.name, v.sno, v.thirdLevelDepartment, v.fourthLevelDepartment)) } ?: JSONObject.NULL) },
        { if (it.isNull("s")) null else it.strings("s").let { s -> HomeCardIdentity(s[0], s[1], s[2], s[3]) } })
    val Summary = codec<BillSummary>(
        { JSONObject().put("income", it.incomeCents).put("expenses", it.expensesCents) },
        { BillSummary(it.getLong("income"), it.getLong("expenses")) })
    val Counts = codec<BillCounts>(
        { JSONObject().put("income", it.income).put("expenses", it.expenses) },
        { BillCounts(it.getInt("income"), it.getInt("expenses")) })
    val Record = codec<BillRecord>(
        { r -> JSONObject().put("s", JSONArray(listOf(r.orderId, r.resume, r.payName, r.turnoverType, r.icon,
            r.typeId, r.typeFrom, r.effectTime, r.bookTime, r.fromAccount, r.position, r.remark, r.merchant, r.serialNumber, r.labelName)))
            .put("amount", r.amountCents).put("balance", r.balanceCents).put("fee", r.feeCents) },
        { j -> j.strings("s").let { s -> BillRecord(s[0], s[1], s[2], s[3], s[4], s[5], s[6],
            j.getLong("amount"), j.getLong("balance"), j.getLong("fee"), s[7], s[8], s[9], s[10], s[11], s[12], s[13], s[14]) } })
    val Records = listCodec(Record)
    val Page = codec<BillPageData>(
        { Records.encode(it.items).put("current", it.current).put("pages", it.pages) },
        { BillPageData(Records.decode(it), it.getInt("current"), it.getInt("pages")) })
    val Daily = listCodec(codec<BillDailyPoint>(
        { JSONObject().put("date", it.date.toString()).put("amount", it.amountCents) },
        { BillDailyPoint(LocalDate.parse(it.getString("date")), it.getLong("amount")) }))
    val Categories = listCodec(codec<BillCategory>(
        { JSONObject().put("name", it.name).put("amount", it.amountCents) },
        { BillCategory(it.getString("name"), it.getLong("amount")) }))
    // Restored queries contain display values only and never authorize a payment.
    val Query = codec<FeeQuery>(
        { JSONObject().put("display", JSONArray(it.display.map { p -> JSONArray(listOf(p.first, p.second)) })) },
        { it.getJSONArray("display").let { a -> FeeQuery(display = (0 until a.length()).map { i ->
            a.getJSONArray(i).let { p -> p.getString(0) to p.getString(1) } }, canPay = false) } })
    val Room = codec<DormRoom>(
        { r -> JSONObject().put("project", r.project.name).put("binding", r.binding.sceneInfo())
            .put("updated", r.updatedAtMillis).put("notice", r.notice ?: JSONObject.NULL)
            .put("meters", JSONArray(r.meters.filter { it.query != null }.map { m ->
                JSONObject().put("kind", m.kind.name).put("binding", m.room.sceneInfo())
                    .put("project", (m.project ?: r.project).name).put("query", Query.encode(m.query!!)) })) },
        { j -> val project = FeeProject.valueOf(j.getString("project"))
            val meters = j.getJSONArray("meters").let { a -> (0 until a.length()).map { i ->
                val m = a.getJSONObject(i)
                DormMeter(DormMeterKind.valueOf(m.getString("kind")), requireNotNull(FeeRoomBinding.parse(m.getString("binding"))),
                    Query.decode(m.getJSONObject("query")), project = FeeProject.valueOf(m.getString("project"))) } }
            DormRoom(project, requireNotNull(FeeRoomBinding.parse(j.getString("binding"))), meters, j.getLong("updated"),
                (j.opt("notice") as? String)) })
}
