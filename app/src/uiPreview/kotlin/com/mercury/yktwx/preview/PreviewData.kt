package com.mercury.yktwx.preview

import com.mercury.yktwx.*
import com.mercury.yktwx.data.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

// Compiled exclusively into debug and preview builds.
internal object PreviewData {
    fun paymentState(lost: Boolean): PaymentUiState {
        val account = PaymentAccount("demo", "000", "1", "统一账户", 18650)
        return PaymentUiState(needsLogin = false, username = "界面预览", account = account, preview = true,
            code = if (lost) null else "00000000000000000000", secondsLeft = if (lost) 0 else 60,
            problem = if (lost) PaymentCodeProblem.Lost else null)
    }

    fun myState(): MyUiState = MyUiState(
        PersonalProfile("示例同学", "202600000001", "示例部门", "示例专业", null),
        summary = BillSummary(20000, 20540), counts = BillCounts(2, 6),
        homeIdentity = HomeCardIdentity("示例同学", "202600000001", "示例书院", "示例专业"), loaded = true)

    fun rechargeConfiguration(): RechargeConfiguration = RechargeConfiguration(
        "demo", "demo", listOf(1000, 5000, 10000), 1, 50000, true, false)

    fun recipientConfiguration(recipient: RechargeRecipient): RechargeConfiguration = RechargeConfiguration(
        "demo", recipient.targetId, listOf(1000, 5000, 10000), 1, 50000, true, false, recipient)

    fun recipients(): List<RechargeRecipient> = listOf(
        RechargeRecipient("demo-recipient", "demo-target", "示例同学", "202600000002", "统一账户"))

    fun cardConfiguration(targetAccount: String?): CardServiceConfiguration = CardServiceConfiguration(listOf(
        CampusCard(targetAccount ?: "demo-card", "校园卡", "202600000001", targetAccount != null, false, 0)), false, true)

    fun passwordConfiguration(): CardPasswordConfiguration = CardPasswordConfiguration(listOf(
        CampusCard("demo-card", "校园卡", "202600000001", false, false, 0)))

    fun feeConfiguration(project: FeeProject): FeeConfiguration = FeeConfiguration(project,
        RechargeConfiguration(project.id, "", if (project.network) listOf(1500, 3000, 4500) else listOf(1000, 5000, 10000),
            1, 200000, true, false), roomBindingEnabled = !project.network && project != FeeProject.Commercial)

    fun hallState(multiple: Boolean = false): DormUiState {
        val levels = listOf(FeeLevel("campus", 1, "校区"), FeeLevel("building", 2, "楼栋"), FeeLevel("floor", 3, "楼层"), FeeLevel("room", 4, "电表"))
        fun demo(project: FeeProject, letter: String, light: String, air: String): DormRoom {
            fun binding(kind: DormMeterKind) = FeeRoomBinding.from(levels,
                mapOf("campus" to "demo-campus", "building" to if (project == FeeProject.Wenbei) "demo-building-$letter-${kind.name}" else "demo-building-$letter",
                    "floor" to if (project == FeeProject.Wenbei) "demo-floor-${kind.name}" else "demo-floor", "room" to "demo-$letter-${kind.name}"),
                mapOf("campus" to project.label.removeSuffix("电费"), "building" to "示例${letter}栋" + if (project == FeeProject.Wenbei) kind.label else "",
                    "floor" to "示例楼层", "room" to "示例${letter}栋-房间${letter}${kind.label}"))!!
            return DormRoom(project, binding(DormMeterKind.Lighting), listOf(
                DormMeter(DormMeterKind.Lighting, binding(DormMeterKind.Lighting), FeeQuery(thirdParty = "{}", display = listOf("剩余电量" to "$light 度"))),
                DormMeter(DormMeterKind.AirConditioning, binding(DormMeterKind.AirConditioning), FeeQuery(thirdParty = "{}", display = listOf("剩余电量" to "$air 度"))),
            ), System.currentTimeMillis())
        }
        val rooms = listOf(demo(FeeProject.Zhengdong, "A", "86.4", "32.8")) +
            if (multiple) listOf(demo(FeeProject.Wenbei, "B", "58.2", "21.6")) else emptyList()
        return DormUiState(rooms, loaded = true, network = FeeQuery(thirdParty = "{}", display = listOf("用户套餐" to "学生宽带套餐", "用户状态" to "正常")),
            bindingProjects = setOf(FeeProject.Zhengdong, FeeProject.Wenbei, FeeProject.FamilyElectric, FeeProject.FamilyWater))
    }
    fun feeQuery(project: FeeProject, detail: Boolean, level: Int,
        selections: Map<String, String>): FeeQuery {
        if (detail) {
            val roomId = selections["room"].orEmpty()
            val display = when {
                project.network -> listOf("用户套餐" to "学生宽带套餐", "用户状态" to "正常")
                project == FeeProject.FamilyWater -> listOf("剩余水量(吨)" to "28.60")
                project == FeeProject.Commercial -> listOf("剩余水电量" to "156.80")
                else -> listOf("剩余电量" to (if (roomId.endsWith("AirConditioning")) "32.8 度" else "86.4 度"))
            }
            return FeeQuery(thirdParty = "{}", display = display)
        }
        val levels = if (project.network) emptyList() else listOf(FeeLevel("campus", 1, "校区"),
            FeeLevel("building", 2, "楼栋"), FeeLevel("floor", 3, "楼层"), FeeLevel("room", 4, "房间"))
        val buildingId = selections["building"].orEmpty()
        val letter = buildingId.removePrefix("demo-building-").substringBefore('-').ifEmpty { "A" }
        val wenbeiKind = if (buildingId.endsWith("AirConditioning")) DormMeterKind.AirConditioning else DormMeterKind.Lighting
        val options = when (level) {
            0 -> listOf(FeeOption("demo-campus", if (project in setOf(FeeProject.FamilyElectric, FeeProject.FamilyWater))
                "家属区" else project.label.removeSuffix("电费")))
            1 -> if (project == FeeProject.Wenbei) listOf("A", "B", "C").flatMap { letter ->
                listOf(DormMeterKind.Lighting, DormMeterKind.AirConditioning).map { kind ->
                    FeeOption("demo-building-$letter-${kind.name}", "示例${letter}栋${kind.label}")
                }
            } else listOf("A", "B", "C").map { FeeOption("demo-building-$it", "示例${it}栋") }
            2 -> listOf(FeeOption(if (project == FeeProject.Wenbei) "demo-floor-${wenbeiKind.name}" else "demo-floor", "示例楼层"))
            else -> if (project == FeeProject.Wenbei) listOf("A", "B").map { room ->
                FeeOption("demo-$room-${wenbeiKind.name}", "示例${letter}栋-房间${room}${wenbeiKind.label}")
            } else if (project == FeeProject.Zhengdong)
                listOf("A", "B").flatMap { room -> listOf(DormMeterKind.Lighting, DormMeterKind.AirConditioning).map { kind ->
                    FeeOption("demo-$room-${kind.name}", "示例${letter}栋-房间${room}${kind.label}")
                } }
                else listOf(FeeOption("demo-$letter-Other", "房间$letter"))
        }
        return FeeQuery(levels = levels, options = options)
    }
    fun billState(
        period: BillDateFilter = BillDateFilter.currentMonth(),
        statsType: Int = 2,
    ): BillUiState {
        val records = billRecords(period)
        return BillUiState(
            preview = true, needsLogin = false, dateFilter = period, items = records,
            summary = BillSummary(20000, 20540),
            counts = BillCounts(records.count { it.income }, records.count { !it.income }),
            currentPage = 1, pages = 1,
            types = listOf(
                BillType("1", "消费"), BillType("2", "充值"),
                BillType("3", "退款"), BillType("4", "二维码支付"),
            ),
            loaded = true, stats = billStats(period, statsType),
        )
    }

    fun billStats(period: BillDateFilter, type: Int): BillStatsState {
        val records = billRecords(period)
        val daily = when (period.mode) {
            BillDateMode.Month -> YearMonth.from(period.start).days().map { day ->
                val amount = when {
                    type == 2 && day.dayOfMonth % 5 == 0 -> 1800L
                    type == 2 && day.dayOfMonth % 3 == 0 -> 640L
                    type == 1 && day.dayOfMonth == 1 -> 20000L
                    else -> 0L
                }
                BillDailyPoint(day, amount)
            }
            BillDateMode.Year -> (1..12).map { monthValue ->
                val month = YearMonth.of(period.start.year, monthValue)
                val amount = when {
                    type == 2 && monthValue % 3 == 0 -> 8400L
                    type == 1 -> 12000L
                    else -> 0L
                }
                BillDailyPoint(month.atDay(1), amount)
            }
            BillDateMode.Day, BillDateMode.Range -> emptyList()
        }
        val categories = if (type == 2) listOf(
            BillCategory("二维码支付", 15000),
            BillCategory("消费", 4000),
            BillCategory("退款", 1540),
        ) else listOf(
            BillCategory("充值", 18000),
            BillCategory("退款", 2000),
        )
        val ranking = records.filter { (it.typeFrom == "1") == (type == 1) }.sortedByDescending { it.amountCents }.take(5)
        return BillStatsState(
            period = period, type = type, daily = daily, categories = categories, ranking = ranking, loaded = true,
        )
    }

    private fun billRecords(period: BillDateFilter): List<BillRecord> {
        val today = LocalDate.now()
        val anchor = minOf(today, period.end).coerceAtLeast(period.start)
        fun date(offset: Long): LocalDate = anchor.minusDays(offset).coerceIn(period.start, period.end)
        fun time(day: LocalDate, hour: Int, minute: Int): String =
            LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).format(BILL_TIME)
        fun record(
            id: String, resume: String, turnover: String, icon: String, typeId: String, typeFrom: String,
            amount: Long, day: LocalDate, hour: Int, minute: Int, payName: String, merchant: String, remark: String,
        ) = BillRecord(
            orderId = id, resume = resume, payName = payName, turnoverType = turnover, icon = icon,
            typeId = typeId, typeFrom = typeFrom, amountCents = amount, balanceCents = 8236,
            feeCents = 0, effectTime = time(day, hour, minute), bookTime = time(day, hour, minute + 1),
            fromAccount = "校园卡", position = "POS-01", remark = remark, merchant = merchant,
            serialNumber = "20260001", labelName = "",
        )
        val d0 = date(0); val d1 = date(1); val d2 = date(2); val d3 = date(3); val d4 = date(4); val d5 = date(5)
        return listOf(
            record("DEMO-01", "第一餐厅一楼-扫码支付", "二维码支付", "qrCode-payment", "4", "2", 800, d0, 12, 5, "扫码支付", "第一餐厅一楼", "[reserve=DEMO]"),
            record("DEMO-02", "第二餐厅二楼-扫码支付", "二维码支付", "qrCode-payment", "4", "2", 800, d0, 11, 42, "扫码支付", "第二餐厅二楼", "[reserve=DEMO]"),
            record("DEMO-03", "校园超市-扫码支付", "二维码支付", "qrCode-payment", "4", "2", 1200, d1, 18, 20, "扫码支付", "校园超市", "[reserve=DEMO]"),
            record("DEMO-04", "第一餐厅一楼-扫码支付", "二维码支付", "qrCode-payment", "4", "2", 1400, d1, 12, 3, "扫码支付", "第一餐厅一楼", "[reserve=DEMO]"),
            record("DEMO-05", "浴室热水-扫码支付", "二维码支付", "qrCode-payment", "4", "2", 940, d2, 21, 16, "扫码支付", "公共浴室", "[reserve=DEMO]"),
            record("DEMO-06", "校园卡充值", "充值", "recharge", "2", "1", 20000, d3, 9, 0, "校园卡充值", "一卡通中心", "充值"),
            record("DEMO-07", "第三餐厅一楼-扫码支付", "二维码支付", "qrCode-payment", "4", "2", 500, d4, 17, 35, "扫码支付", "第三餐厅一楼", "[reserve=DEMO]"),
            record("DEMO-08", "退款", "退款", "refund", "3", "1", 300, d5, 10, 10, "扫码支付", "校园超市", "退款"),
        )
    }

    private fun YearMonth.days(): List<LocalDate> = (1..lengthOfMonth()).map { atDay(it) }

    private val BILL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
}
