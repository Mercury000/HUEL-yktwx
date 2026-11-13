# 技术迁移与验证记录

目标：按 [技术选型评估](modernization-assessment.md) 逐项完成、逐项验证、逐项提交。各项实施完整切换职责，不长期保留双实现。兼容基线为正式版 1.4.1（`1bb98ea5bc87e9a217ce6b8af10c47046aa92c73`）；其后的未发布格式不保留兼容。网络层已由独立任务提交，提交为 `fafc872`。

| 项目 | 状态 | 验证与完成证据 |
| --- | --- | --- |
| Version Catalog | 完成 | root/app 统一引用 `gradle/libs.versions.toml`；迁移前后 debug、preview、release 和 debug 单元测试的编译依赖及根构建脚本依赖完全一致 |
| kotlinx.serialization | 完成 | 生产代码及测试依赖移除 org.json；18 组、224 项 JSON 相关定向测试通过；专门存储兼容集中到 `StoredRecordJson` |
| DataStore 与启动准备状态 | 完成 | 一个类型化文件与仓库；1.4.1 一次性迁移；9 个相关类中的 68 项定向验证通过，含真实文件迁移与启动阻断 |
| Hilt 全图接入 | 完成 | debug、preview、release 的生产和测试图均编译通过；104 项定向测试通过、无跳过 |
| Macrobenchmark 与 Profiles | 实现及本地验证完成，真机收益待验收 | 两条生成流程通过；优化性能/正式 APK 的应用 Profile 均通过解析与 DEX 匹配检查；CI 仅手动触发 |
| Camera2 session API | 待实施 | 尚未更新重载及完成相机定向验证 |

以上迁移验证后，按新增要求完整迁移页面状态恢复：SavedStateHandle、带参数的类型化路由和业务对象恢复入口一起交付，覆盖全部页面、弹层与账户切换；不兼容本轮之前未发布的页面状态格式。性能与相机的设备验证仍须取得实际设备证据。

所有更改完成后进行完整 review，发现问题修入所属迁移提交，并再次复查修正结果，直到没有未解决的审查项；不另留独立修补提交。

哈啰接入按 2026-11-13 的最新要求纳入本轮：按线性历史接入 main，并适配已完成的存储、注入及后续页面恢复架构。当前禁止 ADB；本地实现、定向测试和构建继续，真机性能及相机验收保持待完成，不以模拟器或主机测试替代。

## Version Catalog

集中声明 Maven 依赖与 root/app 插件版本，统一 Kotlin、Miuix、协程、AboutLibraries 和 OkHttp 的共享版本，保留 Compose BOM、release 专属友盟、debug 工具及本地厂商二进制范围。Gradle Wrapper 和 settings 阶段的 Foojay 引导插件继续使用各自的配置机制。

验证使用相同的依赖快照任务分别解析迁移前后的 `debugCompileClasspath`、`previewCompileClasspath`、`releaseCompileClasspath`、`debugUnitTestCompileClasspath` 和根 `buildscript` classpath，比较所有解析出的坐标与版本，差异为 0。Gradle 配置及解析成功；该项不改变应用行为，未运行应用测试、APK 构建或 Lint。

本地诊断记录位于未跟踪的 `tools/modernization/catalog-before.log` 与 `catalog-after.log`。后续迁移的新增依赖单独验证，不改变本项保持既有版本的结论。

## kotlinx.serialization

全部业务 JSON 统一为 kotlinx.serialization：动态接口使用不可变 JsonObject/JsonArray 和明确的混合类型转换，展示缓存改为具名白名单 DTO，凭据及生活平台订单使用生成的序列化器。JSON 运行时为显式依赖，移除 org.json 测试依赖。更新继续先验证原始 payload 字节再解析，清单摘要仍针对原始文件字节；支付金额、错误分类和请求签名的业务规则保持原口径。

展示缓存使用 `display-v2`，旧快照直接淘汰、重新查询。专门的历史存储规则集中到 `data/StoredRecordJson.kt`：接受 1.4.1 的无版本号记录、补基线已有单条订单的确定性 ID、在列表不存在时读取单条来源；有空列表时不能恢复旧记录。写入统一带版本 2，包括登录失效后的本地订单删除。范围和后续删除条件见 [JSON 存储格式与兼容清理](json-storage-compatibility.md)。

补齐旧存储回收：进程启动后在后台 IO 按版本一次性删除 `display-v1` 及残留临时文件，成功后原子记录完成，后续启动跳过，失败或中断可重试；DataStore 成功持久化后删除 `ui_settings`、`hall_display`、`app_updates` 的 XML 和备份，残留空文件也可重试。核对历史目录与订单格式转换，继续保留正在使用的凭据、订单、头像、扫码模型及安装包存储；单条订单仍在列表保存成功后删除。

回收补充验证仅运行 `RetiredStorageCleanupTest` 与 `LegacyPreferencesMigrationTest`，共 7 项通过、无跳过，覆盖实际文件删除、完成后的跳过、标记保存失败重试、空备份回收及迁移失败后保留新设置；相关生产和测试 Hilt 图编译通过。未追加全量测试、发布构建或 Lint。

验证覆盖 18 个直接相关测试类，共 224 项，全部通过、无跳过：更新签名及严格数字类型，账单和身份编码，登录、个人资料、校园卡和缴费/充值协议，展示缓存，以及两家生活平台的接口和加密记录编解码。旧格式凭据和未知订单使用固定历史 JSON 样本验证，另覆盖账户隔离、删除后不恢复、保存失败和旧缓存淘汰。仅执行 `testDebugUnitTest` 的明确筛选，没有运行全量 UI 测试、发布 APK 构建或 Lint；生产及测试 Kotlin 编译、暂存前空白检查通过。

按发布边界收紧后，仅重新运行 `StoredRecordJsonTest`、`LifeDeviceAccountStoreTest`、`Hui798AccountStoreTest`，验证基线记录恢复和未发布显式版本拒绝；全部通过。

Android 启动验证发现系统 Ed25519 KeyFactory 无法导入发布公钥，更新验签改用 Tink Android 1.24.0 的可移植实现。公钥继续采用固定 Ed25519 SPKI 编码，先校验原始 payload 字节、再解析 JSON，不修改全局安全提供者。复用 Python 发布工具的真实签名样本，24 项主机更新接口测试及 4 项 Android 验签/应用入口测试通过、无跳过；Android 验证在暂停设备操作前完成。

## DataStore 与启动准备状态

使用 DataStore 1.3.0 与 JSON Serializer，UI 设置、账号哈希房间选择、更新渠道、提醒/跳过状态及签名策略统一到 `files/datastore/app-preferences.json`。三个旧文件只由 `LegacyPreferencesMigration` 读取，迁移来源限定为正式版 1.4.1；更新展示缓存以及之后未发布的渠道、跳过与策略字段不迁移。保存完成后检查清理结果，失败可幂等重试，已保存目标不会再次导入旧值。新目录同时排除云备份和设备迁移。

设置通过持久快照的只读 Compose 投影显示，写入走同一仓库，失败保留原值并提示。每次写入递增持久 revision，发布时拒绝迟到旧快照。签名账本在 updateData 内一次比较并保存 revision、payload 摘要、版本、强制边界和原始签名清单；跨渠道合并强制边界，不能用切换渠道解除阻断。设置写入本身不触发额外自动检查，渠道切换继续取消旧检查/下载并隔离迟到结果。

设置及更新状态明确为 Loading、Ready、Failed。主 Activity 在异步读盘前设置截图保护，准备完成前不创建业务模型，生命周期回调也不会提前创建；读取失败显示重试入口。付款本地身份恢复、业务前台启动和同意处理在加载后继续按原规则运行，强制更新阻断业务页面与前台读取。usage_consent 与加密凭据/生活订单继续独立保存。损坏文件和缺少当前持久字段的文件进入读取失败，不配置默认空对象覆盖；重试只重读原文件，不清空已接受策略。

定向验证共 68 项、全部通过、无跳过，涉及 9 个类：AppPreferencesRepositoryTest（5）、LegacyPreferencesMigrationTest（2）、ThemeSettingsTest（2）、AppUpdateApiTest（24）、AppUpdateViewModelTest（25）、PreferencesStartupTest（2）、UsageConsentStartupTest（4），以及 HallViewModelTest 的三项选择恢复/账户隔离测试和 LoginUpdateUiTest 的强制更新阻断测试。覆盖真实文件保存和清理失败重试、签名账本序列化恢复、并发策略冲突/回滚、迟到快照、写入失败、渠道取消、加载前截图保护与生命周期阻断、同意恢复及强制页。仅筛选直接相关测试；未运行全量测试、发布构建或 Lint。

## Hilt 全图接入

统一为 `YktApplication` 和 `MainActivity` 的 Hilt 入口。应用仓库、设置、DataStore、统计、加密存储和 HTTP 客户端由同一个 SingletonComponent 提供；业务模型使用普通 ViewModel 与构造注入，删除生产静态取实例入口及默认创建依赖的构造路径。debug/preview 的统计停用实现和 release 的 SDK 实现继续由各自源码集提供，provider 只构造对象，Application 在有效同意成立时初始化 SDK。MainActivity 在 Hilt 初始化设置图之前开启截图保护。

| 依赖 | 提供位置与作用域 | 保留的职责 |
| --- | --- | --- |
| 统计、授权依据 | ApplicationModule / SingletonComponent | 同意判断及撤销；冷启动初始化早于 Activity |
| 设置、DataStore、加密存储 | StorageModule、PreferencesModule / SingletonComponent | 设置独立 IO + SupervisorJob；原子保存与一次性基线迁移 |
| HTTP、业务服务、更新验签与安装器 | ServicesModule / SingletonComponent | 三类客户端共用连接池与调度器；保留各类请求重试规则 |
| 账户仓库、共享读取、数据变更 | RepositoryModule / SingletonComponent | 两类独立 SharedReadStore；Default + SupervisorJob；账户撤销和最后读取者离开取消 |
| 网络监听 | ViewModelModule / ViewModelComponent | 由页面壳启动和停止；模型清理时注销监听 |

MainActivity 与 Compose 壳继续使用原 Activity 的 ViewModelStoreOwner，避免形成重复业务实例。单元测试仍直接构造真实对象、显式传替身；便利构造和测试注册表仅存在 test 源码。实际入口与实例共享由 Hilt 集成测试检查。Hilt 2.60.1、KSP 2.3.13 使用 KSP 管线，生成 Java 编译任务跟随项目的 Java 22 工具链。

debug 通过 12 个直接相关类中的 94 项定向测试、无跳过，覆盖 HiltGraphTest、PreferencesStartupTest、UsageConsentStartupTest、SharedReadStoreTest、AccountRepositoryTest、AppHttpTest、NetworkMonitorTest、AppUpdateViewModelTest、UiPreviewIsolationTest，以及缴费/充值统计和强制更新页各一个定向用例。preview、release 分别通过 UiPreviewIsolationTest 的 5 项测试，验证 SDK 依赖范围、统一 Application、有效同意的冷启动初始化及预览参数隔离。合计 104 项，全部通过、无跳过；三种构建的生产和测试 Hilt 图均已完成 Java 编译。

提交前两轮检查覆盖生产创建入口、限定符与作用域、Activity 模型归属、截图保护顺序、同意初始化及测试替身边界，没有剩余已识别问题。只执行以上直接相关测试及依赖图编译，没有运行全量测试、发布 APK 构建或 Lint。本地诊断日志为未提交的 `tools/modernization/hilt-verification.log`。

## Macrobenchmark 与 Profiles

增加独立 benchmark 模块，共用冷启动、主标签、账单详情及滚动操作。性能目标构建复用受控样本，关闭统计和真实业务操作；测量使用不可调试且启用 R8 的配置。启动采集单独生成 Startup Profile，常用操作加入 Baseline Profile，生成入口排除预览样本类。主界面页签等待明确的 pager 动画结束标识，避免在切页尚未完成时误点详情。

2026-11-13 在暂停 ADB 前，两条 API 36 模拟器生成流程通过；测量的四种参数化用例在生成模式按规则跳过，不计为性能测试通过。排除预览样本后，Baseline 含 28,710 条规则，Startup 含 22,575 条规则。生成文件直接随源码保存，日常构建不会调用设备。

最后一次本地优化性能与正式 APK 构建成功，校验器结合各自 R8 mapping 确认 Profile 对应打包 DEX，分别找到 1,235、1,254 个应用所属类的方法；编译资料分别为 13,333、12,011 字节，均低于 1.5 MB。这里统计打包资料中的方法，不声称 ART 已实际编译或速度已改善。校验器的六项定向测试覆盖混淆后归属、热方法与启动位图、多 DEX、错误校验值、只有库方法、错误映射和无效文本资料。

本地证据为 `tools/modernization/performance-profile-verified.log`、`profile-packaging-current.log`、`performance-profile-verification.json` 和 `release-profile-verification.json`。最后两份 APK SHA-256 分别为 `66bfa59d8622a1bc74bb7073d99d820bf67cf685c078ba29fcab61fc6c061e0c`、`9ccbaea09d5d5c57d8860355ec476acc2b7bf5acf26e2a26947ce44dedc61bd1`。

CI 采集按最新要求仅手动运行，更新分支为 `perf/baseline-profiles-main` / `perf/baseline-profiles-beta`，自动提交无正文。正式打包仅消费并核验现有资料；无需每次运行模拟器。工作流已做静态检查，未推送或触发远端执行。使用方式与限制见 [性能基准与应用 Profiles](performance-profiles.md)。同设备的编译模式对照、Startup DEX 布局对照及真实收益仍待设备操作恢复后完成。
