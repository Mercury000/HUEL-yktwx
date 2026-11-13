# 哈啰整个已接入实现的业务对齐与实际修改

2026-11-16。对象是用户指定的 `D:\mercu\Downloads\哈啰_7.0.75.apk`，SHA-256：`7e7362c0099069334be458347e567b87eb9baf0ab0b1f4ea8a410246d3e7c62b`。以下覆盖登录、定位、我的卡、认证、普通和校园商品、预下单、扫码开锁、骑行、还车、临时锁车及付款的当前接入源码；本次直接修改实现和对应测试。

处理原则：官方客户端有判断就接其实际消费者和分支；由云端决定的资格、订单、价格和状态不加本地业务门槛。客户端负责请求所属账户、页面与取消关联，保存待确认操作，以及 Android 权限和界面反馈。

## 本次撤销或修正的业务差异

| 原实现的差异 | 修改后的行为 | 官方依据 |
| --- | --- | --- |
| SERVER 开锁缺 `rideGuid`、create 成功缺 `rideId` 时额外判失败 | 成功响应继续官方查单；缺省标识在 JSON 中省略，不重发 create | `autoUnlock`、`parseResult`、`orderStatusCheck` 与骑行请求包装器 |
| 缺标识或创建失败时，先读取同车 `originGuid`，把失败改成恢复成功 | 撤销提前兜底；原查单明确成功后才进入骑行页。独立发现的当前骑行不绑定另一条本地待开锁记录 | `checkOrderStatus → onUnlockSuccess → BottomBtn`；成功路由与骑行页消费者分别核对 |
| 将 `rideGuid`、`originGuid`、`orderGuid` 的相等当作通用成功条件 | 按各接口实际用途原样传递，不建立跨字段相等规则 | H5 赋值与 `adaptTwRideCheckToBikeOrder` |
| 骑行页要求订单标识、车号及状态都齐全，或只接受本地列出的两个活动状态 | 非空 `rideInfo` 且状态不是 FINISH=2 时展示骑行；缺省字段保持缺省。请求账户绑定响应，不再核对返回业务字段作为资格门槛 | 完整 `runBikeRidingInfo` 和订单适配器 |
| 保存层又限制缺云端 ID 的记录只能处于 Opening | 本地 `sessionId` 独立承担保存身份，成功骑行页缺字段仍可保存 | 宿主保存适配，不充当哈啰资格判断 |
| 开锁查询使用自定间隔、异常后继续自动请求 | 单车首次立即，通常 1 秒；`subStatus=-12` 为 2 秒。查单异常不安排下一次请求，35 秒进度窗口结束 | 完整 `checkOrderStatus`、超时与间隔方法 |
| 还车位置检查及提交前，再查骑行并要求本地确认标记 | 删除额外请求和资格门槛；点击还车后走 P10 | 完整 `P10BikeLock.handleReturnBike` |
| 所有还车都强制第二次确认；P10 处理中改成另一条通用查询 | 1102/1103 读取 `skipConfirm`，true 直接提交、false 显示确认；1101/1108 按相同 `operateType`、`poll=1` 继续 P10 | 完整 `P10BikeLock.handleSuccess` |
| `firstOperate` 未按操作变化传递，窗口使用自定时长 | 同订单首次操作 0，操作类型变化 1，同类型继续 0；位置和关锁阶段各 40 秒 | `getFirstOperate`；`BikeLockStatus.startTimeout` 的 `4e4` |
| 临时锁车确认前后重复查骑行和资格，提交前重新定位 | 入口能力取云端字段，一次 verify、用户确认、pause、状态查询；提交复用已确认位置。继续骑行使用 resume | 完整 `useTempLock`、`LoopRideState` 和请求包装器 |
| 临时锁车的状态查询自行附加 `rideGuid` | 只传官方的 `bizType`、定位及地区字段 | `user.tw.order.check` 包装器 |
| 校园商品、预下单按本地业务类型/渠道范围拒绝；预下单必须单笔、业务类型一致、金额合计相等 | 保留全部云端订单及业务字段，删除这些额外门槛；商品资格交给官方预下单响应 | 校园 Q/H/R/W、普通预下单 wrapper 与原生收银台请求模型 |
| 付款提交前再次查费用/渠道，再由本地金额或标识核对决定拒绝 | 使用用户看到并选中的云端请求，先保存再提交；由付款响应决定结果 | `ConfirmPayHelper.b/c`、`PayCoreConfirmPayHelper.a` |
| 本地重新求和代替请求总金额，或用优惠报价覆盖服务端返回的订单金额 | 原生请求使用 `payMoney` 和原 `payList`；返回订单字段原样保留。`displayAmount` 单独保存渠道报价，结果页优先显示该报价或云端总金额，不自行求和 | 原生付款参数传递链 |
| 同渠道报价去重、子渠道只解析四层；金额固定限制九位/两位小数 | 每个云端报价独立选择；递归解析子渠道；协议保留可解析 decimal，显示时才按分取整 | 渠道树和收银台金额模型 |
| 付款成功要求原始 JSON 是数字、返回 guid/金额/业务字段相等，且全部订单都成功 | 查询指定订单或 `orders[0]`，按 DTO 解析后的 `payStatus==1` 判断；删除附加相等和全单成功条件 | `BasePayModule.i/w → CheckOrderStatusHelper.j/k/l` 完整链 |
| 手动和恢复查询也自动查四次；查询异常可能沿用旧成功标记 | 自动付款查询首次立即、最多三次重查、间隔三秒；手动及恢复单次。查询异常清除本次旧确认，自动按官方规则重试，登录失效停止 | 同一 helper 的自动和单次方法 |

这张表统计的是逻辑差异类别，不能换算为“整个官方 App 只有这些判断”。源码修改集中于 `HellobikeApi`、`HellobikeViewModel`、`HellobikeCardsController`、模型、保存层以及两处哈啰页面。

## 其他已接入部分的来源与当前行为

| 部分 | 当前行为及来源 |
| --- | --- |
| 登录、短信、滑块、失效 | APK 请求、签名和渠道；短信先 captchaType 再 ok；登录 token/guid 建立账户，未初始化 clientId 采用官方空值；只有官方 103/-10003 视为过期。网络错误保留原原因。 |
| 定位、地区 | Android 定位及官方 LBS 反查，不补造城市、不按自定位置年龄、精度或模拟标记拒绝云端业务。扫码可系统请求授权，设置仅由用户点击；返回重新读系统权限。 |
| 我的卡、认证、校园商品 | 卡列表、认证和商品分别读取；authState=2、userType=1 控制校园入口；普通商品独立于学生认证。校园按钮不套用 button.canClick/appLimit。 |
| 普通单车商品 | 从官方钱包路由追到购卡页；101000、platform=0、filterCoBranded=true，读取所有单车商品组和包。没有抓包商品名称或数量白名单；只按官方 canBuy>1 禁购。 |
| 连续包月、普通付款 | paymentLimit=1 接支付宝102和服务端周期规则；普通支付宝001、余额003、银联1201按官方渠道字段处理。默认渠道沿指定默认→enableSelect→余额合格首项，初选不付款。 |
| 扫码自动与确认 | 默认 H5 pre.ride 可能直接服务端开锁；canSkip/executor 决定 SERVER 查单、APP 创建或官方确认。没有再加入通用“扫码后必须点开锁”条件。 |
| UI、权限与恢复 | 共享内联错误和付款组件；同一操作一个进度指示，授权/设置不重复出现在内联和主按钮。待确认记录用于恢复查询，账户/页面变化丢弃旧响应。 |

登录、地区、普通商品和校园来源分别见[协议核验](hellobike-protocol-audit.json)、[条件核对](hellobike-condition-review.md)、[普通商品 REA](hellobike-ordinary-logic-rea.md)、[校园卡](hellobike-campus-cards.md)与[扫码入口](hellobike-scan-chain-rea.md)。旧抓包仅保留历史来源，当前商品逻辑没有用抓包名单决定展示范围。

## 成功跳转与缺标识处理的证据

重新补读当前 `app.d56d75.js.decoded` 的完整模块19742。`Oz` 导出 `RIDING_NATIVE`，实际为 `hellobike://hellobike.com/hb/ride/page/riding_surface`；`n7` 导出 `RIDING_V2`，为 `corePackages/pages/riding/index`。因此不能把 APP 成功回调的 Oz 直接写成 H5 URL。

完整 `BottomBtn` 成功回调通过原生链接或小程序 redirectTo 跳转骑行页。REA 进一步取得原 APK `H5Route.n`：识别 `riding_surface`，按官方路由配置选择 Flutter 或 H5；H5 分支调用 K，K 再进入相应骑行 H5。页面 `runBikeRidingInfo` 读取预取结果或 `user.tw.ride.check`，适配 `originGuid`，按空信息/结束状态处理。

这是正常成功后的页面链，不是“只要缺 rideGuid，就先查同车骑行当作开锁成功”的依据。当前已撤销后者。路由配置及 latest 资源可变；所选方法的反编译警告和动态分支限制保留，不宣称所有配置都固定进入同一页面。

## 证据与验证

[本次证据归档](evidence/hellobike-business-alignment.json)保存官方 APK/DEX 来源哈希、REA 方法完整输出、精确原型、完整 H5 分支与偏移、成功路由模块、图证据及限制。脚本仅作惰性解析，没有执行官方 bundle。没有分析本项目 APK。

整个业务修改的第一批定向验证执行133项；随后对最后变化的付款、卡控制器、缺字段骑行、保存与相关页面执行36项，其中35项通过，新增骑行用例补齐独立的“原查单成功”模拟响应后单独复测通过。最后两项购卡及骑行付款页面复测也通过：共享结果组件直接显示 decimal 金额，不要求转换为受 Long 上限限制的分值；其他调用者保留既有显示方式。旧断言中依赖已撤销本地门槛的用例已改为校验官方请求顺序和返回消费。日志位于 `build/hellobike-card-list-review/scan-pause-review/whole-business-audit-*.log`；各轮结果独立保留，不把失败日志改写成通过。

最终 `git diff --check` 通过。没有追加全量测试、Lint、安装、真实车辆操作或付款。本地响应与界面测试不能代替本轮实车验证。

蓝牙锁具、风险/人脸认证、调度费接受、特殊签约/复杂组合支付、权益购买引导和完整设备风控初始化仍属于未接入的官方交互范围；其入口或云端要求继续交给官方处理。这里记录整个当前接入的修改，不宣称官方全部功能或所有线上场景等价。
