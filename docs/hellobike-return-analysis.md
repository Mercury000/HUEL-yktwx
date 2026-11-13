# 哈啰调度费与蓝牙还车 REA 核验

日期：2026-11-13。按本次要求先核验官方 APK 的附加费用和蓝牙还车，不修改骑行业务实现。

当前校园接入范围：用户提供的使用条件为仅在校内使用、车辆无法出校、无额外费用且没有蓝牙车；这些是使用前提，不是本次静态核验对校园或平台规则的确认。当前需求为普通网络开还车、正常骑行费结算及订单恢复／查询。下文调度费接受和蓝牙还车证据保留作后续参考，不列为当前接入要求；现有异常条件检查和「在哈啰处理」入口继续保留。

**结论：官方 7.0.75 明确包含停车相关的“调度费”场景、费用接受请求，以及实际蓝牙还车执行和结果上报代码。费用确认和蓝牙操作都有进一步接入的具体依据。当前证据来自静态代码；收费订单、整合客户端的接口可用性、实车锁具及最终结清仍未验证。**

已确认同属调度费的不同停车场景，不能据此认定存在三个分别核实的收费项目。

## 1. 已确认的费用名称和场景

REA 取得 `com.hellobike.bike.ubt.BikeClickUbtEvents` 以下方法的完整方法体。这些是官方客户端的页面／操作事件名称，能证明场景和命名存在；不直接证明收费规则、当前页面布局或真实账单。

| 方法 | 官方代码原文 | 已确认的场景 |
| --- | --- | --- |
| `getCLICK_APPEAL_FORBIDDEN()` | `App_禁停区调度费说明页面`、`APP_没有禁停，我要申诉` | 禁停区相关调度费说明及申诉 |
| `getCLICK_APPEAL_NORM_PARK()` | `App_规范停车区调度费说明页面`、`APP_规范停车区，我要申诉` | 规范停车要求相关调度费说明及申诉 |
| `getCLICK_APPEAL_OUT_SERVICE()` | `App_超区调度费说明页面`、`APP_没有超区，我要申诉` | 超出服务区域相关调度费说明及申诉 |
| `BikeReportBtnLogEvents.getAgreementDispatchBTN()` | `APP_还车异常-调度费收取`、`APP_接受调度费` | 接受调度费的操作标识 |

同一原始 DEX 还保存了 `APP_接受调度费，关闭车锁` 和高校超区调度费调价公告等事件字符串。REA 方法证据与这些原始字节字符串分别归档，后者不冒充完整界面或收费执行证据。

## 2. 费用字段和接受操作

REA 的原生还车实体成员证据确认：

- `PreCloseEntity` 提供 `penalty`、`penaltyFree`。
- `PreCloseForP10Entity` 提供 `penaltyDisplay: Float`、`penaltyFree: Integer`、`causeDesc`、`hideDispatchReturn`、`recommendParkInfo`。
- `PreCloseForP10Entity.isHideDispatchReturn()` 在 `hideDispatchReturn == 1` 时返回 true。后续开放收费还车选项必须核对该服务端条件。

`penaltyFree` 的具体取值语义、金额单位、收费额度、减免规则，以及费用如何并入或拆分付款单，仍缺少成功业务响应证据。本次不根据字段名称补写固定价格或统一收费规则。在上述两个实体中，也没有确认当前接入代码兼容判断所使用的 `extraFee`、`dispatchFee` 是对应协议的正式字段。

REA 确认以下请求模型的字段；action 和整数常量另由同一原始 DEX 的注解及 encoded static values 补证。

| 协议模型 | 原 DEX 的 action | 已确认的参数／常量 |
| --- | --- | --- |
| `ManhattanAcceptPenaltyApiModel` | `user.norm.receiveForbiddenCost` | `orderGuid`、`operateType`。本模型的 `operateType` 具体取值没有调用方补证，不能套用下一行的常量。 |
| `ManhattanP10PreCloseApiModel` | `ride.hub.pre.close` | `DEFAULT=0`、`ACCEPT_PENALTY=1`、`CONFIRM_RETURN_BIKE=2`、`RED_RETURN_BIKE=3`；另有 `orderGuid`、定位、`hasIllegalEdu` 等上下文字段。 |

P10 模型将“接受费用”和“确认还车”定义为不同操作。后续实现需要展示本次服务端条件和费用，由用户明确选择，再按对应协议提交并核对原订单。当前拦截条件之外，还缺少这段完整业务流程。

## 3. 蓝牙还车的实际执行链

REA 取得 `com.hellobike.bike.business.riding.rideinfo.ble.ReturnBikeByBleAction` 多个完整方法体，确认了实际执行行为。

| 阶段 | 官方实现 | 已取得的代码证据 |
| --- | --- | --- |
| 接收上下文并扫描 | `V(bikeNo, orderId, bluetoothKey, bluetoothKeyList)` | 保存车辆、订单及单串／指令列表，再按车号启动扫描；扫描器接收 `30000L` 时间参数。找到设备后调用连接方法。 |
| 连接 | `F()` | 构造 `ConnectTO` 并调用 `BleHelper.b()`；连接异常进入失败回调。 |
| 选择锁型 | 构造方法，明确选择 overload 0 | 复杂锁使用 `BleForthComplexLock`；另一分支使用 `BleFifthLock`。 |
| 指令准备和写入 | `I()`、`g0()` | 非复杂锁将单串按 20 个字符分段并转 UTF-8；复杂锁使用指令列表并经 `HexUtils.b()` 转字节。构造 `WriteTO` 后调用实际写入。 |
| 读取／接收通知 | `P()`、`a0()`、`b0()` | 主动读取与通知均有实现；通知数据按原文去重后上报。写入成功只进入后续读取，不直接等同于骑行结束。 |
| 上报反馈 | `S()`、`reportBleData` 协程、`T()`、`U()` | 上报实际反馈，按照骑行业务配置选择不同请求和结果处理分支。 |
| 处理结果 | `reportBleData` 协程 | 按服务端 `resultType` 分支触发回调；有继续反馈上报、结束前一订单等不同处理。恢复业务仍需核对正在处理的原订单。 |

`ReportBikePositionByBleAction.K()` 另外确认了蓝牙停车位置数据的上报：使用 `operateType=1`，携带 `bikeNo`、`orderGuid`、`bleResult` 和根据锁型选择的 `commandType`。它与关锁反馈不是同一个操作。

同一原 DEX 的模型注解确认了 `user.ride.mht.bleReport`、`user.ride.hub.bleReport` 两条上报 action。`ReturnBikeByBleAction` 的 MHT 上报分支使用 `operateType=2`；停车位置分支使用 `1`。P10 的后续反馈还会进入通用 `BleReportApiModel` 分支，不能机械地把 MHT 的完整请求套到 P10。

通用模型的原 DEX action 是 `user.ride.bleReport`。REA 确认 `U()` 为该分支设置 `readResult="2"`、`operationType=1`、`commandType=2`、`bikeNo` 和实际反馈 `result`；`reportDataFinishOrder` 协程调用 `BikeOpenLockNetService.f()`。服务端返回 `BIKE_LOCK_OPEN` 时显示提示，`FINISH_LAST_ORDER_SUCCESS` 时触发成功回调并关闭蓝牙操作；该协程对 `RETURN_BIKE_SUCCESS` 不触发上述完结回调，其余结果进入失败处理。该模型没有前述 MHT 上报的 `orderGuid` 字段，恢复时仍须查询保留的原骑行标识，不能用通用反馈替代订单归属核验。

原 DEX 的 `BleReport` 常量补证确认 `RETURN_BIKE_SUCCESS=5`、`RETURN_BIKE_FAIL=6`、`IN_BN_PARK=7`、`OUT_BN_PARK=8`、`FINISH_LAST_ORDER_SUCCESS=12` 等不同结果。官方协程对多个结果有不同回调，迁入时必须保留区别，费用结清继续单独核对。

APK 同时包含较新的通用 `RideBleConnector`。REA 确认 `scanBleAndConnection()` 检查蓝牙可用及连接状态，`writeBleInfo()` 在需要时先连接，再由 `bleDataExchange()` 处理服务端提供的 `writtenData` 和回调。这是另一套业务封装，仍须按车辆配置选择，不能把它与上面的旧封装任意拼接。

底层 `HBCommandCoder.b()` 和 `c()` 也已取得方法体：前者区分单包、首包 13 字节有效载荷及后续最多 15 字节有效载荷；后者将已经打包的字节列表直接包装。它们是不同的编码入口，不能把普通分包规则套到服务端已提供的复杂锁包上。完整 GATT 特征选择、所有锁型校验及实车兼容性不在本次已完成覆盖之列。

## 4. 官方两套状态模型需要分别解释

以下是原生模型的常量表，不是对原型 H5 当前响应的实测结果。

| 常量名称 | `PreCloseEntity` | `PreCloseForP10Entity` |
| --- | --- | --- |
| `STATE_SUCCESS` | `1` | `1100` |
| `STATE_FAIL` | `3` | `3` |
| `STATE_BIKE_SEARCHING` | `4` | `1101` |
| `IN_FORBIDDEN_AREA` | `1101` | `1107` |
| `OUT_NORMAL_PARK_POINT` | `1102` | `1105` |
| `IN_NORMAL_PARK` | 无对应声明 | `1102` |
| `IN_SERVICE_AREA` | 无对应声明 | `1103` |
| `OUT_SERVICE_AREA` | `1104` | `1104` |
| `TYPE_NET`／`TYPE_SMS`／`TYPE_BT` | `1`／`2`／`3` | `1`／`2`／`3` |

现有 `HellobikeProtocol.closeReading()` 合并读取 `state`／`status`，并以原型的 `state=3, causeType=1103, penaltyFree=1` 作为可确认路径；`1102`、`1104` 的提示也使用同一套解释。本次补证说明，接入原生条件前须先区分协议、车型及状态模型。尚不能用原生常量推定原型 H5 的当前响应，更不能仅增加收费或蓝牙按钮而沿用统一原因映射。

## 5. 后续接入依据与仍缺少的验证

| 范围 | 本次已经确认 | 后续仍需要 |
| --- | --- | --- |
| 调度费确认 | 官方费用场景、接受操作标识、模型字段、P10 接受／确认常量和独立 action | 当前成功响应、收费条件及金额语义、接受后的订单变化、与本笔待付款标识的连接 |
| 蓝牙关锁 | 车辆上下文、扫描连接、锁型分派、指令转换、读／通知、反馈上报、结果分支及低层编码入口 | 当前车辆配置和服务端指令、完整 GATT／锁型适配、权限与生命周期实现、真实车锁和同订单结束确认 |
| 蓝牙停车核验 | 推荐停车点的蓝牙类型判断、独立位置反馈上报 | 实际停车点配置、反馈与允许还车状态的对应关系 |

这两项有可继续实施的具体协议依据；当前财小通版本仍没有费用接受和 BLE 控制实现。真实可用性不能由本次静态核验替代。

## 6. 来源、证据覆盖与保存

- 原 APK：`D:/mercu/Downloads/哈啰_7.0.75.apk`，SHA-256 为 `7e7362c0099069334be458347e567b87eb9baf0ab0b1f4ea8a410246d3e7c62b`。
- REA 先使用原有支付切片，再增加原 APK 的 `classes9.dex`，与 `classes18.dex`、`classes7.dex` 及原 Manifest／资源表组成本次还车切片。每个复制条目均按字节与原 APK 比对；未修改 DEX、安装或运行切片。
- 本次保存 43 条 REA Evidence，包括 32 个方法输出、10 次类成员检查及 1 次引用查询。32 个方法的源输出均标记完整；反编译警告另在索引中保留。这只描述所选方法的输出，不代表全 APK 或实际业务覆盖。索引保留精确 DEX prototype、overload index、引擎身份、表示方式、fallback 标记和完整 Evidence ID。
- 原 DEX 字节补证检查了 20 个 DEX 的方法 ID；仅在包含所选目标或字符串的 6 个 DEX 扫描直接指令，记录常量、注解、方法 ID、code-unit 偏移和源范围。直接调用与事件字符串证据不推定反射、动态调用或实际执行。
- `getPenaltyDisplay()` 的 REA using-node 引用查询为零。本次不把这一结果当作全 APK 没有收费流程的证明；Flutter、动态调用、缺失定义及使用节点覆盖仍有边界。
- 记录了一次 `BleOperator.<clinit>` 查找失败；该类没有该方法，随后按可用方法取得构造及相关方法证据。失败请求不计作成功反编译。
- 未请求真实账号、短信、车辆、查单、费用确认或付款接口。没有业务代码变更，不运行构建、Lint 或测试。

可复核文件：[REA 证据](hellobike-return-rea-evidence.json)、[原 DEX 字节证据](hellobike-return-dex-evidence.json)、[证据索引与核验记录](hellobike-return-evidence-index.json)。
