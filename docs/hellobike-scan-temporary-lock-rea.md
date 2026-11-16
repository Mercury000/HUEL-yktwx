# 哈啰扫码自动开锁、订单标识与临时锁车

2026-11-16。核对对象仍为用户指定的 `D:\mercu\Downloads\哈啰_7.0.75.apk`，SHA-256 为 `7e7362c0099069334be458347e567b87eb9baf0ab0b1f4ea8a410246d3e7c62b`。使用 REA 分析原 APK 的逐字节相同 DEX 切片和默认入口指向的官方 H5；没有分析本项目 APK，没有发送车辆操作或支付请求。

用户已实测确认：官方 APP 在其测试场景下也会扫一扫直接开锁。此前将“扫码后核对计费并点击开锁”描述为通用步骤不准确。此前又添加了“缺少 `rideGuid` 就从同车骑行信息恢复后查单”的提前兜底，未取得对应官方调用依据，已撤销。整个已接入实现的本次修改见[业务对齐记录](hellobike-business-alignment-rea.md)。

## 扫码与自动开锁

REA 再次取得 `com.hellobike.bike.core.scan.scanservice.BikeScanOpenLockService.m` 的完整方法。其默认路由仍沿既有[入口证据](hellobike-scan-chain-rea.md)进入 H5 确认页。默认页面发送 `user.ride.pre.ride`，这一请求本身可能执行服务端开锁。

H5 的 `onAutoConfirmUnlock` 根据 `skipConfirmResult.canSkip` 和 `executor` 分流。SERVER=1 时，`autoUnlock` 直接显示开锁进度、查单并尝试蓝牙；APP=2 时进入 `onConfirmUnlock` 创建。只有未获得自动确认且官方要求用户确认的分支才需要确认按钮。当前普通自动分支不再显示第二次开锁操作；帮助与初始页面说明“扫一扫会按哈啰规则直接开锁”。

自动开锁是否发生不能用本机没有发送 `user.ride.create` 来判断。预骑行已执行服务端开锁时，重复发送 create 会破坏原订单恢复。

## 标识的来源与用途

| 字段 | 官方取值和传递 | 当前处理 |
| --- | --- | --- |
| `scanReqId` / `scan_req_id` | 扫码请求跟踪字段 | 不用它当订单标识 |
| 预骑行的 `orderGuid` | APK 原生 `PreRideCreateResult` 的字段；REA 取得 `getOrderGuid` 完整方法 | 不因这个字段非空就认定存在已经开始的骑行，不直接替代缺失的 H5 `rideGuid` |
| 预骑行的 `rideGuid` | H5 SERVER 自动分支复制到 `options.orderGuid`，再传给 `user.ride.order.status` | 返回时直接查询；缺少时仍保留已接受的开锁操作 |
| 创建返回的 `rideId` | H5 `parseResult` 保存到 `options.orderGuid` 后查询 | 使用创建实际返回的标识 |
| 骑行页的 `rideInfo.originGuid` | `user.tw.ride.check` 返回；`adaptTwRideCheckToBikeOrder` 原样映射为 `ride.order.orderGuid` | 原查单明确开锁成功后读取骑行页，保留该页实际返回字段；不拿它提前替代失败或缺标识的查单 |

这些标识由服务端提供，是不透明字符串。官方上述赋值、映射和查单链原样传递；旧实现自行剥除 `TW` 前缀的处理已移除。不会通过补前缀、去前缀或生成 UUID 来制造云端订单。

SERVER 响应缺少 `rideGuid` 不再抛出“服务端开锁结果缺少 rideGuid”。官方 `autoUnlock` 仍显示进度并发起 `user.ride.order.status`，未定义标识在 JSON 中省略；创建成功但未返回 `rideId` 时同样继续查单。只有查单明确成功才进入骑行信息阶段。骑行页按非空 `rideInfo` 与 `rideStatus!=2` 展示，不额外要求标识、状态或车号齐全。失败及缺字段不触发再次 pre.ride/create，也不按“同车号”替本地待开锁记录绑定另一笔骑行。

## 单车临时锁车与继续骑行

完整补读官方 `useTempLock`、骑行页订单适配、按钮条件、`LoopRideState`、确认弹窗和请求封装。没有套用电单车的 `user.ev.ride.normal.pause/resume`。

| 阶段 | 官方普通单车行为 | 当前实现 |
| --- | --- | --- |
| 入口 | 骑行页 `rideFunctionSettings` 含 TEMP_LOCK=0；`rideInfo.rideStatus` 为临时锁车时切换解锁入口 | 由官方能力字段提供临时锁车；锁车状态提供“继续骑行”和还车 |
| 校验 | `user.ride.pause.verify`，发送车号、定位、cityCode、adCode | 按骑行页入口提交一次云端校验；不在前面另查骑行添加资格门槛 |
| 校验结果 | `result=0` 可确认，`1` 次数用完，`2` 禁停区禁止；`canPauseTime` 以秒传入确认显示，分钟向上取整 | 保留数字结果的区别，展示服务端时限，不发明固定次数或价格 |
| 用户确认 | 告知锁车期间仍正常计费，时间结束后自动开锁 | 使用项目 Miuix 确认弹窗，显示这两项影响，确认后才提交 |
| 临时锁车 | `user.ride.pause`，车号与定位地区字段 | 独立操作，发送前持久化待确认状态 |
| 继续骑行 | 用户点击后 `user.ride.resume`，相同车号与定位地区字段 | 使用原骑行恢复，零 create，不创建新骑行 |
| 核对结果 | `LoopRideState` 使用 `user.tw.order.check`，约 3 秒轮询，35 秒窗口；另有 socket 通知 | 发送官方的 bizType、定位及地区字段，删除自行添加的 rideGuid；明确核对状态后更新界面 |
| 状态 | 本套 `rideStatus=0` 骑行，`1` 临时锁车，`2` 结束 | 保留原骑行与参考计时；没有把临时锁车当还车或结算 |

以上 `rideStatus=1` 属于 H5 骑行状态空间，与原生 `user.ride.bikeCheck` 的 `status=1`（BILLING）不同。账号检查仍采用既有原生接口，骑行页面采用官方 H5 骑行信息。

锁车与恢复期间只在主操作区显示一个进度指示。临时锁车与还车使用同一行的次操作／主操作布局；已临时锁车时替换为还车／继续骑行。状态切换会替换按钮操作身份，旧按压不能转成另一项操作。定位权限由用户操作触发，拒绝后再次授权返回继续原校验，不误走还车。

响应丢失、切换页面或恢复进程时，持久化的待确认操作只触发查单。明确读到目标状态才清除待确认操作。服务端自动解除临时锁车后，下次读取恢复骑行界面；本机计时器不自行发送 resume。

SERVER 缺标识的分支复用扫码前的本地记录，不再另外生成一条空云端标识的记录。已确认临时锁车按官方顺序只做一次资格校验，再确认、提交和查询；瞬时查询失败仍在 35 秒窗口内继续查询，窗口结束保留待确认操作。确认弹窗、锁车／继续骑行、检查中的单个进度及浅深色布局已经截图复核。

## 证据与验证边界

[本次证据归档](evidence/hellobike-scan-temporary-lock.json)包含 3 条 REA 原生结果、官方脚本哈希、REA 图证据及 12 段完整分支原文和偏移；入口既有证据继续保留。整 APK 直接分析因 Java 堆内存不足未产出方法，随后核对原 APK 身份及 DEX 逐字节一致，再以 REA 取得切片方法。失败结果不作为代码证据。

官方 H5 `latest` 可变，本次分析沿用已取得的官方资源，不能据此锁定其他时刻下发版本。REA 静态语义图存在动态关系未知及保留上限，完整分支原文补充这些关系；不使用未知关系来证明无其他行为。

本文件的旧“同车 originGuid 提前恢复”断言已由本次查单成功后进入骑行页的断言替换，原始历史证据不改写。验证结果和完整源码补证见[业务对齐记录](hellobike-business-alignment-rea.md)，日志位于 `build/hellobike-card-list-review/scan-pause-review/`。没有本次新版本实车验证；蓝牙、风险认证与其他特殊官方交互仍按既有边界交回官方处理。
