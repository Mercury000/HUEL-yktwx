# 友盟 U-App 接入说明

接入位于原项目 `yktwx` 的 `main` 分支。旧 Aptabase 独立目录和分支已清理。本次遵循 [19 个事件的统计设计](umeng-analytics-design.md)，使用官方 SDK 与友盟 Android 统计接入 Skill。

## 当前状态

- release 固定依赖 `com.umeng.umsdk:common:10.0.1`、`com.umeng.umsdk:asms:1.8.7`，由已有 Maven Central 仓库解析，不使用动态版本；debug 和 preview 不包含 SDK，使用共享的停用实现，业务埋点保持一套调用。
- 启用统计的 release 在 `Application.onCreate()` 读取当前须知的有效同意记录，已有同意时依次执行 `preInit`、同意结果提交及 `init`，确保 SDK 在首个 Activity 创建前注册生命周期回调。首次未同意时不预初始化，接受须知后由前台路径初始化；`UMENG_ENABLED=false`、配置为空和 UI Preview 均不初始化。
- Application 初始化不调用 `onResume` 或发送业务事件；业务操作仍须在有效授权且实际进入前台后开始。恢复前台复用同一个实例，不重复初始化。
- 手动生命周期通过主 Activity 的 `onResume` / `onPause` 维护基础启动与会话，不增加 `app_open`，不调用通用页面统计或账号绑定接口。
- 19 个业务事件已接入付款码、账单、本人/代人充值、四类缴费、胖乖接水/淋浴/洗衣、798用水、三平台手动登录及更新流程。
- 用枚举限定分类；连续取码故障、逻辑操作结果和新版发现有本地去重，撤销前的异步回调不能上报。
- 须知已加入友盟接收方、设备识别和事件范围。正文哈希随内容更新，已有用户保留登录信息，但需要重新同意后使用。
- 正式应用为「财小通正式版」；实际发送目标由构建注入的 `UMENG_APP_KEY` 决定，分发渠道为 `official`。release 的主 Activity 配置独立 `um.<AppKey>` 唤起入口，由 SDK 处理官方扫码集成测试模式。正式 SDK 的本地日志关闭。
- 友盟 CLI 登录与 OpenAPI 已就绪。正式应用的 19 个普通计数事件已创建，英文 ID 和中文显示名称均已回查匹配。`payment_code_fetched` 已启用，旧事件 `payment_code_shown` 已暂停并保留历史；实机发送、正式 APK 混淆验证与次日数据核对尚未完成。

## 本机配置

填写未提交的 `local.properties`，或提供环境变量；环境变量优先。

| 本机属性 | 环境变量 | 用途 |
| --- | --- | --- |
| `umeng.appKey` | `UMENG_APP_KEY` | 正式发布 `release` |
| `umeng.channel` | `UMENG_CHANNEL` | 本构建实际分发渠道 |

```properties
umeng.appKey=
umeng.channel=
```

AppKey 来自正式 Android 应用，格式为 24 位十六进制。本地编译的所有构建（包括 release）均不初始化统计，即使本机已配置 AppKey、渠道或 `UMENG_ENABLED=true`。启用开关只由 GitHub Actions 注入，不读取 `local.properties`。关闭统计的构建允许缺配置，但无法通过扫码启用统计；debug / preview / performance 的开关始终关闭，AppKey 和渠道始终为空，不依赖友盟 SDK。personal 构建已移除。

渠道由开发者明确提供，允许 1–64 位字母、数字、下划线或连字符，不自动采用市场名称，不以 stable/beta 更新选择覆盖安装渠道。

## CI 配置

现有构建工作流增加以下注入项：

- 仓库 Secret：`UMENG_APP_KEY`。
- 仓库 Variable：`UMENG_CHANNEL`。

2026-11-12 已通过 GitHub CLI 写入并核对正式 AppKey Secret 和值为 `official` 的渠道 Variable。`.github/workflows/build.yml` 负责注入正式配置与构建开关。

仅非 PR 的 release 构建注入这些配置，debug 不注入。`prepare_build_version.py` 计算启用策略，工作流通过 `UMENG_ENABLED` 传给 Gradle；只有 `GITHUB_ACTIONS=true` 且开关为 `true` 的 release 才允许初始化。

| 构建来源 | `UMENG_ENABLED` |
| --- | --- |
| 本地编译，包括 release | 固定关闭 |
| Actions 手动 release | 由 `umeng_enabled` 勾选项决定，默认关闭 |
| CI 自动发布 stable / beta，包括重试 | 自动开启 |
| debug / preview / performance、PR 或未发版的 push | 固定关闭 |

开关关闭时，初始化入口在调用任何友盟 SDK 方法前直接返回，用户同意、重新登录或恢复前台不会启用统计。开关开启仍须有效同意，AppKey 和渠道缺失或格式错误会让构建失败；发布前应完成实机发送验证。

## 官方扫码集成测试

SDK 10.0.1 满足官方 9.4.0 及以上的要求。release 专属清单在 MainActivity 中添加 VIEW、DEFAULT、BROWSABLE 的独立 intent-filter，Scheme 由当前正式 AppKey 生成，和 MAIN 启动入口并列；debug / preview 不注册此入口。

1. 在 Actions 手动选择 release 并勾选 `umeng_enabled`，或使用 CI 自动发布的包；安装后在应用中同意当前须知。本地 release 和未勾选的手动包不会初始化 SDK，不能用于扫码统计验收。
2. 打开友盟后台「管理 → 集成测试」，使用系统相机、系统扫一扫或浏览器扫描测试二维码，唤起应用。
3. 操作需要验证的功能，在后台「实时日志」核对基础统计与事件。进入集成测试后，测试数据由 SDK 隔离，不进入正式统计报表，也不增加业务代码的测试分支。
4. 完成后按后台提示结束测试；正式报表核验另用已退出测试模式的正常运行记录。

扫码入口只负责唤起应用，不绕过须知同意，未同意时仍不初始化 SDK。后台实时集成日志与 `UMConfigure.setLogEnabled` 的本地日志开关分别控制，正式包关闭本地日志不影响按官方扫码方案验收。

已有有效同意记录时，冷启动的初始化在 `Application.onCreate()` 完成，SDK 可以在首个 Activity 的创建回调中读取二维码携带的测试标记。此前在 Activity 的 `onResume` 才初始化，可能错过首次冷启动的测试入口；再次唤起时 SDK 已初始化，因此可能进入测试。这是代码时序问题的解释，尚未以修改后的安装包实机复现和验收。

官方依据：[Android 集成测试](https://developer.umeng.com/docs/119267/detail/118639)、[测试模式原理与操作](https://developer.umeng.com/docs/119267/detail/119463)、[SDK 本地日志](https://developer.umeng.com/docs/119267/detail/2936723)。

## 官方初始化顺序

已完整阅读基础接入流程、基础功能、进阶功能、合规指引、预初始化 FAQ、无集成测试数据 FAQ、扫码配置、测试模式原理和本地日志文档。本项目采用以下顺序：

1. release Application 在主线程读取已有须知同意记录；只有记录匹配当前正文哈希才允许初始化。
2. 有效同意时，先配置采集限制和手动页面模式，再执行 `preInit`、提交同意结果及完整 `init`；这一步在首个 Activity 创建前完成。
3. 首次没有有效同意时，不调用 `preInit` 或 `init`。用户成功保存同意后，沿用前台路径执行相同初始化。
4. Activity 的 `onResume` / `onPause` 维护基础会话；业务事件继续由前台授权控制。扫码参数由 SDK 自身解析。

[基础功能](https://developer.umeng.com/docs/119267/detail/118588) 的示例在 Application 中预初始化，并读取持久化同意决定完整初始化；[预初始化 FAQ](https://developer.umeng.com/docs/119267/detail/2936740) 和 [集成测试无数据 FAQ](https://ask.umeng.com/preview/faq_1019891299) 均强调 Application 初始化时机。[合规指引](https://developer.umeng.com/docs/147377/detail/210108) 对同意前的限制更严格，要求不调用预初始化或完整初始化；本项目遵循此限制，已同意用户的后续冷启动则在 Application 完成两步初始化。

## 授权与数据边界

`AppAnalytics` 维护本地授权版本及一次操作的临时凭据，不生成或上传安装 ID、会话 ID、订单或时间参数。只有通过有效前台授权开始的操作才能上报；结果可以在操作开始后完成，但撤销前的凭据立即失效。

`UmengAnalyticsBackend` 限制 IMEI、IMSI、ICCID、Wi-Fi MAC、应用列表及安装来源采集，并关闭自动崩溃内容上报。合并清单移除 SDK 自带的 Google 广告标识和 Freeme MSA 权限，不增加电话、定位等运行时权限。设备识别仍由 SDK 使用设备上实际可用的 Android ID、OAID、GUID 等机制完成。

退出校园登录沿用已有须知撤销流程，同步停用统计。官方 `MobclickAgent.disable()` 在所选版本没有公开对称重新启用方法，因此同进程重新同意后继续停用，下次冷启动再根据同意记录初始化。停用保证不再新增本应用统计，不能取消在途请求或保证清空 SDK 队列，也不会删除后台历史。

## 计数边界

- 付款码使用 `payment_code_fetched`（成功获取付款码）：实际取码请求成功返回有效码时，每个请求计一次，覆盖首次取码、手动/到期刷新和登录后的取码。一批返回多个码仍计一次；缓存重显、页面返回、倒计时和条形码/二维码重复渲染不制造事件，扫码返回只有实际重新取码成功才增加。取消、过期回调和授权撤销后的结果不计入，不采集付款码内容。连续无法亮码只计一次故障，显式重试或成功恢复后允许新故障。
- 账单明细/统计真正切换和进入时计访问。实时搜索输入及防抖请求不上报，键盘确认搜索和明确清除才计事件；筛选与日期确认只传分类。
- 充值/缴费在校验通过且开始提交时计数，不把准备支付、输入密码、微信回跳或轮询视为新的提交。
- 接水/淋浴/798用水在设备与选项验证后，开始发送启动操作时计提交；失败只取启动请求的明确错误，不把后续等待状态不明确计作失败。
- 洗衣在实际发起余额支付或第三方支付准备时计提交；取消及等待支付不算失败，启动前查询和订单恢复不增加付款提交。
- 手动登录在明确结果时计一次，随后设备或订单恢复失败不会把已成功登录改成失败。
- 更新必须发生实际网络下载才计结果，成功包含完整性和身份校验；重用缓存 APK、内部多源重试、安装权限页或安装取消不制造下载结果。
- 成功打开系统安装流程才计安装交接，不宣称系统已完成安装。

## 验证与剩余工作

付款码流程与共享统计已通过 23 项定向测试（共享统计 17 项、付款码流程 6 项），覆盖首次/手动/到期取码、每个成功请求计数、同一回调去重、同一响应多码、不同请求返回相同码、扫码返回、缓存重显、仅查账户、登录取码，以及失败、取消与撤销授权边界。验证使用本地记录实现，不发送友盟事件；实机上传仍需使用启用统计的发布包验收。

此前已通过 20 项定向检查，覆盖授权前/预览拦截、撤销与旧回调、页面/结果去重、19 个事件与受控字段、充值/缴费有效提交、更新发现及既有须知流程。该验证完成了 debug 源码与单元测试编译，不包含实机发送、正式 APK 混淆构建或后台数据核对；未运行全量测试或 Lint。

本轮按构建分别通过 5 项定向验证（debug 3 项、preview 1 项、release 1 项）：SDK 类只在 release 可用，debug / preview 的统计实现保持停用，同意前与撤销后的拦截仍有效。三个构建的源码和单元测试编译均通过；同时核对生成的 BuildConfig 与合并清单，确认正式配置及独立扫码入口仅用于 release。此次未进行完整 APK 构建或 Lint。

初始化时机修正另通过 9 项定向检查（debug 7 项、release 1 项、preview 1 项）：已有同意可在前台之前初始化，但不能开始业务事件；首次未同意时等待接受；提前初始化后撤销仍能停用；恢复前台不重复初始化。release 测试使用清单实际选择的 Application，验证旧须知记录不授权、当前持久化同意会在 `onCreate()` 初始化；debug / preview 不使用该 Application。三个构建的相关编译与测试均通过，未追加全量测试、APK 打包或 Lint。用户反馈旧包第二次扫码后已显示数据，本轮修正后的冷启动首次扫码及实际上传尚未实机验收，当前没有连接设备。

本次改为仅 release 依赖 SDK，使用正式 Key 和 `official` 渠道；debug 与 preview 的 Key 和渠道均为空，使用停用实现。当前未连接 Android 测试设备。

本机正式 AppKey 和渠道配置已齐备，CLI 登录及 OpenAPI 已开通。连接测试设备后，用正式构建确认同意前无初始化/上传、同意后扫码进入官方集成测试并核对实时日志；退出测试模式后的正常使用数据再核对次日后台启动和事件报表，历史累计数据不作为本轮发送证据。

2026-11-12 已通过 OpenAPI 注册正式应用的 19 个普通计数事件，回查确认所有英文 ID 与中文显示名称一致。创建定义不等于 SDK 已发送数据，参数报表与实际免费额度仍需后台核验。

创建接口的中文 `eventDisplayName` 必须先对参数值做 URL 编码，再交由 CLI 发送。原始中文会在内部请求中触发 `Invalid query`；本次使用 `[Uri]::EscapeDataString` 后创建成功，并以回查得到正确中文名称作为验证依据。
