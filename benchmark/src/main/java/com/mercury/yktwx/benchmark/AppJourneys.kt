package com.mercury.yktwx.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "com.mercury.yktwx.performance"
private const val ACTIVITY = "com.mercury.yktwx.MainActivity"
private const val UI_TIMEOUT = 10_000L

internal fun MacrobenchmarkScope.startFixture() {
    startActivityAndWait(Intent.makeMainActivity(ComponentName(TARGET_PACKAGE, ACTIVITY))
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        .putExtra("ui_preview", true))
    device.await(By.res("main-bottom-navigation"))
    device.await(By.res("main-pager-ready-0"))
    device.waitForIdle()
}

internal fun UiDevice.selectTab(label: String, page: Int) {
    await(By.text(label)).click()
    // Accessibility idle does not imply that Compose's pager animation has finished.
    await(By.res("main-pager-ready-$page"))
    waitForIdle()
}

internal fun UiDevice.visitMainPages() {
    selectTab("大厅", 1)
    await(By.res("hall-page-list"))
    selectTab("我的", 3)
    await(By.res("my-page-list"))
    selectTab("账单", 2)
    await(By.res("bill-list"))
}

internal fun UiDevice.visitBillDetail() {
    await(By.res("bill-record")).click()
    await(By.text("账单详情"))
    waitForIdle()
    pressBack()
    await(By.res("bill-list"))
    waitForIdle()
}

internal fun UiDevice.scrollBills() {
    val list = await(By.res("bill-list"))
    // BillPage routes gestures through its nested scroll layer, rather than LazyColumn.
    repeat(3) { list.swipe(Direction.UP, 0.8f); waitForIdle() }
    repeat(3) { list.swipe(Direction.DOWN, 0.8f); waitForIdle() }
}

private fun UiDevice.await(selector: BySelector): UiObject2 =
    checkNotNull(wait(Until.findObject(selector), UI_TIMEOUT)) { "Missing UI element: $selector" }
