# 胖乖洗衣下单与支付异常核对

2026-11-07 核对 `胖乖生活_1.145.0.apk`（1.145.0 / 299）。SHA-256：`b0de8fdc4fe0f778b7b9134347b4bb427b73a67fdf223607fec4d9ecdc8bef18`。

主证据来自 REA 原生 Android MCP 的 JADX 方法及成员读取；重新启动请求补充读取原始 DEX。APK 未执行，HAR 仅本地只读解析，没有真实下单、付款、取消或设备控制。现有 HAR 未捕获“30s内不能在同一设备下单”的响应，实际错误 code 未知；测试中的 code=7 是模拟拒绝，不能作为线上 code 的证据。

## 官方行为与本项目处理

| 环节 | 官方可见行为 | 本项目处理及本轮修复 |
| --- | --- | --- |
| 健康检查 | `PayModeFragment` lambda6：health=false 提示 faultDesc；health=true 且 msgId 空直接继续，非空才查询设备同步。 | 检查失败清除尚未创建订单的本地记录、退出忙碌，保留付款表单和错误。成功健康检查允许空 msgId。 |
| 连接超时 | 同类 lambda8 每 500ms 检查；耗尽提示连接超时，停止创建。 | 保留有界检查。重试重新走下单及支付，不再只建立订单却不继续付款。 |
| 创建失败 | `OrderPayPreviewVm.createOrder` 使用 async，专用错误 callback 为 Unit；预览页 lambda39 只在拿到非空 OrderCreate 后继续付款。 | 已解析的 API envelope 带数值非成功 code，或 HTTP 401/403 明确拒绝认证时，清理未建单记录、显示原错误、允许重试。其他 HTTP、传输、解析或成功响应缺订单字段仍视为结果未知。 |
| 创建结果未知 | 专用错误 callback 没有显式回滚。不能据此认定官方完全没有通用异常处理。 | 保留 createSent 防重记录；关闭付款抽屉、退出忙碌，显示“下单结果未知”，禁用无效重试。重新扫码相同设备也不能重复建单。 |
| 预支付失败 | `PayModeVm.prePay` 使用 async，传原 orderNo、payType、againFlag；没有独立自定义错误 callback。 | 清除停留在 Creating 的前台反馈，回到原订单付款表单。检查成功前不改写后续支付查询渠道。 |
| 钱包异常 | VM 错误 callback 发布 orderId=-1，Fragment 走 orderPayCancel。 | 业务拒绝显示失败并允许原订单续付；丢响应显示待查询，只读查询原订单，避免再次扣款。 |
| SDK取消 | 支付宝 false 走 orderPayCancel；预览页 payCancel 携原 tradeNo/orderId/payType 进入结果页、刷新订单列表、结束预览页。此处没有取消订单 API。 | 保留同订单续付和显式取消入口。SDK取消不能直接认定订单关闭；既有支付宝交接超时恢复和银联回调查询继续有效。 |
| 查询异常 | `PayStateVm.queryPayStatus` 错误 callback 发布 false；结果页每 1s 有界重查，耗尽进入 state4。 | 保持有界只读查询，失败退出忙碌并允许再查，只有服务端确认才能标记付款成功。 |
| 查询超时文案 | state4 显示“支付失败 / 支付超时，订单已关闭，请重新下单”；查询错误发布 false 后也能到这一分支。 | 不以查询失败推断关闭；保留原订单，只有服务端状态或取消成功确认才能关闭。 |
| 启动确认 | `DeviceStartupAct` lambda42：false 每 1s 有界重查，成功或耗尽后查原订单详情。lambda44 继续有界查询 completeTime，非空显示启动成功。 | 支付和启动分开。启动查询耗尽保留 Starting 和详情入口，空闲状态不持续显示忙碌动画。 |
| 手动重新启动 | lambda44 按 showStart、洗衣/鞋/烘干类别及创建后 180000ms 显示按钮。按钮调用 restart(mainOrderNo)，表单 POST `/trade/order/start`，唯一业务参数为原 orderNo，然后刷新详情；失败 callback 也刷新原订单。按钮还会在 1s 后直接提示“指令下发成功”，该提示没有等待请求结果。 | 当前入口明确标为“查询启动结果”，重启 App 后也能查询；尚未接入重新下发启动指令，这是已确认的接入差异。 |
| 主动取消 | `OrderDetailsViewModel.orderCancel` 成功才发布取消结果，未配置专用错误 callback。 | 取消前查询原订单并检查付款；拒绝或丢响应保留订单，重试先确认是否已经关闭或付款，不因网络异常自行关闭。 |

通用 `BaseViewModelExtKt.parse` 可见关闭 loading、业务解析异常 Toast、异常 callback 和 HTTP 错误上报。不过创建和预支付调用的是 async；其同名匿名协程主体尚未通过 REA 完整解析，不能把 parse 的全部界面恢复行为直接归到 createOrder/prePay。官方取消异常的全部界面恢复细节也未完整确认。

## 小岛关闭范围

- 尚未建单的检查失败、明确拒单：自动清理本地进度、下岛。
- 没有订单号且创建结果未知：异常提醒提供 ×，仅持久化隐藏，不删除 createSent、不调用取消、不标完成或关闭。重启后保持隐藏，重新扫码同设备仍受防重保护。
- 已有订单可进详情继续支付、取消或查询：不提供异常 ×，保留原订单入口。
- Closed 自动下岛；Ended 保留至完成日次日北京时间 03:00，可手动移除；Running 不因预计时间归零而隐藏。

## 证据

REA bundle：`C:/Users/mercu/.codex/visualizations/2026/11/07/01a1b4db-1696-7683-aec4-4f333c5333e0/panggui-order-errors-evidence.json`。其中包含原文、表示形式及静态观察限制。

| 观察 | evidence_id |
| --- | --- |
| 健康检查 lambda6 | `ev_4bc56f7ed330f4fe2c20d7e4f3dd7bf573f90961f48d029a7965b5768b07e790` |
| 连接超时 lambda8 | `ev_777fa3c90aa6bce2b52f82ce66e0b4fb7b657daa12a8eaeaed1df1ba680cfbc0` |
| 创建及错误 callback | `ev_ee45d9fd16dde1fe7867a597e3f674a08a39940b363265023e1bd7efb05176a6` / `ev_d470d791311587485c09f99531223d777e08b61cf981cbbc8d60d78ead81a22a` |
| 创建结果进入支付 | `ev_05b4ff4d21bd22bef04db0ea538189bc1c149c75c78ac896472436546ad3fa12` |
| 预支付 | `ev_f09455ee12a660d6ae20536f2712dfa5a8e46fd61fa87f73a18198342f0851ed` |
| 钱包错误及结果分支 | `ev_1965622998fbb6c2e1ce3dd533190b1c600c69d6ee3ded37b2b6e512e13d6aee` / `ev_abc774224fc9e40d94be70387a10972872e6d7240a0f94af665af7ecda7b9d12` |
| SDK取消后的原订单结果页 | `ev_1f89411c6d07f29d7275b35fffad94d9c95dde244f647bcf9b016d2ca96e2e65` |
| 支付查询错误、轮询及超时文案 | `ev_1d4be162f5ce7d6eb8e342c44991515eb3a4c68d8d48aee3257ae24ceced7c5b` / `ev_eafccbff2d7cdb0ec5829e72f6db7703b20a8b35952c8cf795fd2c6645dccf09` / `ev_4e40ddd4f802339ac7072dac1443ce9f1438d47e631f79d13656b16557b5e226` |
| 启动确认及订单详情 | `ev_da018c8724fee2df0e68ec8c5fdd507f2ce3dedee87f1f1d4b8701e1bf49d340` / `ev_7c29f03d1ae43ecbe993bf08ca46c3991b551d4de7edf0552b1c6bdda269cb4d` |
| 重新启动按钮及 VM | `ev_5f2648305b4c780f21ce20ff7b47a9ab583af3fdffef630dcbb339a94dcb2cc3` / `ev_149650a2ecbdd6a24c256867b3d4ba655d8d1d0235abb27e4fe54d4591690f90` |
| 重新启动失败后查询原订单 | `ev_43a804c7eb5176dcae4ebce5190288e6dfec40c84ef0e5d7b7d8126e15dd0aab` |
| 取消订单 | `ev_3c1629307481f6d75e4eeb9640840abe4fcef2a2da94a12aa6385c2a1ff80e9e` |
| 通用 parse | `ev_a46641a5052f9945b6724f8eecb574eda01608b45b928b64a114581a2f82f444` |

原始 DEX 补充（不是 REA 返回的注解）：`read-order-restart.ps1` / `panggui-order-restart-dex.txt`，与 bundle 同目录。ApiService.orderStart 位于 classes11.dex、method index 39270、POST annotation offset 7003997；请求参数由 classes12.dex 的 `DeviceStartupVm$restart$1.invokeSuspend`（method index 28332、code offset 3287104）确认。lambda44 的原始常量为 180000ms（method index 15019、code offset 2263232）。

## 验证

覆盖洗衣 ViewModel 的创建/支付/查询/启动/取消共享路径，以及新增 API 分类、空 msgId、持久化隐藏、小岛 ×、未知结果 UI、恢复后启动查询和直接共享的认证路径。首轮发现重开付款页会清掉设备检查错误，修复后复验该入口及报价、拒单、同订单续付相关 6 项通过；补充启动入口和未知结果 UI 的 2 项、认证及拒单分类的 3 项通过。未运行全量项目测试、Lint 或真机支付。
