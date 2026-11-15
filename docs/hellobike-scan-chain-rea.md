# 官方哈啰7.0.75：扫码到开锁的完整关键链条

核对对象为用户指定的`D:\mercu\Downloads\哈啰_7.0.75.apk`，SHA-256：`7e7362c0099069334be458347e567b87eb9baf0ab0b1f4ea8a410246d3e7c62b`。核对官方APK和它实际路由到的官方H5，未逆向本项目APK，未执行骑行或支付请求。

结论：**预骑行接口存在，且官方默认扫码链会调用它；它可能直接触发服务端开锁。是否自动开锁取决于官方返回的skipConfirmResult，不能只用本机createCalls=0证明没有开锁，也不能自行规定所有扫码都自动确认。**

## 原APK入口与默认路由

REA确认`BikeScanOpenLockService.a()`在扫码成功后调用`m()`。`m()`构造车号、车型、bizType和OCR参数，调用`H5Route.F()`；该方法经过`OhoH5WebBridgeService.Companion.m()`后检查`H5Utils.r()`。

此APK的`r()`固定调用`callback.invoke(Boolean.TRUE)`，因此进入`H5Route.G()`和`H5RoutePath.p()`。原smali初始化构造路径：

`https://m.hellobike.com/AppHelloMiniBrand/latest/index.html#/corePackages/pages/confirm-unlock/index`

`H5Route.k()`补偿位置、行政区、账户参数、时间、蓝牙授权和网络类型。`H5RoutePath.k()`保留旧pages/confirm-unlock路径，不能因此把它或原生备用链当作默认页。

原生备用链也存在：`j(false) → BikePreRideCreateAction.g0 → f0 → preRideCreateCheck → S → R → listener.d`进入Flutter确认页。原`PreRideCreateApiModel`有`ActionValue("user.ride.pre.ride")`注解，网络服务POST；`RideCreateApiModel`的`user.ride.create`是独立操作。原生S的`result || causeType==1100`不能替代新版H5条件。

classes18／classes7切片与原APK对应DEX逐字节相同，DEX SHA-256为`ae9418d3e03fea7da04ba5a364474dc989b2e7b020616f02cd75b6be0ee6db2b`和`e6f9be8412ccb67020c8bbc0e1718cde94746365d46357b0989c618f9bd0797d`。完整REA方法、Evidence ID和切片身份见[证据归档](evidence/hellobike-scan-chain.json)。反编译失败的方法使用REA完整smali回退，不把失败调用当作方法证据。

## 页面、请求与全部关键分支

页面初始化消费预取结果；没有可用预取时`preCheck → fetchPreScan → getPreScan → getOhoBikePreScan`调用`user.ride.pre.ride`。普通扫码入口没有自行补出的isAutoUnlock。计费信息`tw.bike.unlock.page.basic.info`独立加载，失败不替代预骑行结论。

| 返回结果 | 官方行为 | 当前实现 |
| --- | --- | --- |
| canSkip不为true | 等待用户确认；明确路由isAutoUnlock另有自动分支 | 普通扫码等待确认，不合成isAutoUnlock |
| canSkip=true、executor=1（SERVER） | autoUnlock使用预骑行返回的**rideGuid**查单并尝试BLE，不再发create | 保存rideGuid并查单，零create |
| canSkip=true、其他executor（APP常量为2） | onConfirmUnlock通过其拦截条件后创建 | 消费官方自动确认结果，至多一次create |
| 用户点击普通确认 | onClickConfirmUnlock检查蓝牙及官方交互，再进入onConfirmUnlock | 普通网络分支明确确认；未接入的官方交互转官方处理 |

`isBlockedByPreCheck`检查`result === false && causeType ∈ [1204,1235,1231,1109,1115]`；onConfirmUnlock还检查非空结果及人脸识别结果。页面预处理另外包含directGoto、popups、推荐车辆、提醒、欠费跳转和认证，不能只凭这个五项列表认定可以开锁。

补齐页面依赖的`chunk-pre-scan-components.2ba618.js`后核实scanHandler导航／弹窗／默认分发，以及单车wxHandler完整分支。普通false结果显示阻断说明，不能因为causeType=1100套用原生OR条件。推荐对象、导航对象、弹窗及warnType 1429／1213／1227／1237需要官方交互；1215／1420／1421和强制蓝牙提示也不能当普通文字处理。

本项目既定范围为普通网络骑行。BLE、风险人脸认证、推荐弹窗、欠费导航及显式`user.ride.reCreate`未完整接入，转官方处理；不会伪称这些界面与官方完全等价，不用本机猜测替代认证结果。SERVER结果须先保留订单，避免丢掉已经开始的骑行。

## 创建、查单与恢复

新版H5普通onConfirmUnlock参数没有预骑行orderGuid，parseBikeParams读取未定义值，JSON省略。它同样读取未定义的scanReqId，而scan_req_id用于确认参数的追踪。当前普通create同步省略这两个字段，使用platform=2；pre.ride使用platform=0、force=1、openBluetooth=1和bleStatus=0（本项目没有BLE控制能力）。

userConfirmContinueRide来自路由参数或成功结果的unpaidEntranceMode 2／3；当前普通入口没有该路由参数，按成功结果计算。H5封装从原生UA获取version=7.0.75，h5Version=7.0.70，分别发送。

create的parseResult采用实际返回的**rideId**，再查user.ride.order.status，不能要求等于预骑行orderGuid。成功为rideStatus=20且subStatus不是22／23；22／23继续等待，-12走卡锁提示。官方单车首次立即查单，此后约1秒查询，等待窗口35秒。

本项目在pre.ride发送前保存没有云端ID的本地恢复记录，正常结果返回后删除临时记录。SERVER采用rideGuid；普通／APP创建前另存本地操作，取得rideId后绑定。响应丢失、取消或进程中断保留待确认记录，按账户及同车号从原生bikeCheck恢复真实ID后查该订单，不重发pre.ride或create。不同扫码的恢复记录相互隔离，旧响应不能删除新记录。

重复云端ID与已有记录合并，保留已结束订单和付款状态；迟到开锁结果不能复活结束记录。没有云端ID的记录只允许Opening、未知结算和无付款记录。删除异常记录按本地sessionId处理，不会禁止未来所有暂无云端ID的操作。

## 证据与验证边界

[证据归档](evidence/hellobike-scan-chain.json)包含完整REA入口／备用链方法、原APK身份、原DEX注解、脚本URL／压缩及解压哈希、精确源码偏移和完整关键分支。H5是官方可变latest资源，获取日期为2026-11-13及2026-11-16；文件身份已校验，但APK不能锁定过去某时刻服务器下发的H5。

REA对补齐的弹窗和蓝牙脚本完成静态解析，语义图有动态调用未知和保留上限，不能把未知或缺失关系当作不存在。关键条件结合完整原文逐项核对。这是完整关键链条核对，不能证明官方全App功能、设备风控初始化或所有账户状态等价。

生产API配合离线响应验证普通等待、SERVER零重复create、APP一次create、无预订单ID、实际rideId变化、普通及特殊拦截、继续骑行字段、22／23查单、丢失响应、取消／换车、结束订单及权限恢复。最终日志为`build/hellobike-card-list-review/final-official-chain-review.log`。没有真实车辆、凭据或支付的新版本实测。
