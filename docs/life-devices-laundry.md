# 胖乖生活洗衣接口：桶自洁与支付唤起

核验日期：2026-11-04（Asia/Shanghai）。离线读取用户提供的 `桶自洁.har`、`支付唤起.har`、`洗衣完成.har`，并与此前的 `饮水.har` 对照。未重放请求，未下单、支付、取消订单或操作设备。原始 HAR、账号 token、签名和第三方支付参数不写入文档。

洗衣接入以本文的扫码、订单创建、支付、设备启动和同订单完成证据为依据。与接水共用登录、传输及界面基础；账单继续不接入。

## 1. 签名和共用部分

三份抓包中 `userapi.qiekj.com` 带签名的请求分别为 85 条、70 条、38 条，现有 Android 签名算法全部匹配：

```text
SHA256(appSecret={secret}&channel=android_app&timestamp={ms}&token={token}&version=1.109.1&{path})
```

结果为小写十六进制。签名 token 从请求表单、查询参数或 `Authorization` 取值；这两份抓包没有单独的 `token` 请求头。密钥仍由本机配置提供。

登录、HTTP 传输、签名、扫码相机、设备展示、支付渠道数据结构和弹层布局复用接水基础。洗衣不能复用接水的解锁、结束及后付处理接口：本次洗衣设备是 `payment=before`，饮水是 `payment=after`。

## 2. 扫码及设备配置

| 请求 | 抓包参数 | 结果与用途 |
| --- | --- | --- |
| `/goods/scan/v2` | `NQT`、token | 返回 `categoryCode=00`、`categoryName=洗衣机`、设备 ID、数字 shopId |
| `/goods/normal/details` | goodsId、token | 本次设备名 `77`；非蓝牙；`communication=serial`；先付费；`supportAppUserFinish=false` |
| `/goods/normal/skus` | goodsId、token | 程序名、说明、价格、unit、可用性和附加信息 |
| `/shop/outline/v2` | orgId、goodsId、token | 商户及位置，本次为河南财经政法大学 |
| `/goods/attachList/v2` | goodsId、token | 本次为空数组，不生成虚构附加项目 |

洗衣扫码表单用 `NQT`（本次为 UUID 形式），接水扫码用 `SN`。当前接水二维码解析仍只处理已证实的接水地址；洗衣二维码图片或原始文本尚未提供，不能从表单参数反推完整二维码格式。

扫码数字 shopId 与 details 字符串 shopId 是不同标识。首次支付渠道请求使用扫码数字 shopId；再次支付请求使用已有订单的字符串 shopId，不能混用。

本次 `/goods/normal/skus` 返回的真实程序如下；这些是设备配置观察值，后续页面必须按实时响应生成：

| 程序 | 价格字段 | unit 字段 |
| --- | --- | --- |
| 快洗 | 2.50 元 | 25 |
| 桶自洁 | 0.00 元 | 5 |
| 单脱 | 1.00 元 | 10 |
| 大物洗 | 5.00 元 | 45 |
| 标准洗 | 3.50 元 | 35 |

桶自洁订单进一步返回 `markMinutes=5`、`markUnit=m`；unit 的分钟含义在该程序有订单依据。以 skuId 逐项核对后，77 号机快洗的历史订单也对应 `markMinutes=25`。本次没有同一 skuId 的单脱、大物洗和标准洗订单，不能仅凭字段名推断这些程序的单位，也不能把其他机器的同名程序当成同一 SKU。`stockState=1`、`scanAvailable=true` 是可用性依据，不能单凭价格认定可启动。

扫码后还出现 `/machine/jumpAlipayMp`（imei、orgId），返回 true；扫码响应中的 `jumpToAlipayFlag`、`jumpToAlipayFlag2` 均为 false。true 本身不是跳转地址，不据此生成支付宝小程序跳转行为。

## 3. 桶自洁的完整启动顺序

本次 77 号机的桶自洁走普通先付订单，预览价格为 0 元，支付渠道只有 `method=2` 余额支付。不是饮水免密，也没有独立 `/clean` 启动接口。

1. `/trade/preview/v4`：发送 items、promotions、`isAutoSelectPromotion=1`、forbiddenPromotions。items 是 JSON 字符串数组，每项包含 goodsId、skuId、`amount=1`、`num=1`、`soldType=1`。
2. `/payChannelRoute/prePay`：发送 imei、goodsId、`hitAd=0`、payPrice（预览 realPrice）、`useTicket=0`、扫码数字 shopId、`combineOrderStatus=false`、`confirmSelectedStatus=false`。本次仅返回可用余额渠道。
3. `/trade/check_device_status`：goodsId，返回 health=true、msgId。
4. `/machine/sync`：使用该 msgId 轮询。本次先 false 后 true；此接口与接水 `/machine/msg/sync` 的对象响应不同，不能共用响应解析。
5. `/trade/create`：发送 items 和 promotions，返回 tradeNo、orderId。与下一请求逐项核对，**tradeNo 就是支付接口使用的 orderNo**。
6. `/pay/unify/pay`：orderNo、`payType=2`，本次成功响应 data=null。
7. `/order/pay_status`：同一 orderNo、payType，本次 data=true，确认本次支付处理成功。
8. `/machine/device_is_start`：同一 orderNo，本次 data=true，确认设备启动。
9. `/order/detail`：支持 orderId 或 orderNo；随后 `/trade/device/find_fault` 返回 ifFault=false。

0 元订单仍经历创建、支付、支付结果、设备启动四步，不能在前端直接略过。

优惠参数也有两种集合：本次 preview 的 promotions=[]，forbiddenPromotions 含 `promotionType=4`、`oldPromotionId=3482` 等捕获值；create 的 promotions 则携带该捕获项。它与接水禁用资产的 -6/-7/-8 集合不同，不能照搬接水常量，也不能将本次优惠标识固定为所有设备默认值。

## 4. 进行中与结束的证据边界

桶自洁在 12:41:47 已确认启动，订单 details 返回 `orderStatus=2`、`showStart=true`、`completeTime=2026-11-04 12:46:47`。抓包截至约 12:41:55，早于 completeTime；因此不能把 `orderStatus=2` 或 completeTime 字段存在解释成设备已结束。该字段在本次启动阶段给出了未来结束时间，可作为预计时间；是否实际结束仍需设备状态依据。

本次 `supportAppUserFinish=false`，也没有洗衣停止接口，不能为洗衣生成“结束洗衣”按钮。倒计时归零不能代替设备结束确认。启动抓包未覆盖运行完毕；完成证据由后续同订单抓包补齐，失败和退款仍缺少依据。

将两份抓包按设备及订单关联后：桶自洁启动前 `/goods/latestUsed` 中 77 号机 `workStatus=10`；约 12:44:09 的第二份抓包中，同一机器变为 `workStatus=20`。第二份抓包约 12:44:14 查询订单时，同一桶自洁订单仍为 `orderStatus=2`，早于预计结束时间 12:46:47。这是启动期间设备状态变化的依据，但没有捕获结束后状态。`workStatus=20` 也不能单独证明某一用户、某一订单仍在使用，应保留本次 orderNo 与设备 ID 关联。

`洗衣完成.har` 覆盖 13:36:14 至 13:36:23，共 57 条请求。两次 `/order/detail` 与启动抓包的 orderId、orderNo、设备和 skuId 均一致：`orderStatus=3`、`showStart=false`，新增 `finishTime=2026-11-04 12:48:48`，原先的 completeTime 不再出现。该时间晚于预计结束时间 12:46:47，进一步说明预计时间归零不能直接视为已完成。

当前只将匹配本次订单及设备程序的 `orderStatus=3` 视为已完成；`orderStatus=2` 表示本次已启动订单仍在运行。finishTime 用于展示真实结束时间，不能把 completeTime 改名当作实际结束时间。订单详情用于当前会话的恢复和结束判断，不提供账单列表。

## 5. 已有订单再次支付和第三方唤起

`支付唤起.har` 先从 `/order/list` 找到 83 号机标准洗订单，3.50 元、`orderStatus=0`。三次支付尝试针对同一已有订单，期间没有 `/trade/create`：

```text
againPay → check_device_status → machine/sync(false → true) → pay/unify/prePay
```

`/payChannelRoute/againPay` 参数是 orderNo、订单字符串 shopId、`confirmSelectedStatus=false`。返回银联52、支付宝13、微信32、农行掌银91；本次没有余额渠道，不能固定展示所有支付方式。

| payType | `/pay/unify/prePay` 返回 prepayParam | 本次证据 |
| --- | --- | --- |
| 52 银联 | 字符串交易参数 | 参数已返回，未抓到成功支付或返回本 App |
| 32 微信 | **JSON 字符串**，解析后有 appid、partnerid、prepayid、package、noncestr、timestamp、paySign | package 为 `Sign=WXPay`；不是现有校园充值的 weixin URI |
| 13 支付宝 | 签名订单字符串 | method=`alipay.trade.app.pay`，sign_type=RSA2，biz_content 金额为 3.50 元；不是饮水免密渠道15 |

服务端已签发第三方参数，本 App 不在客户端拼接或重新签名支付订单。现有校园微信充值是 URI 唤起，胖乖这次是 App 支付参数，两者只共用事件派发等基础机制；不能把 JSON 直接作为 URI，也不能仅凭参数已获取保证本 App 包名及签名能完成第三方 SDK 唤起与回调。

支付宝参数后出现支付宝 SDK 网络请求，但 HAR 没有支付成功查询和设备启动成功响应。末尾 `/order/cancel` 以该订单 ID 返回成功 data=null。只能确认三种预支付参数取得、支付唤起有迹象及最终取消，不能说普通付费洗衣已经支付和启动验证通过。

## 6. 账单补充与当前范围

`/order/list` 返回订单 ID、orderNo、程序名、设备、价格、订单状态及 tradeOrderItem。与之前 `饮水.har` 解锁返回的 orderNo 对照，找到同一条雅5-1冷饮水订单，`payPrice=0.01`、`orderStatus=2`。这证明费用可以在订单列表取得，不代表接水结束同步接口返回了金额；`markMinutes=330000` 也不能直接作为真实接水时长。

当前不接入账单，不把该金额填进接水中或结束页。实际洗衣完成状态已由 `洗衣完成.har` 补齐。普通付费新建到启动按已验证的先付订单流程接入；尚未完成本 App 真机支付验证，洗衣原始二维码图片也尚未提供。

## 7. 后续页面及共用结构的落点

| 环节 | 页面依据 | 共用内容及洗衣差异 |
| --- | --- | --- |
| 首次进入 | 未连接胖乖账号时登录 | 与接水共用账号、验证码和登录抽屉 |
| 扫码 | 相机识别二维码后加载设备 | 共用相机和扫码错误提示；分别解析 NQT、SN，洗衣原始码格式仍需证实 |
| 核对设备、选择程序 | details、skus、shop | 共用设备展示、位置和程序选择组件；洗衣显示真实程序说明及已证实单位的时长 |
| 核对价格与支付方式 | trade/preview/v4、payChannelRoute/prePay | 金额以服务端预览 realPrice 为准；不将 SKU 标价或优惠价格当成最终应付金额 |
| 开始 | 设备检查、创建订单、支付、支付结果、设备启动结果 | 点击后按钮短暂加载；支付成功与设备启动成功分别核对，不增加“待确认”业务页面 |
| 进行中 | 同一订单 details、device_is_start 与设备状态依据 | 共用进度展示；本次洗衣不提供手动结束，预计时间归零不生成已结束状态 |
| 再次支付 | 已存在的 orderNo、againPay | 继续当前订单，不再次 create；渠道按当前响应展示，不能照搬首次支付渠道 |

基础传输、登录、扫码 UI、设备信息和支付方式展示继续共用。程序类型、支付准备、订单结果及设备启动响应使用各自协议解析，不把洗衣流程塞进 WaterPhase，也不复制一整套账号或页面布局。

接入时保留失败环节及订单标识，以便区分“支付未完成”和“已支付但未启动”。已创建订单的网络失败不能自动再次下单；第三方回调也不能替代 `/order/pay_status` 和 `/machine/device_is_start`。目前证据支持本文已列出的启动、进行中和同订单完成分支；退款、预约、优惠资产、蓝牙设备及其他未捕获的异常状态保持未实现。

## 8. 当前实现与复核

- 洗衣扫码使用 NQT，支持直接输入捕获的 UUID，以及已知胖乖二维码域名的显式 NQT 参数。洗衣图片原始格式仍待真机检验，不把 15 位接水 SN 当作洗衣 NQT。
- 设备读取、程序和位置解析、加密会话中的设备序列化、登录抽屉、扫码相机与手动输入基础共用；洗衣使用独立 LaundryPhase 和会话。
- 程序价格来自 skus，仅作为标价；应付价来自当前程序 preview.realPrice。skuInfo 在真实 preview 中是 JSON 字符串，也兼容对象；预计分钟读取 markMinutes，不直接把 unit 当分钟。
- 当前主动不使用优惠：preview 的 promotions=[]、forbiddenPromotions=[]、isAutoSelectPromotion=0；create 的 promotions=[]。这是按字段含义选择的无优惠请求，未捕获同参数的真实服务器响应。不会硬编码本次 oldPromotionId；预览若仍返回使用优惠，或创建后订单价格与已确认价格不一致，会停止支付并给出说明。
- 余额渠道2按服务器返回可用性启用，包括零元桶自洁；支付宝渠道13调用 `com.alipay.sdk:alipaysdk-android:15.8.42` 的 payV2，原样传递服务端签名串，不进行二次签名。SDK由阿里云 Maven 镜像取得。SDK结果不直接标记已支付，继续查询同订单 pay_status 与设备启动/订单状态。
- 微信32、银联52、农行91等若返回则显示说明，暂不提供支付；它们需要各自 SDK 或应用配置，不能把本次参数作为现有校园微信 URI 使用。不会为未返回的渠道造选项。
- 已创建订单继续使用同一 orderNo；创建请求发送前保存 createSent，丢失创建响应后不重建订单。支付丢失响应时先读取服务器结果，不自动重复付款。返回大厅、重新进入和前后台恢复保留洗衣会话。
- UI 使用 miuix SmallTopAppBar、IconButton、Card、Surface、RadioButton、TextField、Button、CircularProgressIndicator、OverlayBottomSheet；复用现有 APP 的色板、间距、按压反馈及 ContentMotion 曲线。洗衣选择页沿原设计采用紧凑设备行、纵向程序列表；进度页大数字和完成页沿接水风格。
- 页面进出沿应用原有页面动画；设备内容阶段使用淡入和轻微位移，离开的阶段保留自身快照并关闭操作。程序背景、金额及按钮文字有短过渡；完成图标轻微放大。抽屉、选择标记及按压反馈沿 miuix/APP 共用实现，遵从系统动画时长设置。

仅运行相关 API、接水与洗衣 ViewModel、原生 UI 定向测试。原生截图覆盖明暗主题、较大字号、程序选择、进度、完成、付款抽屉和状态过渡；真实设备扫码与第三方付款尚未验证。原始抓包、离线截图 fixture 和签名密钥不提交。
