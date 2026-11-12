# 未使用代码清理记录（2026-10-27）

基线为提交 `4c9d23b`。本次从 `MainActivity` 和 `YktApp` 的现有入口反向核对页面、ViewModel、服务接口、数据模型和测试，并通过 Kotlin PSI 语法引用扫描、R8 裁剪结果及人工调用链复核区分了死代码与框架入口、运算符调用、字符串插值和无直接源码引用的依赖。

## 已删除的废弃入口

- `ui/pages/FeePage.kt`、`ui/pages/AboutTabPage.kt`、`ui/pages/DormFeeDialog.kt` 和 `data/SecureRechargeStore.kt` 均没有当前生产调用链，已整体删除。
- 旧 `DormViewModel` 已删除；仍在使用的 `DormUiState` 独立保留在 `DormUiState.kt`。
- 旧独立账单统计页面已移除，`BillStatsPage.kt` 收敛为仅包含当前 `BillPage` 使用内容的 `BillStatsContent.kt`。
- 旧付款弹层包装已移除，`FeePaymentDialogs.kt` 收敛为当前使用的 `FeePasswordDialog.kt`。
- 旧 `CampusCardServicesCard` 及其私有入口模型已删除；当前校园卡管理入口保持不变。

## 已整理的冗余状态和接口

- 删除没有生产入口的账单、宿舍和计费 ViewModel 方法，包括旧月份选择、统计页选择、详情清理、默认宿舍、刷新与兼容储蓄入口。
- 计费页面统一使用显式目标房间和 `createWithMethod`；删除未使用的自动保存提示、空查询刷新、`hall` 分支和重复建单包装。订单过期、取消、密码纠正、渠道隔离和服务端金额校验保持原行为。
- 删除没有读取方的图标、账单工具函数、回调参数、页面参数、存储接口和严格读取分支。旧充值存储数据不会被主动删除，账户凭据与 Token 的现有存储行为不变。
- 清理数据模型中只写不读或只供旧页面使用的字段，并同步所有构造、序列化、解析和测试调用点。服务端响应仍会执行现有协议与金额校验；仅移除了客户端没有消费者的保存在状态中的副本。
- `BillApi.billCount` 直接读取响应总条数，不再为取得总数构造整页账单记录。

## 测试调整

- 删除只验证旧 `FeePage` 的 `FeeRoomUiTest`，以及旧宿舍弹层、旧校园卡入口和已移除状态的重复测试。
- 将密码失败、房间目标、计费重试、余额解析等仍有价值的回归用例迁移到当前 `HallFeeDialog`、`FeeViewModel` 和当前数据入口。
- 删除已无法通过现有 UI 入口构造的重复支付码测试场景，保留当前入口可达的支付渠道隔离验证。

## 明确保留

- Manifest、`MainActivity`、AndroidX ViewModel 和 Compose 生命周期回调属于框架入口。
- 预览变体和工具代码有独立用途。2026-10-28 将假数据、预览文案及启动参数移至仅由 `debug` / `preview` 编译的 `src/uiPreview/kotlin/`，`release` 使用禁用实现和 `UiPreview.enabled` 编译期常量隔离。
- 传感器、导航、主题特效和协议字段均有运行或框架调用链。
- Gradle 中的 Core KTX、Compose 工具、ZXing、miuix、AboutLibraries 等依赖仍有构建、调试或运行用途，本次没有删除整库依赖。

## 验证

- Android debug 单元测试：421 项通过，0 失败，0 错误。
- `lintDebug`、`assembleDebug`、`assembleRelease`、`assemblePreview` 均纳入提交前最终构建检查。
