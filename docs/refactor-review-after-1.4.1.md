# 1.4.1 后七项重构复核

本记录针对网络、Version Catalog、JSON、DataStore、Hilt、相机和 SavedStateHandle 七项重构。按各项提交与其父提交比较，再沿当前主线调用关系检查组合行为。复核起点为主线 `7907ac4`，状态恢复修复前提交为 `da61dc8`。其他功能、性能和统计提交仅在它们调用这些共享路径时涉及，不作独立完整审查。

这是关键路径的源码复核，不是全应用所有行为已验证的声明。本轮重新执行的测试仅覆盖实际修改的缴费路径；下文其他测试名表示检查过的既有覆盖，不能计为本轮通过。

## 已确认并处理的问题

### 缴费恢复的取消和错误处理没有与保存状态同步

恢复密码确认时，模型先建立原单的结果状态，再读取支付方式和新的密码键盘。恢复状态没有配置、层级或原目标查询对象；取消付款仅清除 live order，没有清除 `pendingRestore` / `paymentPromptRetry`，也没有重读目标。

因此存在两个直接结果：在新键盘读取中取消，再保存并重建，会再次打开已取消的原付款；已经恢复成功后取消，则返回没有配置和错误提示的缴费页，页面也没有重试入口。即使恢复成功后再次保存，原目标选项也会因为结果状态没有保留 selections 而丢失。

密码键盘读取原先复用普通发起付款的 `prepareMethod` 错误处理。普通请求错误退回金额页并删除 order，但恢复重试记录仍保留原单。因此恢复失败后页面和保存记录指向不同操作。读取期间的明确拒绝、订单过期等结果也需要结束旧密码弹层的恢复意图。

修复在结果状态中保留安全目标 ID；取消时丢弃旧付款恢复意图，按原目标重读当前配置和选项，读取中再次离开仍保留的是取消后的目标草稿。恢复键盘读取失败保留原单的核实入口；显式重试重查原单、方法和新键盘。已过期或明确拒绝的结果不再保存旧密码弹层。整个恢复、取消和重试过程不调用创建或支付命令。

证据：[FeeViewModel.kt](D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/FeeViewModel.kt)、[FeeRestorationTest.kt](D:/mercu/Desktop/yktwx/app/src/test/java/com/mercury/yktwx/FeeRestorationTest.kt)。修复合入原状态恢复提交。

### 代人充值恢复失败的重试与新草稿覆盖

前一轮已确认：恢复读取失败时没有重试入口；用户更换收款人或验证码后，旧恢复快照可能覆盖当前草稿。原状态恢复提交中已包含 observable 失败状态、实际重试按钮、重试期间的编辑阻断，以及修改、刷新验证码、开始新查询时丢弃旧快照的修复。本轮检查它已在当前历史中，未再次重复修改。

证据：[CardServiceViewModel.kt](D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/CardServiceViewModel.kt)、[CardServiceDialog.kt](D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/ui/pages/CardServiceDialog.kt)。前一轮定向测试 9 项通过，未计入本轮测试数。

## 分项复核

| 项目／原提交 | 实际检查的路径 | 结论与限制 |
| --- | --- | --- |
| 网络 `c4e6b8c` | 校园登录、卡务、充值、缴费与生活平台传输；队列与 body 读取取消；总超时；gzip 大小上限；503/421 重发；网络切换、离页／返回；账号更新与旧请求结果 | 未确认本次迁移引入的新回归。service client 保持无 Cookie、无重定向、无连接失败重试，并处理 OkHttp 的 503/421 follow-up；有副作用的仓库调用另行禁止鉴权重试。无空闲连接复用会增加握手开销，收益与代价未进行性能实测。 |
| 依赖 `25d3728` | 根工程与 app 插件、各依赖坐标／版本、Compose BOM、debug/test/release 范围、本地 AAR/JAR | 比较迁移前后声明，坐标、版本和范围一致，未确认别名迁移造成依赖变化。后续 JSON、DataStore、Hilt 新增依赖分别归入各自提交。未运行发布构建或跨环境依赖解析。 |
| JSON `4f81a7e` | 字符串数字、数字、布尔、null、缺失字段、对象／数组；登录嵌套 JSON；付款目标比较；无版本旧记录与 v2 记录；单记录转列表、空列表、owner；持久字段名与默认值；签名载荷 | 未确认新回归。服务宽容转换与更新字段严格解析分别保留；签名核验的是解码后的原始 payload bytes。旧 session ID 由已有字段稳定生成，当前空列表具有权威性。新 display-v2 缓存不可授权付款，旧 display-v1 作为可丢弃缓存清理。未知未来记录版本拒绝读取属于明确边界。 |
| DataStore `46db6e2` | 首次迁移、目标写入、旧文件清理失败后重试；写入与延迟 observer 发布顺序；损坏文件／签名策略；并发策略更新、回退与 digest 冲突；自动提醒和渠道切换；启动等待 | 未确认新回归。清理发生在 DataStore 持久化之后；legacyMigrated 防止重导入覆盖新值。writeRevision 防止旧 observer 覆盖新写入；策略在发布前核验，损坏不自动回退成空策略。设置／策略读取失败会挡住业务页，属于现有设计，不能据此认为损坏数据已得到自动恢复。 |
| Hilt `b6ea6c5` | Application、Activity、Compose 的对象获取；仓库／服务／存储／DataStore 单例；Scope 和 client qualifier；配置重建的 ViewModel owner；网络监听释放；同意状态与 SDK 初始化 | 未确认作用域或对象重复创建回归。Activity 和 Compose 在同一 owner 下获取模型；DataStore 单例；network monitor 由专用 ViewModel 拥有并通过 shell 注册／释放。依赖构造与实际授权业务启动分开。未测冷启动耗时与真实进程内存变化。 |
| 相机 `ad9bab5` | 新 SessionConfiguration 与旧接口参数；预热与 surface 两种到达顺序；onConfigured 的线程与 generation；关闭时 session/device/ImageReader/Surface 顺序；未完成 open 的 lease；迟到 onOpened/onClosed；解码任务与 SurfaceTexture 释放 | 未确认会话接口迁移引入的新回归。executor 仍 post 到 cameraWorker，输出仍为 preview 与 reader；minSdk 34 满足 API 要求。线程保留到 open leases 释放后退出，关闭回调前不交出相机占用。主机 shadow 覆盖这些顺序，但不是厂商 Camera2 实机证明。 |
| 状态恢复 `da61dc8` | Bundle 保存／大小／版本；等待身份期间再保存；owner／preview／同账号重新登录；类型路由；账单详情分页匹配、离页取消；头像 URI／裁剪／选图 token；卡务／改密中断；充值原单；生活平台登录／验证码／付款／取消确认；更新弹层 | 已修复上面列出的缴费与代人充值问题。其余被走查的恢复入口没有确认新的命令重放。业务对象、密码和旧键盘不进入恢复 DTO，已有订单从只读引用核实；无单号的未知操作仍要求人工核实。没有用全量 UI 测试或实机杀进程验证替代源码结论。 |

## 跨模块的失败路径

### 网络取消、恢复与账号切换

`readResponse` 在发起前检查取消，取消回调调用 `Call.cancel()`；响应使用 `use` 关闭，读取前后检查 coroutine 的有效性。call deadline 外另有 coroutine deadline，包含 dispatcher 排队时间；父级取消保持 CancellationException，不被转换成网络错误。APK streaming 客户端没有全尺寸总超时，仍有 read stall timeout。

`ReadController` 为请求保存引用 identity、revision 和网络恢复 revision。取消旧 query 之后旧 finally 不能完成新请求；foreground/page 激活给网络失败新的恢复机会，当前周期自动尝试受 ReadGate 预算约束。网络 monitor 使用回调提供的 capabilities 和 blocked 状态，网络短暂切换延迟发布断线，重连 epoch 与 lifecycle resume epoch 分开。检查了自动 retry、手动排队 action、离页清空与 reconnect 跨在途请求的组合。

仓库可重复读取在拿到 session lease 后释放 mutex；鉴权失败只在 owner/generation 仍匹配时清理或续期，并复用已更新的 token。命令持有 session mutex 且不自动重试。创建订单、支付、绑定、改密、卡务状态修改等调用明确使用 `retryAuthentication=false`；服务 GET 也可能有副作用，所以传输层没有仅凭 HTTP method 放开重试。

相关源码：`data/AppHttp.kt`、`data/ReadController.kt`、`data/NetworkReadAccess.kt`、`data/NetworkMonitor.kt`、`data/AccountRepository.kt`、`data/PaymentHttp.kt`、`PaymentReturnObserver.kt`、`OrderResultPoller.kt`。

既有覆盖检查：AppHttpTest 的 body／排队取消、总超时、服务 GET/POST 不重发、gzip、重定向／Cookie；ReadControllerTest、NetworkMonitorTest、NetworkRecoveryNavigationTest、AccountRepositoryTest 的恢复与会话隔离。本轮未执行这些测试。

### JSON、历史存储、迁移与启动

旧加密记录沿用存储文件、Keystore key alias、IV/AAD；读取前校验 owner，重写时加 schemaVersion。持久 session 的时间字段通过 SerialName 保持旧名，非正时间值归一为空。旧单记录的合成 ID 没有随机生成；已有列表为空时不回退复活旧单。对版本不支持或解析失败的处理与网络响应解析分别检查，未把拒读解释为成功恢复。

旧 display 缓存的目录升级是有意放弃缓存，没有把它当成用户订单迁移。清理使用不跟随符号链接的遍历，完成标记只有在清理结束后提交；设置的三个旧文件由 DataStore migration 在持久化之后清理。此前本地记录的字段类型与当前 DTO 对照检查，服务响应的可选值转换不能机械套用到严格的已签名更新策略。

DataStore Ready 前要通过 serializer、schema、策略签名与账本字段一致性验证；MainActivity 先保护窗口，在 preferences 和 update model ready 前不进入业务。Hilt 构造 AppHttp 或 API 不会自行发请求，SecureVault 的 Keystore 获取是 lazy；SDK 初始化仍受 consent 控制。

相关源码：`data/JsonValues.kt`、`data/StoredRecordJson.kt`、`data/SecureVault.kt`、`data/DisplayCodecs.kt`、`data/DisplayCache.kt`、`data/life/LifeDeviceAccountStore.kt`、`data/life/Hui798AccountStore.kt`、`data/RetiredStorageCleanup.kt`、`data/AppPreferences*.kt`、`data/LegacyPreferencesMigration.kt`、`data/UpdateCheckStore.kt`、`data/AppUpdate.kt`、`data/AppUpdateInstaller.kt`、`MainActivity.kt` 与 `di/*.kt`。

既有覆盖检查：StoredRecordJsonTest、LifeDeviceAccountStoreTest、Hui798AccountStoreTest、LegacyPreferencesMigrationTest、AppPreferencesRepositoryTest、PreferencesStartupTest、HiltGraphTest。重点看了 cleanup failure / empty backup / delayed observer / corrupt policy / concurrent acceptance 用例的实际断言，本轮未执行它们。

### 页面恢复与业务意图

导航恢复先等待身份，账户或 generation 变化清空旧栈；生活平台路由还检查平台 owner，旧页不能直接重新绑定新账户。账单详情通过 ID 与原 period 查找，后续分页检查 current 前进，并用 detail generation 和 owner 隔离迟到响应。头像保留 content URI 和裁剪，重新解码；新 session token 隔离旧相册结果，失去 URI 权限时回到选图错误提示。

充值和缴费分别检查未提交草稿、已取得单号、无单号中断、明确拒绝与未知请求错误。恢复原单仅查结果，密码确认需先查原单和当前方法，再取新 preparation。生活平台恢复付款只核实原单或读取当前渠道；启动、停止、支付、取消订单需要新的显式动作。短信／图形验证码和密码没有保存为文本草稿。卡务和改密中断先重新读取当前卡片并显示核实提示。

更新弹层保存版本／digest／渠道与展示状态，重建后核对签名缓存；下载与安装不会通过 SavedStateHandle 自动继续。授权返回与再次安装都重新核验文件 hash、package、version 和签名。核对了渠道改变及下载 generation 对迟到进度的隔离。

相关源码：`state/*.kt`、`AppNavigationViewModel.kt`、`BillViewModel.kt`、`AvatarViewModel.kt`、`CardServiceViewModel.kt`、`CardPasswordViewModel.kt`、`RechargeViewModel.kt`、`FeeViewModel.kt`、`LifeDevicesViewModel.kt`、`Hui798ViewModel.kt`、`HellobikeViewModel.kt`、`AppUpdateViewModel.kt`、`ui/YktApp.kt` 与恢复入口对应的页面。

既有覆盖检查：StateRestorationTest、WholeAppRestorationTest、FeeRestorationTest、RechargeViewModelTest、CardOverlayRestorationTest、AvatarViewModelTest、HallRestorationUiTest、PresentationRestorationUiTest 及三平台恢复用例。源码走查不能证明全部 UI 路由组合、所有厂商回调或所有历史数据都经过运行验证。

## 本轮定向验证

修复前新增取消／重建和键盘读取失败用例，首轮完成 4 项，其中 3 项失败，分别复现原付款复活、取消后缺失配置、恢复错误退回 Query。另一项过期测试随后加强了未付款条件，另补明确拒绝恢复草稿用例。

修复后仅执行 FeeRestorationTest（13 项）与 FeeViewModelTest 中取消和密码直接共享路径（9 项），共 **22 项通过，0 失败、0 错误、0 跳过**。断言包括原单 ID/payId 保持不变、创建／支付调用为零、取消后原目标可编辑、取消期间再重建、旧任务结束不回写、新键盘显式重试及终态不复活密码弹层。

证据：[修复前日志](D:/mercu/Desktop/yktwx/tools/fee-restoration-before-tests.log)、[修复后日志](D:/mercu/Desktop/yktwx/tools/fee-restoration-final-tests.log)、[FeeRestorationTest XML](D:/mercu/Desktop/yktwx/tools/fee-restoration-fix/app/build/test-results/testDebugUnitTest/TEST-com.mercury.yktwx.FeeRestorationTest.xml)、[FeeViewModelTest XML](D:/mercu/Desktop/yktwx/tools/fee-restoration-fix/app/build/test-results/testDebugUnitTest/TEST-com.mercury.yktwx.FeeViewModelTest.xml)。

测试任务所需的源码和测试编译完成，git diff --check 通过。未追加全量测试、Lint、发布构建、设备安装或实机验证。其余六项的结论是本轮所列源码路径未确认新回归，不能扩大成全应用无缺陷。
