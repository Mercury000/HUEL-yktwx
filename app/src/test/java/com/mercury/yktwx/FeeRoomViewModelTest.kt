package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class FeeRoomViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val fees = FakeFees()
    private val vault = MemoryVault()
    private val repository = AccountRepository(YktApi(), vault, feeService = fees)
    private val model = FeeViewModel(app, repository, 0L)
    private val store = ViewModelStore().apply { put("rooms", model) }

    @After fun cleanup() {
        fees.writeGate.complete(Unit)
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for room state")
    }
    private fun open(project: FeeProject = FeeProject.Wenbei) {
        model.open(project, vault.username, false)
        await { !model.state.value.loading && model.state.value.config != null }
    }
    private fun selectRoom(index: Int = 0) {
        val state = model.state.value
        model.select(state.levels.last(), state.options.getValue(2)[index])
        await { !model.state.value.selecting && model.state.value.query != null }
    }

    @Test fun unboundCompletedSelectionPromptsAgainAfterCancelAndReopening() {
        open(); selectRoom()
        assertEquals(FeeRoomAction.Save, model.state.value.roomDialog?.action)
        assertEquals(0, fees.writes)
        model.dismissRoomDialog()
        assertNull(model.state.value.roomDialog)
        assertTrue(model.state.value.roomActionEnabled)
        model.requestRoomAction()
        assertNotNull(model.state.value.roomDialog)
        model.dismissRoomDialog()
        selectRoom(1)
        assertNotNull(model.state.value.roomDialog)
        assertEquals(FeeRoomAction.Save, model.state.value.roomAction)
        model.dismiss(); open(); selectRoom()
        assertNotNull(model.state.value.roomDialog)
        assertEquals(0, fees.writes)
    }
    @Test fun refreshingAnUnboundTargetDoesNotReopenCanceledSaveDialog() {
        open(); selectRoom(); model.dismissRoomDialog()
        model.refreshQuery()
        await { !model.state.value.loading }
        assertNull(model.state.value.roomDialog)
        assertTrue(model.state.value.roomActionEnabled)
    }
    @Test fun saveReadsBackAndUnbindKeepsCurrentSelectionAndQueryWithoutPromptingAgain() {
        open(); selectRoom()
        val selections = model.state.value.selections
        val query = model.state.value.query
        model.confirmRoomAction()
        await { !model.state.value.roomBusy && fees.writes == 1 }
        assertEquals(FeeRoomAction.Unbind, model.state.value.roomAction)
        assertEquals("已保存为我的房间", model.state.value.roomNotice)
        assertEquals(FeeProject.Wenbei, fees.writtenProject)
        model.requestRoomAction(); model.confirmRoomAction()
        await { !model.state.value.roomBusy && fees.writes == 2 }
        assertNull(model.state.value.roomBinding)
        assertEquals(selections, model.state.value.selections)
        assertSame(query, model.state.value.query)
        assertNull(model.state.value.roomDialog)
        assertEquals(FeeRoomAction.Save, model.state.value.roomAction)
        assertEquals("已解绑", model.state.value.roomNotice)
        model.refreshQuery()
        await { !model.state.value.loading }
        assertNull(model.state.value.roomDialog)
        selectRoom(1)
        assertNotNull(model.state.value.roomDialog)
    }
    @Test fun savedRoomRestoresAllOptionsAndReselectingNeverPromptsAutomatically() {
        fees.saved = fees.room(0)
        open()
        assertEquals(fees.saved!!.selections, model.state.value.selections)
        assertNotNull(model.state.value.query)
        assertEquals(FeeRoomAction.Unbind, model.state.value.roomAction)
        assertEquals(listOf(0, 1, 2), fees.queryLevels)
        selectRoom(1)
        assertNull(model.state.value.roomDialog)
        assertEquals(FeeRoomAction.Save, model.state.value.roomAction)
        model.requestRoomAction()
        assertTrue(model.state.value.roomDialog!!.previous!!.sameTarget(fees.room(0)))
        assertTrue(model.state.value.roomDialog!!.target.sameTarget(fees.room(1)))
        model.dismissRoomDialog(); selectRoom(0)
        assertEquals(FeeRoomAction.Unbind, model.state.value.roomAction)
        assertNull(model.state.value.roomDialog)
    }
    @Test fun dormSiblingOpensExactMeterAndPreservesOfficialBindingWithoutSavePrompt() {
        fees.saved = fees.room(0)
        model.open(FeeProject.Wenbei, vault.username, false, fees.room(1))
        await { !model.state.value.loading && model.state.value.query != null }
        assertTrue(model.state.value.currentRoom!!.sameTarget(fees.room(1)))
        assertTrue(model.state.value.roomBinding!!.sameTarget(fees.room(0)))
        assertNull(model.state.value.roomDialog)
        assertEquals(0, fees.writes)
        model.refreshQuery()
        await { !model.state.value.loading }
        assertNull(model.state.value.roomDialog)
        assertEquals(0, fees.writes)
    }
    @Test fun dormAmountCreatesOrderForExactSiblingWithoutWritingRoomOrPayingAutomatically() {
        fees.saved = fees.room(0)
        model.open(FeeProject.Wenbei, vault.username, false, fees.room(1))
        await { !model.state.value.loading && model.state.value.query != null }
        assertEquals(0, fees.creations)
        model.create(100)
        await { !model.state.value.loading && model.state.value.stage == FeeStage.Methods }
        assertEquals(1, fees.creations)
        val target = org.json.JSONObject(fees.createdTarget!!)
        assertEquals(fees.room(1).selections.getValue("room"), target.getString("room"))
        assertTrue(model.state.value.roomBinding!!.sameTarget(fees.room(0)))
        assertEquals(0, fees.writes)
        assertEquals(listOf("demo-method"), model.state.value.order!!.methods.map { it.id })
        model.cancelPayment()
        assertTrue(model.state.value.currentRoom!!.sameTarget(fees.room(1)))
        assertNull(model.state.value.order)
        assertEquals(1, fees.creations)
    }
    @Test fun unavailableDormMeterDoesNotCreateAnOrder() {
        fees.canPay = false
        model.open(FeeProject.Wenbei, vault.username, false, fees.room(0))
        await { !model.state.value.loading && model.state.value.query != null }
        model.create(100)
        assertEquals(0, fees.creations)
        assertEquals(FeeStage.Query, model.state.value.stage)
    }
    @Test fun matchingSavedTargetCanBeUnboundEvenIfBalanceQueryFails() {
        fees.saved = fees.room(0); fees.detailFails = true
        open()
        assertNull(model.state.value.query)
        assertNotNull(model.state.value.error)
        assertEquals(FeeRoomAction.Unbind, model.state.value.roomAction)
        assertTrue(model.state.value.roomActionEnabled)
    }
    @Test fun staleSavedOptionFallsBackToSelectionAndDoesNotPromptOrOverwrite() {
        fees.saved = FeeRoomBinding.parse("campus:demo-campus#\$#示例校区;room:missing-room#\$#已删除房间")
        open()
        assertNotNull(model.state.value.error)
        assertNull(model.state.value.query)
        assertNotNull(model.state.value.roomBinding)
        selectRoom()
        assertNull(model.state.value.roomDialog)
        assertEquals(FeeRoomAction.Save, model.state.value.roomAction)
        assertEquals(0, fees.writes)
    }
    @Test fun lostWriteResponseIsReconciledWithoutReplayingWrite() {
        fees.writeFailure = NetworkFailure()
        open(); selectRoom(); model.confirmRoomAction()
        await { !model.state.value.roomBusy }
        assertEquals(1, fees.writes)
        assertEquals(FeeRoomAction.Unbind, model.state.value.roomAction)
        assertEquals("已保存为我的房间", model.state.value.roomNotice)
        assertNull(model.state.value.error)
    }
    @Test fun unknownReadBackDisablesWritesAndNeverClaimsSuccess() {
        open(); selectRoom(); fees.readBackFails = true
        model.confirmRoomAction()
        await { !model.state.value.roomBusy }
        assertFalse(model.state.value.roomBindingKnown)
        assertFalse(model.state.value.roomActionEnabled)
        assertNull(model.state.value.roomNotice)
        model.requestRoomAction(); model.confirmRoomAction()
        assertEquals(1, fees.writes)
    }
    @Test fun failedWriteKeepsPreviousBindingAndAllowsManualRetryOnly() {
        fees.saved = fees.room(0)
        open(); selectRoom(1)
        fees.applyWrite = false; fees.writeFailure = NetworkFailure()
        model.requestRoomAction(); model.confirmRoomAction()
        await { !model.state.value.roomBusy }
        assertTrue(model.state.value.roomBinding!!.sameTarget(fees.room(0)))
        assertNotNull(model.state.value.error)
        assertTrue(model.state.value.roomActionEnabled)
        assertEquals(1, fees.writes)
        assertNull(model.state.value.roomDialog)
    }
    @Test fun duplicateClicksAndLateWriteCannotMutateDismissedPage() {
        open(); selectRoom(); fees.writeGate = CompletableDeferred()
        model.confirmRoomAction()
        await { fees.writes == 1 }
        model.confirmRoomAction(); model.requestRoomAction(); selectIgnored()
        assertEquals(1, fees.writes)
        model.dismiss(); fees.writeGate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(model.state.value.config)
        assertNull(model.state.value.roomNotice)
    }
    private fun selectIgnored() {
        val state = model.state.value
        model.select(state.levels.last(), fees.rooms[1])
        assertEquals(fees.rooms[0].id, model.state.value.selections["room"])
    }
    @Test fun promptDependsOnCurrentBindingAfterExternalUnbindNotPastHistory() {
        fees.saved = fees.room(0)
        open(); selectRoom(1)
        assertNull(model.state.value.roomDialog)
        fees.saved = null
        open(); selectRoom()
        assertNotNull(model.state.value.roomDialog)
        model.dismissRoomDialog()
        open(FeeProject.FamilyWater); selectRoom()
        assertNotNull(model.state.value.roomDialog)
    }
    @Test fun unsupportedProjectNeverOffersSave() {
        open(FeeProject.Commercial); selectRoom()
        assertFalse(model.state.value.config!!.roomBindingEnabled)
        assertFalse(model.state.value.roomActionEnabled)
        assertNull(model.state.value.roomDialog)
        model.requestRoomAction()
        assertNull(model.state.value.roomDialog)
    }
    @Test fun wrongAccountCannotWriteRoom() {
        open(); selectRoom(); vault.username = "switched-user"
        model.confirmRoomAction()
        await { !model.state.value.roomBusy }
        assertEquals(0, fees.writes)
        assertFalse(model.state.value.roomBindingKnown)
    }

    private class MemoryVault : Vault {
        var username = "room-test-user"
        override fun credentials() = Credentials(username, "fake-password")
        override fun session() = Session("fake-token", Long.MAX_VALUE)
        override fun saveCredentials(value: Credentials) = Unit
        override fun saveSession(value: Session) = Unit
        override fun clearSession() = Unit
        override fun clear() = Unit
    }
    private class FakeFees : FeeService {
        val levels = listOf(FeeLevel("campus", 1, "校区"), FeeLevel("room", 2, "房间"))
        val rooms = listOf(FeeOption("demo-light&示例照明", "示例照明"), FeeOption("demo-air&示例空调", "示例空调"))
        val queryLevels = mutableListOf<Int>()
        var saved: FeeRoomBinding? = null
        var writes = 0
        var writtenProject: FeeProject? = null
        var detailFails = false
        var canPay = true
        var creations = 0
        var createdTarget: String? = null
        var readBackFails = false
        var applyWrite = true
        var writeFailure: Exception? = null
        var writeGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        fun room(index: Int) = FeeRoomBinding.from(levels,
            mapOf("campus" to "demo-campus", "room" to rooms[index].id),
            mapOf("campus" to "示例校区", "room" to rooms[index].name))!!
        override suspend fun feeConfiguration(token: String, project: FeeProject): FeeConfiguration {
            if (readBackFails) throw NetworkFailure()
            return FeeConfiguration(project, "示例缴费", RechargeConfiguration(project.id, "", "", 0, listOf(100), 1, 10000, true, false),
                project != FeeProject.Commercial, saved)
        }
        override suspend fun feeQuery(token: String, project: FeeProject, level: Int, selections: Map<String, String>, detail: Boolean): FeeQuery {
            queryLevels += level
            if (detail && detailFails) throw NetworkFailure()
            return if (detail) FeeQuery(thirdParty = org.json.JSONObject(selections).toString(), display = listOf("剩余电量" to "100 度"), canPay = canPay)
                else FeeQuery(levels = levels, options = if (level == 0) listOf(FeeOption("demo-campus", "示例校区")) else rooms)
        }
        override suspend fun setFeeRoom(token: String, project: FeeProject, room: FeeRoomBinding?) {
            writes++; writtenProject = project
            writeGate.await()
            if (applyWrite) saved = room
            writeFailure?.let { throw it }
        }
        override suspend fun createFee(token: String, username: String, config: FeeConfiguration, query: FeeQuery, amountCents: Long): FeeOrder {
            creations++; createdTarget = query.thirdParty
            return FeeOrder(PendingRecharge(username, query.thirdParty!!, config.project.id, amountCents, 0, "DEMO-ORDER"),
                listOf(FeeMethod("demo-method", "TSM", "微信", false, 0)))
        }
        override suspend fun prepareFeeAccount(token: String, order: PendingRecharge, method: FeeMethod): FeeAccountPreparation = error("No payment")
        override suspend fun payFeeAccount(token: String, order: PendingRecharge, method: FeeMethod, preparation: FeeAccountPreparation, type: String, password: String) = error("No payment")
        override suspend fun launchFeeWechat(token: String, order: PendingRecharge, method: FeeMethod): WechatRechargeLaunch = error("No payment")
        override suspend fun feeResult(token: String, order: PendingRecharge): RechargeResult = error("No payment")
    }
}
