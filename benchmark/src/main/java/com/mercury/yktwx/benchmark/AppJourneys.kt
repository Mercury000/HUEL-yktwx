package com.mercury.yktwx.benchmark

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import androidx.test.platform.app.InstrumentationRegistry

internal const val TARGET_PACKAGE = "com.mercury.yktwx.performance"
private const val ACTIVITY = "com.mercury.yktwx.MainActivity"
private const val UI_TIMEOUT = 10_000L

internal fun MacrobenchmarkScope.startFixture() {
    // Camera startup affects scan-entry profile collection, so grant the runtime permission
    // before launching the fixture instead of relying on a hand-tapped permission dialog.
    InstrumentationRegistry.getInstrumentation().uiAutomation
        .grantRuntimePermission(TARGET_PACKAGE, Manifest.permission.CAMERA)
    // Layout test tags differ between phone portrait and navigation-rail layouts.
    device.setOrientationNatural()
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

internal fun UiDevice.visitDeviceScanner() {
    // The scan action belongs to the payment/home page, while the preceding journey ends on bills.
    selectTab("付款", 0)
    await(By.res("main-floating-scan")).click()
    await(By.res("device-scanner"))
    // This marker appears only after CameraX reports STREAMING. A mounted page or camera
    // client alone does not prove that any preview frame has reached the screen.
    await(By.res("device-scanner-preview-ready"))
    // Record recognition work after preview readiness, then reject a failed camera session.
    // This sampling interval is not used as a substitute for waiting for preview startup.
    Thread.sleep(1_000)
    check(hasObject(By.res("device-scanner-preview-ready"))) { "Camera preview stopped during profile collection" }
    pressBack()
    await(By.res("main-pager-ready-0"))
    waitForIdle()
}

internal fun UiDevice.visitBillDetail() {
    selectTab("账单", 2)
    val list = await(By.res("bill-list"))
    if (!wait(Until.hasObject(By.res("bill-record")), 2_000)) {
        // Preview bills keep the chart above a list that may extend below the fold.
        list.swipe(Direction.UP, 0.8f)
        waitForIdle()
    }
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
