# 非网络层技术选型评估

评估日期：2026-11-12。范围包括主应用源码、production/uiPreview 实现、Gradle、清单与备份规则、相关测试入口和项目设计文档。网络层的 OkHttp 迁移由另一项工作负责；本报告只安排其完成后的接入顺序。

这是评估阶段的选型与完成标准，实际迁移和验证记录见 [迁移进度](modernization-progress.md)。

**确定迁移四项：设置存储改为 DataStore、业务 JSON 统一为 kotlinx.serialization、依赖注入统一为 Hilt、项目依赖与插件版本统一为 Version Catalog。另建设 Macrobenchmark 与应用自己的 Baseline/Startup Profiles。** 相机、图片、文件缓存、账单分页、页面导航及交易调度保留现有技术方案。

“完整迁移”以职责为边界：同一个设置键、同一个业务 JSON 边界、同一个应用服务不能长期保留两套实现。授权账本、加密凭据、可丢弃展示缓存各有不同的保存和恢复规则，应继续分开管理。它们使用不同存储机制有明确原因。

本报告完成源码和文档评估，尚未实施以下行为改动，也没有测得迁移后的性能结果。

## 1. 最终决策

| 模块 | 当前方案 | 最终选择 | 判断依据 |
| --- | --- | --- | --- |
| 普通设置、房间显示选择、更新设置与策略 | SharedPreferences；多处内存状态和直接读写 | **迁移到类型化 DataStore** | 统一数据来源、原子更新、异步读取与错误处理；消除更新渠道由两个对象分别维护的问题 |
| 业务 JSON | 24 个主源码文件引用 org.json；手写编解码 | **全部统一到 kotlinx.serialization JSON** | 已有序列化插件；稳定结构使用 DTO，动态结构使用 JsonElement，统一兼容和校验规则 |
| 依赖注入 | 静态仓库单例、默认构造 API、ViewModel 手工取得依赖 | **完整迁移到 Hilt** | 统一实例和生命周期，编译时检查依赖图，统一生产与测试替身的绑定 |
| 依赖与插件版本 | root/app Gradle 中分散声明 | **统一到 gradle/libs.versions.toml** | Kotlin、Miuix、协程等共享版本只维护一次，显式声明应用实际使用的库 |
| 性能验证与编译优化 | debug 日志、局部耗时记录；没有自有基准模块和 Profile 生成流程 | **增加 Macrobenchmark、Baseline Profiles 与 Startup Profiles** | 为启动、页面切换和滚动建立发布版测量；让常用代码路径参与编译优化 |
| 相机取帧 | Camera2、TextureView、ImageReader；专用线程和帧门控 | **保留 Camera2** | 当前完整实现已覆盖取帧、释放、预测返回和首帧顺序；没有证据证明 CameraX 能改善当前性能或修复当前故障 |
| 二维码识别与生成 | 微信二维码 native decoder；ZXing 生成付款码 | **保留两者各自职责** | 相机取帧、二维码识别、付款码生成是不同职责；已有成熟编码库，不改成另一套扫码 UI |
| 图片展示与头像编辑 | ImageDecoder/BitmapFactory、按尺寸采样、专用裁剪导出 | **保留，不引入 Coil** | 图片主要是少量头像和验证码；已有输入、像素、取消和账号隔离处理，Coil 不替代头像编辑与安全落盘 |
| 展示缓存与本地头像 | 小容量加密文件快照、AtomicFile | **保留，不引入 Room** | 缓存可丢弃，无 SQL、关联查询或本地数据库作为数据来源的需求 |
| 授权账本 | 独立 usage_consent，正文哈希匹配、保存成功后接受 | **保留独立存储** | release 在首个 Activity 创建前需要判断有效同意；不能因异步设置加载失去冷启动初始化依据 |
| 凭据与生活平台订单记录 | Android Keystore、AES-256-GCM、检查提交结果 | **保留加密存储机制** | 已使用系统非导出密钥和认证加密；DataStore 不提供加密替代。内部 JSON 仍随统一解析方案迁移 |
| 后台工作 | viewModelScope、有限前台轮询和请求取消 | **保留，不引入 WorkManager** | 当前工作不要求跨进程、重启后自动执行；支付与设备命令不得形成持久化重放队列 |
| 账单分页 | 查询缓存、受读取许可控制的页码追加 | **保留，不引入 Paging 3** | 分页与查询切换、离线快照、交易补查和滚动触发紧密协作；引入 Paging 仍需重建这些业务桥接 |
| 展示读取调度 | ReadController、ReadGate、SharedReadStore、TransactionRefresh | **保留业务规则** | 已统一许可、单次在途请求、账号隔离与有限重试；依赖提供方式由 Hilt 统一 |
| UI 与导航 | Compose、Miuix NavDisplay、可序列化路由、生命周期收集 | **保留** | 已使用现代 UI 和导航组件，已有预测返回与宽屏行为，无需再切一套 UI/导航框架 |
| 支付结果桥接 | 外层 ActivityResultContract，内部厂商 SDK 回调 | **保留** | 内部旧回调属于厂商接入边界，外层已使用现代结果 API；SDK 回调仍需服务端核对 |
| 图表、动画、模糊与更新说明 | Compose Canvas/动画、RuntimeShader、限定 Markdown 子集 | **保留** | 已使用现代绘制能力，产品表现明确；不引入通用图表框架、WebView 或完整 Markdown 渲染器 |
| 日期与构建工具链 | java.time、较新的 AGP/Kotlin/Compose、release R8 | **保留，不为本次评估追版本** | 当前已不存在 Date/SimpleDateFormat 一类待替换方案；版本管理整理与版本升级分开进行 |

### 迁移代价与影响范围

| 工作 | 主要收益 | 影响范围与代价 |
| --- | --- | --- |
| DataStore | 单一设置来源、原子更新和持久写入结果 | 三个旧设置文件及其所有调用方；需要历史迁移、启动状态和强制策略错误恢复，行为风险较高 |
| kotlinx.serialization | 统一解析、减少手写字段映射、格式可演进 | 24 个主源码文件及相关测试；混合类型、错误分类、支付金额、历史数据和原始签名是主要回归风险 |
| Hilt | 实例/作用域统一，依赖图编译检查，替身绑定明确 | 应用入口、仓库/服务构造、业务 ViewModel 和构建配置；增加 KSP 编译成本，需要完整核对共享实例和初始化顺序 |
| Version Catalog | 版本维护集中，减少重复声明与隐式依赖 | root/app Gradle 与 catalog；不改变行为，代价较低，但必须保留各构建的依赖范围 |
| 性能模块与 Profiles | 可重复测量和常用代码的编译优化 | 新测试模块、生成/打包流程和设备验证；有持续维护成本，实际收益必须用本项目数据证明 |

## 2. 设置存储：完整迁移到 DataStore

### 现有问题

[ThemeSettings.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/ui/ThemeSettings.kt:9) 构造时读 SharedPreferences，每个设置分别保存为 mutableStateOf，setter 同时修改内存和调用 apply。持久写入失败没有相应的统一状态。

[HallViewModel.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/HallViewModel.kt:52) 直接维护 hall_display。[UpdateCheckStore.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/data/UpdateCheckStore.kt:8) 与 ThemeSettings 都读写 app_updates 中的渠道键，但各自持有渠道相关内存状态。

更新存储还包含最高策略 revision、摘要、强制边界、已接受版本和原始签名清单。它承担防回滚职责，不能当作随时可重置的普通偏好。[AppUpdateViewModel.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/AppUpdateViewModel.kt:42) 在创建初始状态时读取缓存强制目标，因此迁移必须同时解决启动加载时序。

### 选定方案与完整边界

采用 **DataStore<AppPreferences> + kotlinx.serialization JSON Serializer**。AppPreferences 使用不可变类型，包含 UI 设置、按账号哈希保存的房间选择、更新渠道、按包/渠道/安装版本保存的检查及提示状态，以及按渠道保存的更新策略。这个范围使用一个文件、一个应用级 DataStore 实例和一个设置仓库，确保渠道与策略可以一起原子更新。

完整迁移 ui_settings、hall_display、app_updates 的有效数据。废弃设置不重新启用，现有默认值和取值范围继续生效。页面通过仓库的 Flow 获取持久状态，写入统一调用 updateData；页面局部编辑草稿仍可以使用 Compose 状态。

无需增加 Protobuf 编译链：官方支持使用 JSON Serializer 的类型化 DataStore，项目也已使用 Kotlin 序列化插件。新的模型仍须明确 schemaVersion 与迁移规则。

usage_consent 和 encrypted_account/生活平台加密文件继续作为独立数据域管理，不能与这些设置合并。它们不会与 DataStore 对同一个键双写。

### 必须一起完成的处理

1. **启动状态明确为 Loading、Ready、Failed。** 设置与更新策略未加载完成前，不启动受影响的业务读取，不先发布“无需强制更新”的默认结果。主线程不使用 runBlocking 等待磁盘。加载失败进入可重试错误界面，不能无限停在启动画面。
2. 设置尚未就绪时，截图保护采用开启状态；主题等首帧设置在准备完成后应用，避免先绘制错误主题或暴露付款界面。
3. 在同一个 updateData 事务内比较并保存 revision、digest、forced boundary、version 和 signed manifest，保留现有同 revision 冲突拒绝、版本及强制边界不回退规则。
4. 保留两个渠道中已确认强制边界的合并语义；切换更新订阅不能解除强制更新。检查冷却仍按原包名、渠道和安装版本区分。
5. 渠道只从仓库取得。设置页和更新模型不再各自维护第二份可独立写入的持久状态；切换时保留旧检查/下载取消和结果隔离行为。
6. 一次性迁移在目标成功持久保存后才清理旧设置；中途退出可以幂等恢复。升级后只写新格式，不能长期双写旧文件。
7. **含更新策略的数据损坏不能用默认空对象覆盖。** 采用明确的读取失败和可信策略恢复流程；不能顺带清除已接受强制状态。普通可丢弃展示缓存的损坏处理不适用于这里。
8. 新文件明确排除云备份与设备迁移。当前两个备份规则只排除了 sharedpref，迁移后必须覆盖 DataStore 的 file 路径，防止恢复旧渠道或旧策略。

完成标准：普通设置、房间显示选择及更新设置/策略的生产代码不再直接读写旧 SharedPreferences；同一渠道键只有一个仓库负责；有效授权的冷启动、首次未授权和强制更新阻断均保持成立。

## 3. JSON：统一到 kotlinx.serialization

### 选定方案

显式声明 kotlinx-serialization-json 运行时依赖。稳定接口结构使用专用 DTO；动态键、接口字符串/数值混合字段使用 JsonObject/JsonElement 与明确的转换函数。两种访问方式使用同一个解析库，不再长期保留 org.json。

迁移范围覆盖全部主源码 JSON 边界，包括接口请求及响应、业务错误判断、账单/缴费/充值模型、本地展示编解码、凭据与生活订单记录、更新封套与清单，以及统计错误分类。网络实现完成后再改其文件中的解析职责，避免两个任务同时改同一接口。

### 必须保留的语义

- 外部接口与本地格式分别配置解析规则。兼容服务端新增字段不等于容忍必填业务字段缺失；金额、账号归属、状态码、布尔值及未知状态必须沿用各自明确规则。
- [DisplayCodecs.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/data/DisplayCodecs.kt:31) 当前只存展示字段。新模型继续使用白名单，不直接序列化完整服务端响应，也不从缓存恢复付款许可或设备控制许可。
- 更新验签继续针对 Base64 解出的 **原始 payload 字节**，先验签再解析。清单文件摘要继续针对原始文件内容，不能先重序列化。[AppUpdate.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/data/AppUpdate.kt:86) 的严格数字规则继续拒绝字符串、浮点和布尔值，不能只依赖 DTO 自动转换。
- 凭据、订单和其他敏感模型继续采用安全的 toString；不能因改成 data class 而让自动生成的字符串输出凭据。
- 生活订单的恢复、归属、删除和迟到结果隔离继续成立。没有订单号的未知下单状态不自动重发，删除过的本机记录不被恢复响应重新创建。
- BillRecord.stableId 与 DisplayKeys 的部分身份由旧 JSON 文本构造。为这些用途规定稳定的身份编码并核对旧结果，避免更换序列化输出后把同一账单当作新记录。
- 现有金额规则并不完全相同：[BillModels.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/data/BillModels.kt:173) 的展示金额存在默认 0、四舍五入和非负处理；RechargeMoney 的支付金额使用严格十进制规则。解析库迁移保留已有业务口径，金额校验的行为修复单独处理。
- 服务端要求的签名串、优惠参数文本及编码保持协议约定；不因为统一 JSON 而更改签名算法或重新推导参数顺序。

### 旧数据处理

凭据和订单记录使用新库读取旧 JSON，成功更新时只写新格式；专门的版本兼容、早期单条订单标识及列表恢复规则统一到 `StoredRecordJson`，不在各存储保留独立手写解析器。现有 AES 密钥 alias、随机 IV、AAD 与加密封装不随解析库更换。具体兼容范围、长期格式约定和删除条件见 [JSON 存储格式与兼容清理](json-storage-compatibility.md)。

展示缓存采用新的缓存 schema/目录，旧展示快照直接淘汰并重新加载，不保留兼容读取；用户选项、凭据、订单与强制更新策略不采用这一清空方案。原始签名清单完整保留并继续验证。

完成标准：主源码无 org.json 引用；相关测试改用新解析库和真实格式样本，移除不再需要的 org.json 测试依赖；同一个接口或存储边界只有一套生产编解码实现。

## 4. 依赖注入：完整迁移到 Hilt

[AccountRepository.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/data/AccountRepository.kt:368) 用静态 sharedInstance 持有仓库并构造默认 API。多个 ViewModel 的生产构造器直接调用 shared(app)，内部仍有 API、存储和协程作用域的默认创建。

项目已有构造注入的测试入口，可以直接演进为 Hilt。官方手动 DI 文档也建议在可能时使用 Hilt；本项目选择 Hilt，收益在于统一已有依赖图，不能仅以“单模块”作为继续扩散静态单例的理由。

### 迁移边界

- 应用级仓库、设置仓库、存储、统计服务、共享读取状态及其他共享服务都由 Hilt 绑定提供；删除对应的静态取实例路径和生产默认构造依赖。
- 有外部依赖的业务 ViewModel 使用构造注入与 @HiltViewModel。Application/Context 的存储职责下沉到仓库或存储，业务模型优先继承普通 ViewModel。
- 无外部依赖的局部会话状态、不可变值和纯函数正常保留，不为了使用注入框架改写所有工具函数。
- 使用 Hilt + KSP 的编译链，并锁定与当前 AGP/Kotlin 配套的版本，不额外启用一套 kapt 管线。
- 使用统一的应用入口。production 与 uiPreview 提供各自的统计实现绑定，release SDK 依赖和 debug/preview 的停用实现继续按构建区分。

### 生命周期与初始化约束

仓库、DataStore 等确实需要共享的对象使用应用级作用域。NetworkStateViewModel 的监听仍保留当前页面壳挂载/卸载和 Activity 重建语义，不能因为使用 Hilt 就改成永不注销的全局监听。相机实例、支付 SDK 请求会话与页面任务仍由对应生命周期创建和释放。

共享读取用的协程作用域由依赖图提供，保留 SupervisorJob、最后读取者离开时取消以及账号撤销/reset 的现有规则。Hilt 不负责判断哪个账号有权接受响应，Repository 的会话版本、账号身份和互斥逻辑必须继续保留。

保留当前业务模型的 ViewModelStoreOwner。MainActivity 与页面壳对同一个业务模型的访问必须仍取得同一实例；不能机械把所有调用改为导航条目作用域，造成重复轮询、重复 SDK 唤起或账户状态不一致。

[UmengApplication.kt](/D:/mercu/Desktop/yktwx/app/src/production/kotlin/com/mercury/yktwx/analytics/UmengApplication.kt:6) 的有效同意检查与初始化继续早于首个 Activity 创建。依赖注入只负责提供对象；不能在 provider 构造时提前初始化友盟 SDK。首次未同意、撤销后的停用和 preview 隔离保持原行为。

### 完成标准

所有应用服务和业务 ViewModel 的生产依赖都通过同一 Hilt 图获得，不保留第二套服务定位器。业务单元测试继续直接构造对象和注入替身；框架集成只增加验证实际入口、共享实例及构建绑定所需的检查。

依赖图编译覆盖 debug、preview、release 三种实现。厂商 SDK 自身和无注入需求的桥接类无需为形式一致而改造。

## 5. 版本管理与性能建设

### Version Catalog

将 root/app 使用的库坐标、项目插件与共享版本放入 gradle/libs.versions.toml，统一 Kotlin、协程、Miuix、AboutLibraries 等重复版本，继续使用 Compose BOM。应用直接使用 JSON 等库时显式声明，不依赖导航库间接带入。

整理覆盖 implementation、构建专属依赖和 testImplementation，并保留 release-only 友盟、debug-only 工具和本地厂商 AAR/JAR 的范围。保留 AGP 内置 Kotlin 的兼容配置，不在整理中盲目改回另一种 Kotlin Android 插件用法。

Gradle Wrapper 继续由 wrapper 配置管理；settings 中在项目 catalog 可用前就解析的引导插件遵守该阶段的配置规则。本地厂商二进制保留校验摘要及来源记录。集中管理不会替代这些职责。

完成标准：root/app 不再重复硬编码同一依赖或插件版本，解析到的既有版本与构建专属范围保持一致。新增库的兼容性在其接入变更中验证，版本管理整理不附带批量升级。

### Macrobenchmark、Baseline Profiles 与 Startup Profiles

增加独立的性能测试/Profile 生成模块，覆盖冷启动、主界面显示、主标签与常用详情切换、账单滚动。相机首帧、识别成功耗时另做真机定向测量，不能仅用 UI 首帧指标代表扫码性能。

Profile 生成使用专用、未混淆的构建；最终测量使用不可调试、启用 R8 的发布配置。基准使用受控账号状态和服务替身，运行生产 UI，避免真实登录、下单或设备控制。测量报告注明替身和被排除的 SDK/网络工作，不能把这种结果当作完整线上响应时间。

启动路径生成 Startup Profile，启动与常用交互生成 Baseline Profile；最终 APK 检查是否包含应用生成的 profile，并明确 ProfileInstaller 的依赖与安装行为。

本项目从 GitHub/CDN 分发 APK。官方说明非 Play 渠道可能要等后台 dexopt 才体现 Baseline Profile 收益，因此不承诺首次侧载就有固定加速，也不直接引用官方约 30% 的通用数字作为本项目结果。Startup Profile 的 DEX 布局优化与设备的 profile 编译行为分别核对。

完成标准：在相同设备和受控条件下有可复现的基线/优化后数据，确认 profile 生成、打包与应用行为。设备不可用时可以完成配置准备，但不能把性能验收标记为已完成。基准按影响路径运行，不加入每次文案修改后的默认验证。

## 6. 保留项的依据

### Camera2 与二维码处理

当前取帧使用 acquireLatestImage、复制 Y 平面后立即关闭 Image、复用灰度缓冲区、120ms 帧门控以及最多一帧在途。相机与识别分别运行在工作线程，首帧显示后才初始化 native 模型。开启队列等到 onClosed 才交出相机，退出页面释放硬件，同时维持返回动画的最后预览画面；预测返回取消后继续使用原相机。

CameraX 是官方推荐的新应用相机库，优势是生命周期集成与跨设备兼容。但当前 Camera2 本身没有废弃，源码已有的线程、帧和预览处理也不会因为使用 CameraX 自动变快。迁移需重做相同的硬件释放、转场和取帧约束，当前收益证据不足，因此**保留完整 Camera2 取帧链**。

单独维护 [DeviceScanner.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/ui/components/DeviceScanner.kt:374) 使用的已废弃 createCaptureSession 重载，改用 SessionConfiguration/OutputConfiguration 与相机线程对应的 Executor。minSdk=34 不需要维持旧系统分支。这是一次定向 API 更新，不引入第二套相机管线。

保留微信二维码 decoder 和 ZXing 付款码生成；不换成扫码专用 SDK 的整页 UI，不额外维护双识别器兜底。现有 native 依赖的来源和 SHA-256 已在 app/libs/README.md 记录。

### 图片与本地文件

[AvatarImage.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/data/AvatarImage.kt:40) 已使用 ImageDecoder，限制输入 20MB 和最长边 2048，最终导出 512×512、最多 200KB 的 WebP。SampledImage 在后台按显示尺寸采样，限制总像素，并处理取消与旧结果覆盖。LocalAvatarStore 使用按账号隔离的 noBackupFilesDir 和 AtomicFile。

Coil 擅长通用图片加载、缓存、采样与取消；这里输入已是受控字节，头像编辑、规范化输出和账号存储仍须由业务完成。当前没有大量远程图片列表，故**保留当前图片链，不接 Coil**。小尺寸验证码的专用显示路径也不需要为统一依赖而重写。

[DisplayCache.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/data/DisplayCache.kt:24) 是 cacheDir 下的可丢弃加密快照，总量 5MB、写入单文件上限 512KB，已有淘汰、同步落盘与替换处理。它没有数据库查询职责。**保留文件缓存与头像存储，不接 Room，也不把展示快照改成本地权威数据库。** JSON 编码统一与文件存储机制保留可以同时成立。

### 授权与加密凭据

[UsageConsent.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/ui/UsageConsent.kt:16) 以 accepted_hash 等于当前须知正文哈希作为有效同意，accept 在 IO 中确认提交成功后更新状态。授权历史提示与授权依据分开，Application 可以提前读取已有记录。

[SecureVault.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/data/SecureVault.kt:29) 使用 Android Keystore 的非导出 AES-256-GCM 密钥、随机 IV 和字段 AAD，并检查原子提交。Keystore 是否由安全硬件支持取决于设备，不能仅凭代码声称密钥必然位于 StrongBox。保留当前机制和后台加解密路径。

DataStore 的异步读写与事务能力不提供加密，也不直接解决授权冷启动。上述两个模块完整保留各自的存储合同，其 JSON 部分统一到新库。

### WorkManager、读取调度与账单分页

WorkManager 面向要求跨应用退出、进程结束或设备重启继续可靠执行的工作。当前下载明确不在进程结束后续传；交易和设备状态核对需要按页面、前台、账号及有限次数控制，不能迁成后台必达任务。**不接 WorkManager**，保留 viewModelScope 与现有取消规则。

Paging 3 能提供通用分页、缓存、去重和 LoadState，适用于分页数据源。当前 [BillViewModel.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/BillViewModel.kt:404) 已处理重复记录、空页终止、查询取消和追加错误，列表内存快照限制为 200 条。第一页、概览和统计独立发布；离线恢复的展示快照不能继续翻页，重新读取后才恢复分页许可。

[BillPage.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/ui/pages/BillPage.kt:194) 在滚动接近底部时触发追加，配合查询切换的动画和位置。换成 Paging 需要继续实现读取许可、查询世代、交易补查和离线快照桥接，不能直接删除这些规则；被替代的页码工作不足以抵消当前重建成本。因此**整个账单分页维持现有实现，不添加半套 Pager 或双列表来源**。

读取调度已经集中实现，保留 docs/read-scheduling.md 和 docs/life-local-policies.md 的业务合同。Hilt 统一对象提供；JSON 统一数据边界，两者不改写有限补查或支付命令的语义。

### UI、导航、支付桥接与绘制

Compose、StateFlow、viewModelScope 和大部分 collectAsStateWithLifecycle 已在使用；Miuix NavDisplay 已负责预测返回、可序列化栈和宽屏转场。**保留当前 UI 与导航技术栈**。

NetworkAvailability 中的 collectAsState 用于持续接收连接世代，与普通可见页面的数据收集不同。保留已有注释约定，不机械批量替换所有收集 API。

支付外层已经使用 ActivityResultContract/rememberLauncherForActivityResult。银联等内部仍通过厂商规定的 Activity 回调桥接，**保留 SDK 边界**；只有服务端订单核对可以确认付款，不能把回调改名后视作最终成功。

图表使用 Compose Canvas，动画和模糊使用 Compose/Miuix 的现代能力，更新时间使用 java.time。更新说明只支持规定的 Markdown 子集，链接限 HTTP(S)，不执行 HTML、脚本或远程图片。**保留这些实现**，不引入整套通用渲染框架。

友盟现有业务统计与采集范围继续按专项设计执行。源码目前关闭自动未捕获异常上报，没有接入 U-APM；本报告的性能基准建设不等于已经具备线上崩溃/ANR 监控，也不把新增监控 SDK 混入基础组件迁移。

## 7. 实施顺序与定向验收

这些是确定的目标，顺序用于控制依赖与冲突。每项迁移在合入时完成自己的职责切换；可以拆成独立提交，不长期留同职责双实现。

| 顺序 | 工作 | 完成后应看到的结果 | 实施时最小直接验证 |
| --- | --- | --- | --- |
| 0 | 网络层迁移完成并确认接口 | 后续工作基于同一网络入口和返回类型 | 由网络任务负责 |
| 1 | Version Catalog | 共享版本单一来源，既有版本及构建范围不变 | 静态核对坐标/版本和依赖解析；无需全量测试 |
| 2 | kotlinx.serialization | 所有业务 JSON 使用同一库，旧格式可读，新格式单写 | 按迁移边界运行解析、错误分类、缓存/订单兼容测试；更新验签使用真实签名样本；支付金额和身份键核对 |
| 3 | DataStore 与启动准备状态 | 三类设置统一仓库，渠道单一来源，策略原子保存 | 旧设置迁移/中断恢复、并发更新、写失败、损坏、两个渠道强制边界、加载前阻断、截图保护；复用授权冷启动定向回归 |
| 4 | Hilt 全图接入 | 应用服务与业务 ViewModel 使用同一依赖图 | 三个构建的依赖图编译；入口实例共享、生产/preview 隔离、友盟初始化顺序与账号退出取消 |
| 5 | 性能模块和 Profiles | 可复现的发布版数据、APK 中实际打包 profile | 只测规定的启动、导航、滚动路径，说明设备与编译模式 |
| 6 | Camera2 session 重载更新 | 同一相机管线使用当前 API | 既有相机线程/释放测试，加真机进入退出、旋转、闪光灯、缩放、预测返回与首帧检查 |

存储和 JSON 迁移的测试重点是历史数据与失败语义，不能只验证“新格式能读回新格式”。Hilt 重点是实例和生命周期，不能只验证注解存在。性能及相机验收需要设备证据，不能以 Robolectric 或 debug 日志替代。

本轮仅新增此评估文档，没有修改业务实现，没有运行测试、构建或 Lint，也没有提交其他任务的工作区改动。

## 8. 官方依据与阅读范围

已完整阅读以下页面的正文及代码示例，再结合项目实际职责判断；不把概览页面当作全部实现细节，也不把官方示例版本当作当前工程必须使用的版本。

| 来源 | 本报告采用的内容 |
| --- | --- |
| [Android DataStore](https://developer.android.google.cn/topic/libraries/architecture/datastore?hl=en) | 异步 Flow、原子 updateData、同文件单实例、不可变类型、JSON Serializer 与备份要求 |
| [Kotlin Serialization](https://kotlinlang.org/docs/serialization.html) | 插件与运行时依赖、稳定 JSON 格式、类型化序列化 |
| [Android 手动依赖注入](https://developer.android.google.cn/training/dependency-injection/manual?hl=en) | 应用容器与构造注入；在可能时推荐 Hilt |
| [Android Hilt](https://developer.android.google.cn/training/dependency-injection/hilt-android?hl=en) | KSP 接入、Application/Activity/ViewModel 入口、绑定、限定符、组件作用域与编译检查 |
| [Version Catalog 迁移](https://developer.android.google.cn/build/migrate-to-catalogs?hl=en) | 集中库与插件版本、类型安全引用及迁移步骤 |
| [Baseline Profiles 概览](https://developer.android.google.cn/topic/performance/baselineprofiles/overview?hl=en) | Profile 生成和 release 构建区分、Startup Profile、Macrobenchmark、非 Play 渠道限制 |
| [CameraX 概览](https://developer.android.google.cn/media/camera/camerax?hl=en) | 新应用的推荐相机库、跨设备一致性；不构成现有 Camera2 性能改善证明 |
| [Coil](https://coil-kt.github.io/coil/) | 通用图片加载、缓存、采样和取消的能力范围 |
| [Room](https://developer.android.google.cn/training/data-storage/room?hl=en) | 结构化数据库与 SQL/migration 的适用范围 |
| [WorkManager](https://developer.android.google.cn/topic/libraries/architecture/workmanager?hl=en) | 跨退出/重启的可靠调度，与可终止进程内协程的边界 |
| [Paging 3 概览](https://developer.android.google.cn/topic/libraries/architecture/paging/v3-overview?hl=en) | PagingSource/Pager/PagingData、缓存、去重、Compose 及测试工具 |
| [Android Keystore](https://developer.android.google.cn/privacy-and-security/keystore?hl=en) | 非导出密钥、密钥用途约束、后台加解密、安全硬件与 StrongBox 的实际边界 |

更新模块同时按现有 docs/app-updates.md、docs/update-review.md 的项目约束核对存储与安装边界，保留签名、防回滚、SHA-256、APK 身份核对和系统安装授权流程。
