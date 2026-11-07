# 胖乖淋浴接入

核验日期：2026-11-07（Asia/Shanghai）。离线分析用户提供的 `淋浴.har` 和胖乖生活 1.145.0 APK，未重放请求或操作设备。抓包与实际账号、设备、订单标识不入仓。

APK SHA-256：`b0de8fdc4fe0f778b7b9134347b4bb427b73a67fdf223607fec4d9ecdc8bef18`。
HAR SHA-256：`a8e96b4e651a027bcc9acad58cbb8819df927a4df98229e8026db46bf57f79e0`。

## 已确认的设备与协议

抓包共 253 条请求，包含一次淋浴使用和随后扫码饮水设备。淋浴使用 `SN` 扫码，服务端返回 `categoryCode=08`、`payment=after`、`isBluetooth=0`、`supportAppUserFinish=true`。本次只有一个可用出水口，SKU 的 `price=0.00` 不代表整次淋浴免费，`unit=33.333` 的单位未知，不据此显示费用或水量。

| 环节 | 接口 | 参数和确认依据 |
| --- | --- | --- |
| 扫码 | `/goods/scan/v2` | `SN`；接着核对详情 goodsId 与类别 |
| 后付渠道 | `/payChannelRoute/afterPay` | `imei`、`useTicket=0`、扫码返回的 `shopId`、`goodsCategory=08` |
| 选择免密 | `/payChannelRoute/addUserAfterPayChannel` | 本次实际选择 `method=15`，支付宝免密 |
| 启动 | `/goods/shower/unlock` | `skuId`、`promotions`；返回 msgId、orderNo |
| 命令确认 | `/machine/msg/sync` | 先 null，后 `state=OK`、`code=0` |
| 初次查询 | `/goods/shower/sync` | `skuId`；本次返回 `status=1`、`workStatus=2` |
| 后续查询 | `/goods/water/sync` | 同一 skuId，identify 始终匹配淋浴启动的 orderNo |
| 主动结束 | `/shower/goods/remoteEnd` | APK 声明的 POST 路径；`goodsId`，可带 `orderNo`；必须返回 true，再查询实际状态 |
| 非零后付 | `/order/afterPay/creating`、`/order/sync` | 复用已有后付订单处理；本次 HAR 未出现这两项 |

`/goods/shower/getAudioFlag` 在本次返回 false。声音功能与蓝牙指令暂未接入。

渠道同时返回 `15`（支付宝免密）和 `53`（云闪付免密）。此次仅接入已开通、可用的支付宝免密；`53` 不能作为洗衣普通银联支付 `52` 调用 SDK。没有可用的 `15` 时显示具体原因，不发送启动命令。

## REA/JADX 的静态依据

反编译通过 REA 的 `inspect_android_method`、`inspect_android_class` 和 `trace_android_references` 完成，使用 JADX headless MCP，没有运行 APK。

- `DeviceStartupAct.initData` 按饮水/淋浴类别及蓝牙标志选择不同 unlock。
- `DeviceStartupAct.unlockSuccess` 对淋浴先调用 showerSync。
- `createObserver$lambda$38$lambda$37` 后续调用 waterSync，与 HAR 相符。
- `createObserver$lambda$38` 将淋浴同步的 status 3 或 5 判为使用结束；正用量且 identify 非空时 afterPay，status 6 则提示设备异常。
- `DeviceStartupAct.EventListener.orderBtn` 的淋浴按钮调用 `showerEnd(goodsId, "")`。
- `DeviceStartupVm.showerEnd` 必传 goodsId，orderNo 非空时也加入参数。项目在主动结束前匹配当前订单，并传入保存的 orderNo。
- `createObserver$lambda$39` 将 afterPay 返回的订单交给既有付款状态页。

REA 对抽象的 `ApiService.showerEnd` 返回空方法体，未提供 Retrofit 注解。为避免猜测，补充只读解析 APK 的原始 DEX 注解：`classes11.dex` 的 showerEnd method index 39334、annotation offset 7002022，POST 的原值为 `shower/goods/remoteEnd`。同一方式核对了 showerSync 与 showerUnlock 的路径，与 HAR 一致。此补充属于原始声明读取，不能声称是 REA 返回的注解，也不能证明接口已在本机实测。

REA 证据包保存在本次分析的本地可视化目录 `panggui-shower-evidence.json`，未将原始目标内容入仓。

## 项目行为

淋浴和饮水使用相同的 SN 格式，扫码后由服务端的 `04`/`08` 区分。复用现有 WaterSession 管理，保存完整设备类别，页面、出水口选择、开始/结束按钮及小岛按类别显示淋浴。未识别的二维码或未知设备类别显示“未知设备”。

启动前检查后付、非蓝牙、可用 SKU 和支付宝免密；先保存启动上下文，再发送一次 shower unlock。响应丢失不能再建立订单。命令确认后首次查询 shower sync，正在使用后改用 water sync，每 1.5 秒轮询。主动结束前重新核对 identify；命令成功不等于设备已结束。订单已变化时移除旧跟踪，不操作或结算其他订单。

仅匹配当前订单的 status 3/5 确认淋浴结束。遵循官方正用量分支，明确正用量才进入后付创建与同步；本次抓包的零用量结束跳过后付。status 6 显示异常，保留当前未确认结束的进度，不显示完成或创建付款。饮水仍使用原有 status 5/workStatus 1 与明确零用量规则，详见 `life-devices-water.md`。

淋浴未结束时在付款页保留小岛，可返回对应订单结束；确认结束后隐藏。冷启动先以 shower sync 单次核对保存订单，仅恢复匹配且正在使用的会话，随后按 water sync 轮询，不重放启动、结束或付款请求。恢复失败的旧记录按既有用水策略丢弃，不能发现官方 APP 新建的订单。

## 验证边界

抓包实际覆盖扫码、免密选择、启动确认、使用中查询及零用量结束；没有主动结束请求或非零扣款，不能据此认定这两种情况已通过真实设备验证。主动结束依据 APK 方法分支、参数及原始接口注解；非零后付依据共享官方流程及已有饮水实现。

定向测试覆盖淋浴类别路由、出水口与渠道检查、请求路径/参数/签名、结束确认、零/正用量、status 3 与故障 6、响应丢失、其他订单隔离、冷启动恢复、淋浴页面及小岛；同时覆盖直接共享的饮水启动/结束、轮询、恢复及用量解析。所有验证使用离线响应或测试替身。
