# 一卡通内部接口说明

记录当前客户端使用的官网协议，不代表稳定的公开 API。仅用于本人账户；不要向聊天、日志或仓库提供真实账密、Token 和付款码。

## 固定源站

`https://yktwx2.huel.edu.cn`

Android 的校园卡登录及一卡通业务请求均只向该 HTTPS 源站发送，不依赖网页登录 Cookie，不跟随重定向发送账密，不关闭 TLS 校验。微信 H5 支付桥接另允许经校验的 `https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb` 与 `weixin://wap/pay` 跳转，校验预支付标识及官方订单回跳，不携带学校 Token。

## 登录

1. `GET /berserker-app/frontInfo?synAccessSource=h5`
   - 当前 `data.getFrontConfig` 是 JSON 字符串，其中 `loginType` 也是 JSON 编码字符串，需要分别解析。
   - 选择 `key=snoNew`、`encryption=keyboard`；缺少 `keyboardType` 时默认 `1`。
   - 配置不支持或要求验证码时停止，不猜参数、不反复尝试。
2. `GET /berserker-secure/keyboard?type=Standard&order=0&synAccessSource=h5`
   - 数字、大小写字母与符号按官网 Standard 固定显示顺序映射到本次返回的编码字符。
   - 编码密码后附加 `$1$<keyboard uuid>`，不直接在登录表单中提交明文密码。
   - 编码在内存中完成，不替代 HTTPS 或本地加密保存。
3. `POST /berserker-auth/oauth/token`
   - `Content-Type: application/x-www-form-urlencoded`。
   - `Authorization` 使用官网前端公开的 OAuth Basic 客户端标识，不是用户密码或 Token。
   - 表单：`username`、`password`（编码后）、`grant_type=password`、`scope=all`、`loginFrom=h5`、`logintype=snoNew`、`device_token=h5`、`synAccessSource=h5`。
   - 解析 `access_token`、`token_type`、`expires_in`。Android 加密保存访问 Token，不使用 Refresh Token。
   - 首次登录、密码过期、验证码或账户异常需在官方渠道处理，不绕过验证。

## 获取付款码

1. `GET /berserker-app/ykt/tsm/codebarPayinfo?synAccessSource=h5`
   - Android 读取统一账户 `ACCOUNT` 及余额，不按 `lostflag`／`freezeflag` 过滤账户。账户展示和充值入口不依赖取码成功；`status=0` 时停止取码。
   - 从响应读取 `account`、`payacc`、`paytype`，不硬编码个人账户字段。
2. 账户取得后，以下卡状态查询与取码并行，状态查询不作为取码前置条件：`GET /berserker-app/ykt/tsm/getCampusCards?synAccessSource=h5`
   - 以当前付款账户 `account` 精确匹配唯一卡片，确认 `lostflag`／`freezeflag`／`acc_status`；其他卡片挂失不影响当前账户。
   - 已挂失时在付款码上方复用内联错误样式与展开／收起动画，显示“当前卡片已挂失，可能无法支付，点击解挂”。点击整条提示直接打开当前卡片的确认解挂流程，不跳大厅、不自动提交操作；仍展示服务端成功签发的有效付款码。已冻结不提供解挂，并清除付款码。
   - 查询失败或匹配不唯一时保持卡状态未知，不阻塞亮码，不能把网络异常当作挂失；网络失败复用顶部卡状态读取错误。挂失／解挂完成后获取新码并重新查询状态；取消并失效化旧请求，防止晚返回的状态或码覆盖当前结果。
   - 取码期间仅显示居中的加载指示，不再显示“核验中”或挂失占位。内联提示支持窄屏大字体换行；取码成功就展示，不等待卡状态返回。冻结和取码失败保留原有居中状态布局；手动刷新统一使用下拉刷新。
3. `GET /berserker-app/ykt/tsm/batchGetBarCodeGet`
   - 参数：账户查询取得的 `account`、`payacc`、`paytype`，加 `synAccessSource=h5`。
   - 解析 `data.barcode` 和 `data.expires`，不将完整码写入日志。取码失败时保留本次查询的账户和余额、清除旧码，优先展示 `data.errmsg` 或外层 `msg`；充值入口不依赖是否已取得付款码。

取码使用自定义 Token 头，与登录的 OAuth Basic 头不同：

```text
synjones-auth: bearer <access_token>
synAccessSource: h5
```

## 消费结果通知与实验性反馈（2026-10-27）

官网源码 `/plat/js/chunk-ae0e72c0.efd771bf.js` 的付款码组件使用 WebSocket；`/plat/js/paycode.ed9a2242.js` 的结果组件读取 `consumeData`，不通过余额差计算交易金额。

- 地址由后端 `frontInfo.data.getFrontConfig.websocket` 加 `mobile_service_platform/qrcode_ykt/payCode` 组成。本次只读配置为 `wss://yktwx2.huel.edu.cn/websocket/`，因此完整地址为 `wss://yktwx2.huel.edu.cn/websocket/mobile_service_platform/qrcode_ykt/payCode`；接入时必须读取配置。
- 连接建立及付款码变化时发送 `{"appCode":"mobile_service_platform","module":"qrcode_ykt","userId":"<当前付款码>"}`。不要记录实际付款码。
- 收到消息后仅在外层 `code===200` 时读取 `data.content`，存为 `consumeData` 并打开成功结果；金额来自 `consumeData.tranamt / 100`，交易信息来自 `payName`、`orderId`、`jndatetimeStr`。非 200 消息不触发成功反馈。
- 用户反馈该 WebSocket 目前无法连接、疑似配置问题。本轮只核对源码和配置，未独立确认故障原因、未接入或测试真实消费通知；不能把此项写成已可用。
- 当前原生翻转检测后根据同账户余额减少显示“支付成功”、展示差额并刷新付款码，作为默认关闭、需用户主动开启的实验性功能。检测本身只查询余额；确认扣减后发布数据失效事件，账单和本月概览在对应页面可见时重新读取，不重复查询已取得的新余额。这是余额变化推测，与官网交易通知机制不同；它不参与充值／缴费订单状态判断。后续若官网通知恢复可用，应以真实交易结果替代推测。

## 账单

账单请求同样使用 `synjones-auth: bearer <access_token>`，不携带网页 Cookie，不跟随重定向。列表、统计和排行均只查询当前账户；客户端支持单日、自然月、自然年和自定义时间段，统一以 `timeFrom=<yyyy-MM-dd>&timeTo=<yyyy-MM-dd>` 传给列表、汇总、分类和排行接口。

日期下限不是账单接口返回值，而是官网前端规则：

- 账单列表默认 `minDate=2010-02-01`，并允许前端配置 `billMinDate` 覆盖。
- 统计页默认 `minDate=2010-01-01`，年份列表固定回溯到 2010。
- 官网通用日期组件的 `2004-01-01` 只是未被这两个页面采用的默认兜底值。

1. `GET /berserker-search/search/personal/turnover`
   - 列表/搜索参数：`current`、`size`、`timeFrom`、`timeTo`，可选 `typeId`、`amountFrom`、`amountTo`（单位分）、`info`，以及 `synAccessSource=h5`。
   - 搜索沿用当前日期范围并传 `info`；不单独保存搜索输入，按日期、筛选和查询条件隔离的账单首屏展示快照使用本地加密缓存，搜索条件改变时重新查询。
   - 排行复用该接口，附加 `current=1`、`size=5`、`type=2`、`sortFields=tranamt`、`sortType=desc`。
   - 解析 `data.{records,total,size,current,pages}`；记录字段包括 `orderId`、`resume`、`payName`、`turnoverType`、`icon`、`typeId`、`typeFrom`、`tranamt`、`cardBalance`、`feeAmt`、`effectdateStr`、`jndatetimeStr`、`fromAccount`、`posCode`、`remark`、`toMerchant`、`sno`、`labelName`。
   - 收入/支出笔数复用该列表接口，附加 `type=1`（收入）或 `type=2`（支出）、`current=1`、`size=1`，读取 `data.total`。
2. `GET /berserker-search/statistics/turnover/count?timeFrom=<date>&timeTo=<date>&synAccessSource=h5`
   - 解析 `data.income`、`data.expenses`，用于月收入/支出汇总。
3. `GET /berserker-search/search/turnoverType?synAccessSource=h5`
   - 解析 `data[].{id,name,icon}`，作为交易类型筛选项。
4. `GET /berserker-search/statistics/turnover?type=2&timeFrom=<date>&timeTo=<date>&synAccessSource=h5`
   - 支出分类饼图；`type=1` 为收入、`type=2` 为支出。
   - 解析 `data[].{turnoverType,amount,typeId,icon}`，按金额降序展示。
5. `GET /berserker-search/statistics/turnover/sum/user?dateStr=2026-10&dateType=month&statisticsDateStr=day&type=2&synAccessSource=h5`
   - 按月查询时按日聚合；`dateStr` 为 `yyyy-MM`，响应 `data` 是日期到金额的对象。
   - 按年查询使用 `dateStr=2026&dateType=year&statisticsDateStr=month`，响应键为 `yyyy-MM`。
   - 单日和自定义时间段仍使用汇总、分类与排行接口；官网未提供对应的趋势聚合参数，客户端不伪造趋势数据。
   - 趋势键同时接受 `yyyy-MM-dd` 与 `yyyy-MM`，其余忽略。

约定：

- `tranamt` 等金额字段按分处理；接口可能返回 `800.0` 形式，解析时四舍五入为整数分。`tranamt` 恒为正，收支方向看 `typeFrom`：`"1"` 为收入，其他为支出。收入显示 `+`，支出显示 `-`。
- 空值字段可能为字符串 `"null"`，解析时统一转为空字符串，避免界面显示 `null`。
- 列表按稳定交易标识去重，优先使用 `orderId`，缺失时使用流水号或交易字段摘要；分页加载到 `current >= pages` 后停止。仅首屏持久化，后续分页留在本次运行内存；恢复快照后先核对实时首屏，再允许继续分页。
- Token 过期或被拒绝时映射为 `AuthenticationRequired`，由账户仓库最多重登一次；API 层不保存原始账单响应，页面层仅缓存解析后的展示字段。
- Android 的账单预览模式只注入虚构记录、金额和账户名，不读取真实账单。

## 挂失与解挂（接入调查）

详见 [挂失解挂接口调查](card-operation.md)。2026-10-21 通过 MCP 在本人账户完成一次挂失→解挂，最终恢复 `lostflag=0`。

- 卡状态：`GET /berserker-app/ykt/tsm/getCampusCards?synAccessSource=h5`，解析 `data.card[].{account,lostflag,freezeflag,acc_status}`；操作使用卡片 `account`，不是学号或 `accinfo[].type`。
- 挂失：`POST /berserker-app/ykt/tsm/lostCard`，JSON 请求体传 `account`、`synAccessSource`；按配置可带查询密码 `pwd`/`pwdType`。
- 解挂：`POST /berserker-app/ykt/tsm/unlostCard`，字段同上。本次解挂要求 6 位查询密码，密码来自官网数字安全键盘，不提交明文。
- 成功需外层 `code=200` 且 `data.retcode=0`；官网源码还接受挂失 `60007`、解挂 `60008`，本次未独立验证。
- 响应不返回新的 `lostflag`；成功后重新查询卡列表确认。写请求不自动重试，超时先查状态，避免重复挂失/解挂。
- 提交前错误和后端明确拒绝显示原提示、保留确认界面，不再统一报待确认；只有写请求结果不明／提交后状态未确认时只读复查，仍不自动重发。响应可省略账户回显，若存在则校验；最终以请求目标状态确认。

## 修改查询密码

详见 [修改查询密码协议与登录处理](card-password.md)。入口为“我的 → 校园卡管理 → 修改查询密码”。

- `POST /berserker-app/ykt/tsm/modifyPwd`，JSON 字段 `account`、`oldpw`、`newpw`、`renewpw`、`synAccessSource`。
- 三个密码分别使用同次数字安全键盘映射与 UUID；原生固定顺序编码使用 `Number&order=0`，不提交明文。
- 仅外层 `code=200` 与内层 `retcode=0` 确认成功；成功清除旧凭据与 Token，Toast 后回登录页，不自动重登。
- 失败保留弹窗，直接在固定错误槽显示服务端原始 `msg` / `data.errmsg`，不添加前缀、不改写、不附加重试文案；没有原始消息的网络/格式异常才用本地提示。不自动重试写请求。
- 本轮未执行真实原生改密，无 Cookie 提交仍需本地验证。

## 卡片充值与微信支付（接入调查）

详见 [卡片充值调查](recharge.md)。2026-10-21 通过 MCP 只读验证接口，并离线分析用户本人 10 元充值 HAR：已确认微信内网页下单 302、微信 JSAPI 参数、两次订单成功查询和余额增加 10 元。外部 Android App 调起微信及自定义回跳仍未验证，**当前客户端已接入原生充值界面，微信实际唤起仍需真机验证**。

- 充值项目：`GET /charge/feeitem/singleFeeitem`，解析顶层 `feeitem`。
- 可充值卡：`GET /berserker-app/ykt/tsm/queryCard?scene=recharge`。
- 下单：官网表单 `POST /charge/order/thirdOrder`，用户微信内 HAR 已确认返回 302；`POST /charge/cashier/toPayment` 分支仍未调用。
- 支付方式：`GET /charge/pay/getpayinfo?orderid=<本人订单>&userAgent=h5`，解析顶层 `order`、`payList`。
- 支付：`POST /blade-pay/pay`，用户 `wechat-mp` HAR 已确认返回 JSAPI 参数，不是 H5 支付链接；外部 App/H5 返回分支未验证。
- 结果：`GET /charge/order/personal_data?orderid=<本人订单>`，解析顶层 `orderList`；`status=1` 是支付成功，不仅凭回跳或请求成功展示“充值到账”。
- **充值/收银台金额是元，卡片余额和账单金额是分**，不能混用。
- 不保存原始支付配置，不将 Token 放进外部跳转 URL，不自动重试创建订单。
- 挂失代充值已验证支付参数请求返回外层 `60007`／`success=false`／`msg=账户已挂失`；本人与代充值共用拒绝处理，不拉起微信、不自动查单。错误文案优先原样服务端 `msg`，没有官方映射时使用官网 `Error` 兜底；消费 `60050` 不再等同余额不足。一卡通付款不进行本地余额拦截，原样显示后端原因；充值目标遵循官网状态配置，不额外增加下单前账户预检。

## 代人充值（接入调查）

详见 [代人充值接口调查](others-recharge.md)。2026-10-22 通过 MCP 核对当前官网源码及用户手动查询的失败响应；没有创建或支付代充值订单。

- 验证码：`GET /berserker-auth/oauth/captcha`，当前组件读取顶层 `key` / `image`，用户手动输入。
- 收款人查询：`GET /berserker-app/ykt/tsm/idtypeQueryCard`，传 `idtype=sno|id`、`id`、`scene=recharge`、`captcha_header_key` / `captcha_header_code`（查询参数）。
- 成功须外层 `code=200` 且内层 `data.retcode="0"`、存在可用目标；已观察到验证码错误 `code=8002`，以及外层成功但内层 `60003` / “账户信息不存在”。
- 下单复用 `/charge/order/thirdOrder`，`abstracts` 中 `othersRecharge=true`；`yktcard` 来自收款目标查询，而不是付款人卡片或输入的学号/证件号。
- 代充值成功查询、跨用户目标绑定、下单支付和到账仍未验证。当前 Android 已接入验证码、收款目标查询与确认，支付及查单复用现有充值流程；已进行离线回归，不要求额外真实扣款。

## 电费、水费与校园网缴费（接入调查）

详见 [缴费接口调查](fees.md)。2026-10-21 离线核验用户 `缴费..har`：普通手机浏览器中郑东宿舍电费的一卡通与微信 H5 各完成一笔 1 元支付并查单成功。文北宿舍、家属区、商业街及宽带仅覆盖查询/下单/账户准备，宽带本次仅返回一卡通方式，未捕获最终支付结果。

- 项目配置：`GET /charge/feeitem/singleFeeitem`，解析顶层 feeitem。
- 目标/用量查询：`POST /charge/feeitem/getThirdData`，按项目的 `map.total[].code` 动态构造级联字段；宽带本次直接查询当前用户。
- 下单和支付：`POST /blade-pay/pay`，缴费先 `paystep=0`，再按订单动态选择渠道；一卡通 ACCOUNTTSM、微信 TSM，不是卡充值 CAMPUSCARD。
- 一卡通支付密码采用缴费安全键盘的数字位置编码，不使用网页登录密码协议，也不自动使用保存的登录密码。
- 结果：`GET /charge/order/personal_data`，只核验本人匹配订单；不能仅凭 code=200 认定支付成功。
- 所有金额以接口各自单位为准；用户密码、Token、Cookie、房间/宽带 ID 与键盘映射不写入仓库。Android 已通过大厅金额弹层接入宿舍、家属区、商业街水电与校园网缴费，复用渠道选择、一卡通支付密码、微信唤起及查单流程；项目、目标和渠道以实时官方配置为准。界面已接入不代表全部项目已完成真机支付验证。

## 刷新与错误边界

- Android 使用加密保存的 Token；普通查询取得会话后释放账户仓库互斥锁并行读取，登录与不可重放的写操作仍由锁保护。Token 过期或被拒绝时，并发请求共享一次重登，每个查询最多重试一次；迟到的旧 Token 拒绝不清除新会话。账密无效或需要验证码时清除保存登录状态并要求重新登录，已取消查询不再改动凭据，网络故障不等同于账密错误。
- 官方前端显示周期为 60 秒；若 `expires` 更短则缩短周期，不将批次有效期当成离线可用承诺。
- Android 每个周期重新取批次，只显示首码；后台移除当前码，回前台重新获取。
- 余额、账单首屏 / 统计、资料及大厅展示值有按账号和查询目标隔离的本地加密快照；冷启动先恢复展示，再联网核对，同一运行中沿用一分钟内存新鲜度。失败保留有效旧数据，退出登录、改密或确认身份失效时清理当前账号缓存。
- 本人充值及一卡通缴费查单成功后使自己的余额失效，在付款 / 我的 / 账户详情可见时重新读取；代人充值和微信缴费不重查自己的余额。所有确认成功的充值 / 缴费都使账单和本月概览失效，缴费同时使大厅失效，待对应页面可见时刷新；关闭结果弹层的方式不影响事件发送，其他账号或过期任务不能更新当前数据。
- 接口格式、键盘布局或验证码规则改变需重新验证；不支持官网键盘以外的字符。
- 多客户端取码对旧码的影响、离线付款、实际扫码消费及消费结果监听未验证。

## 测试原则

测试使用虚构学号、密码、Token、账户、付款码和账单记录。HTTPS 请求构造测试使用替代连接验证参数、请求头及解析，不连接真实接口。真实验证只在手机或本地隐藏输入中提供凭证，不保存网络抓包或敏感响应到仓库。
