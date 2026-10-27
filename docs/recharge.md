# 卡片充值与微信支付接入调查

核验日期：2026-10-21（Asia/Shanghai）。先通过 MCP 只读检查官网与本人历史成功订单，后离线分析用户提供的 `ProxyPin10-21_07_32_01.har`，其中包含用户本人手动完成的一笔 10 元充值。**代理没有创建或支付订单，没有重放 HAR 请求，没有删除订单；原始抓包未复制进仓库。**

## 验证等级

- **实际只读验证**：充值配置、卡片查询、支付方式查询、订单结果查询；使用 `credentials: omit` 和 `synjones-auth` 请求头重复验证，不依赖浏览器 Cookie。
- **官网源码确认**：签名算法、支付返回处理分支、结果页状态与完成回跳拼接规则。
- **用户真实 HAR 验证（微信内网页）**：10 元充值签名表单、302 下单重定向、微信 JSAPI 参数、同一订单两次 `status=1` 查询，以及账户余额增加 1000 分。详见第 9 节。
- **未验证**：外部 Android App/H5 载体的支付返回类型、外部 App 调起微信和回到本 App、学校收银台是否接受自定义 scheme。HAR 的 `wechat-mp` 请求携带 Cookie，不能把这份抓包当作无 Cookie 下单/支付已验证。

不要将源码确认等同于真实支付链路已经通过。

代人充值使用相同充值组件，但需增加验证码与收款目标查询，并签名 `othersRecharge=true`；详见 [2026-10-22 代人充值调查](others-recharge.md)。本人充值成功验证不等价于代充值已通过。

## 1. 查询充值配置

`GET /berserker-app/frontInfo?synAccessSource=h5`

解析 `data.getFrontConfig` 和 `data.getEcardConfig`（JSON 字符串）。从配置读取充值收费项目和收银台地址，不固定个人账户或将观察值作为永久配置。

本次观察：

- `getFrontConfig.recharge = "401"`。
- `getFrontConfig.payment = "https://yktwx2.huel.edu.cn/payment/"`。
- `getFrontConfig.payResult = "https://yktwx2.huel.edu.cn/plat/payResult"`。
- 页面使用电子账户模式 `getEcardConfig.type = "1"`。

`GET /charge/feeitem/singleFeeitem?feeitemid=<recharge>&synAccessSource=h5`

成功响应是 `code=200`，收费项目在**顶层 `feeitem`**，不是 `data`：

- `feeitem.feeitemid`、`name`：收费项目。
- `layout`：金额快捷选项，本次为 `"10,50,100"`。
- `retain_money`、`maxmoney`：可为空，前端作为最小/最大金额配置。
- `flag[7]`：源码中 `"1"` 表示只允许选项，`"2"` 表示只允许整数；其他情况允许自定义金额，最多两位小数。
- 官网也会使用 `getFrontConfig.rechargeMaxAmount`；本次未返回这个字段。不能把前端默认值当作服务端允许额度。
- `view`：本次返回 `"choose"`，不代表微信支付结果。

### 2026-10-27 限额复核

通过 MCP 在登录后的官网读取 `frontInfo`、`singleFeeitem`、充值页组件配置及当前账户信息。项目 401 的 `retain_money`、`maxmoney`、`daymaxmoney` 均为 null，`flag="1100000000"`；`rechargeMaxAmount` 未返回。充值组件允许自定义金额和小数，其源码在未配置时使用 9999 元作为输入默认上限；这不是服务端承诺的可交易额度。此前 Android 额外限制 500 元，没有本次后端配置依据，已移除。

用户本人尝试充值后提供了官网 `/plat/notfound?msg=...` 返回：`金额大于每笔交易允许的最大金额800元`。该消息证明该次请求遇到了服务端单笔限制，但上述只读配置中没有发现可提前读取的 800 元字段。代理没有重放充值请求，也没有通过多笔订单探测额度。

- 本人及代人充值直接读取 `retain_money`；单次上限使用 `maxmoney`，未提供时采用 `frontConfig.rechargeMaxAmount`，与官网配置覆盖顺序一致。两者都未提供时保留未知，不写死 500、800 或 9999 元。
- 金额提示复用支付密码错误的 `InlineError`；整数项目的无效输入不再提示允许两位小数。整数限制由本次 `flag[7]` 决定，不按项目 ID 硬编码。
- 下单或支付的 302 `Location` 若指向同源 `/plat/notfound`，应用共用生活缴费的解析，在本地解码 `msg` 并显示官方拒绝原因，结束本次支付尝试。允许不改变消息内容的官方可选参数；不跟随跳转、不创建新订单、不从错误消息提取数值持久化为限额。
- 下单错误重定向与支付 JSON 错误共用消息处理。新版充值收银台以 `code === 200` 进入成功回调，其余业务码显示 `msg || "Error"`；不依赖 `success`、`data` 或错误码白名单。生活缴费采用另一套官网 `code/state` 判据，见 `fees.md`。
- 缺失上限不代表无限额；累计额度、商户或账户交易限制仍以服务端提交校验为准。网络或格式错误显示请求错误，不增加交易状态，不自动重发下单或支付。
- 同日重读新版收银台 `/payment/js/chunk-6e840d41.7bb7da17.js`：支付成功分支解析 `webUrl`、`paysubmit` 等；非成功分支清空密码并显示 `msg || "Error"`。结果页 `/payment/js/chunk-6b4d1c18.c387234a.js` 在订单 `status=0/2` 时显示订单自身的 `msg`。原生补齐该字段传递，失败／超时优先显示后端原因；未知状态有消息也只展示，不靠文案判断成功或未扣款。

## 2. 查询可充值卡片

`GET /berserker-app/ykt/tsm/queryCard?scene=recharge&synAccessSource=h5`

解析 `data.retcode`、`data.card[]`。卡片包含 `account`、`db_balance`、`unsettle_amount`、`lostflag`、`freezeflag`、`accinfo[]`；电子账户包含 `name`、`type`、`balance`。

官网账户映射：

- 电子账户模式：选中 `accinfo[].type` 作为下单 `yktcard`，**不是**卡片 `account`。本次默认账户名为“统一账户”。
- 卡账户模式：卡片 `account` 作为 `yktcard`。
- 卡余额展示：`(db_balance + unsettle_amount) / 100`；电子账户余额展示：`balance / 100`。
- 官网根据 `showLost`、`freezeRecharge` 决定是否禁用挂失/冻结卡片；客户端应明确处理卡片状态，不擅自绕过限制。

所有参数从当前本人账户响应读取，不能硬编码学号、卡账户或电子账户 ID。

## 3. 创建学校收银台订单（源码及微信内 HAR 确认）

官网普通充值通过 HTML 表单：

`POST /charge/order/thirdOrder`

`Content-Type: application/x-www-form-urlencoded`

| 字段 | 官网行为 |
| --- | --- |
| `feeitemid` | 配置中的充值项目 |
| `appid` | 官网公开前端接入标识，本次为 `56321`；不同于充值入口页面的 `appId=27` |
| `tranamt` | 用户选中的金额，**单位元** |
| `source` | `app`，即使网页当前载体为 H5 |
| `synjones-auth` | 表单包含 `bearer <access_token>`；只可提交至固定学校 HTTPS 源站 |
| `yktcard` | 上一步选中卡账户/电子账户 ID |
| `synAccessSource` | 当前载体，本次 `h5` |
| `abstracts` | 本人充值使用 JSON `{"type":"recharge","othersRecharge":false}` |
| `redirect_url` | 可选，官网从入口 `finishUrl` 传入；用于充值结果页完成后的回跳，不是微信异步通知地址 |
| `APP_ID`、`TIMESTAMP`、`SIGN_TYPE`、`NONCE`、`SIGN` | 官网签名函数添加 |

签名源码：

1. 合并业务字段与 `APP_ID`、`TIMESTAMP`、`SIGN_TYPE=SHA256`、`NONCE`。
2. 按键名排序，排除 `SIGN`、`SECRET_KEY`。
3. 拼接非空值（含数值 0）为 `key=value&`，最后追加 `SECRET_KEY=<官网前端接入签名值>`。
4. 对原始拼接字符串做 SHA-256，十六进制结果大写；表单 URL 编码在签名之后。

这里的接入签名值是官网前端协议的一部分，**不是本 App 拥有微信商户密钥的证明**。本说明不记录签名值，不复制整个支付配置对象；移植时应对照官网源码，并用虚构字段进行离线一致性测试。时间戳由官网本地时间函数生成，nonce 由官网随机函数生成；真实有效期和重放限制尚未核验。

官网 AI 聊天分支另有：

`POST /charge/cashier/toPayment`

提交相同签名字段，源码将 `code=200` 的 `data` 作为收银台跳转地址。**本次未调用，不能假定它是通用公开接口，不能假定 URL 不含 Token，也不能假定它直接返回微信链接。**

创建订单是有副作用的操作，不应用普通查询的自动重试策略。超时后状态不明，应查询/恢复现有订单，不自动创建第二笔订单。

## 4. 读取订单与可用支付渠道（实际只读验证）

`GET /charge/pay/getpayinfo?orderid=<本人订单>&userAgent=h5&synAccessSource=h5`

顶层响应：`{code, msg, order, payList}`。

从 `payList[]` 动态选择微信方式，不硬编码支付 ID。本次查询本人已有成功充值订单观察到：

- `name = "微信"`
- `code = "CAMPUSCARD"`
- `payid = 81`
- `remark = "weixin"`

这不是已核验的原生微信 APP 支付，也不是已取得的 `mweb_url`。对新待支付订单的渠道选择仍需核验。

学校响应可能包含不应进入客户端日志的支付配置字段；只解析所需的订单、金额、状态、支付名称/渠道和回跳字段，禁止保存或输出原始响应。

## 5. 调起支付（源码及微信内 HAR 确认）

`POST /blade-pay/pay`

`Content-Type: application/x-www-form-urlencoded`

核心表单：

- `paytypeid`：`payList[].payid`。
- `paytype`：`payList[].code`。
- `paystep=2`。
- `orderid`：创建订单所得 ID。
- `userAgent=h5`：本次查看的 H5 载体；不能伪装官方 App 以绕过限制。
- `redirect_url`：官网使用学校收银台 `payment + "?name=result"`；不同于订单层的完成后回跳 URL。
- 同一套 `APP_ID`、`TIMESTAMP`、`SIGN_TYPE`、`NONCE`、`SIGN`。
- `opAppId` 在官网微信环境取自 sessionStorage，仅当正确载体提供时使用；不可伪造 OpenID/AppID。

官网支付返回分支不只一种：`data.webUrl`、`paysubmit`、`qrCodeUrl`、`returnType="app"`、`toAppSdkType/toAppSdkData`、微信 JSAPI 参数，以及异步等待/错误分支等。

**用户 HAR 已确认 `wechat-mp` 载体返回微信 JSAPI 参数，不含 `webUrl` 或 `paysubmit`；外部 App/H5 载体的返回类型仍未验证。**不要通过 `code=200` 判断支付成功，不执行不受信任的返回 HTML/JS，不任意打开返回 scheme，也不将学校 Token 带给微信或其他域名。

## 6. 核验支付结果（实际只读验证）

`GET /charge/order/personal_data?orderid=<本人订单>&payid=<可选>&synAccessSource=h5`

顶层响应：`{code, msg, orderList}`。本次使用本人历史充值订单查询，返回一条匹配记录，`status=1`、收费项目为卡片充值。

官网结果页源码状态：

| `orderList[].status` | 官网结果页行为 | 本 App 应对 |
| --- | --- | --- |
| `1` | 交易成功 | 匹配本人待支付订单、充值项目和金额后展示支付成功 |
| `0` | 最终展示失败，也可能先等待通知/再次查询 | 最多查三次，间隔 5 秒；最终仍为 0 显示充值失败 |
| `2` | 交易超时 | 显示支付超时，优先使用订单 `msg` |
| 空 `orderList` | 补出 `status=0`，参与相同的有限查询 | 成功响应的空数组按未支付处理，最终显示充值失败 |
| 网络 / 格式异常 | 请求错误 | 停止加载，显示错误；重试只查询当前订单 |
| 其他状态 | 官网未定义对应结果 | 显示订单 `msg` 或状态异常，不增加本地交易状态 |

关键字段：`orderid`、`status`、`tranamt`、`actulamt`、`payid`、`payBean.name`、`feeitemlist[].feeitemid`、`redirect_url`。**此处 `tranamt` 和 `actulamt` 单位是元**：官网 money 函数直接格式化，没有除以 100。

官网结果页在完成回跳时，会在订单 `redirect_url` 后追加 `orderid`、`payid`。回跳参数只用于定位/唤醒，仍要用当前登录身份重新查询服务器，不能信任 URL 中的 `status=success`。

官网源码还有 `${getFrontConfig.websocket}mobile_service_platform/pay_result/<orderid>` 消息监听。本次没有连接，鉴权、消息绑定及真实性未验证，暂不作为本 App 的成功判据。

客户端沿用官网 `status=1` 的成功反馈，不额外增加到账状态或“待确认”结果。下单或支付接口返回 `code=200` 只表示该请求进入成功分支，仍需查询订单结果。

## 7. 当前 Android 交互（2026-10-27）

充值使用原生金额和结果弹窗。失败原因在结果中显示一次；有后端 `msg` 时原样展示。金额校验使用现有内联错误样式，错误动画退场保留原文案及按钮，避免瞬间闪出“重试”。整数规则来自收费项目 `flag`，上下限来自接口配置，服务端提交时的限额拒绝直接显示，不将数字提取为本地规则。

- 每次打开都是新尝试，仅保留本次内存订单；关闭后取消查询，不恢复历史订单。
- 微信返回后最多查询三次，首次立即、之后间隔 5 秒；任一次 `status=1` 即显示“充值成功”，最终 `0` 显示“充值失败”，`2` 显示“支付超时”。有效成功响应的空 `orderList` 按新版官网补为 `status=0`。
- 下单、取支付参数的请求失败回金额表单显示错误并保留已输入金额；不会自动跳去查单或重新下单。业务拒绝保留统一失败结果展示。
- 查单请求失败立即停止加载，显示原始请求错误。用户点击“重试”只查当前订单，不重发支付；正常查单期间的暂停／恢复仍保留剩余查询次数。
- 不根据消息关键词判断支付状态。状态 0／2 的空消息沿用官网语言包 `/config/language/cn.json` 的 `transactionFailedDescription`：“若扣款成功，请稍后在账单查看”；有后端消息时不额外附加文案。
- 返回 App 只触发查询，不作为微信取消或成功的证据。关闭本地弹窗不取消服务器订单。
- 失败后切换渠道、重新下单属于保留的交互改进；错误码、消息及订单状态以官方机制为准。

静态来源为 `/payment/js/chunk-6e840d41.7bb7da17.js`（付款回调）与 `/payment/js/chunk-6b4d1c18.c387234a.js`（结果查询）。当前仍校验订单归属及已有订单字段的一致性；异常只作为请求错误处理。没有实际支付的分支不以源码复核替代实测结论。

## 8. 外部 App 的下一步真机验证边界

1. 用户选定金额、确认本人账户；如需联网创建测试订单，应由用户明确同意。
2. 记录创建订单的响应结构/受信任跳转与订单 ID，不能记录 Token、支付签名或原始支付配置。
3. `wechat-mp` HAR 已确认待支付订单返回微信 `CAMPUSCARD`；外部 App/H5 载体仍需确认服务端返回的合法支付方式。
4. 只抽取支付响应类型与必要字段名，确认是 H5 URL、表单、二维码或 SDK 分支；由用户在微信自行确认支付。
5. 对照同一订单验证成功、取消、回跳、查单延迟、到账、重复点击和进程重启场景。

离线测试应覆盖金额单位、签名一致性、请求源站、顶层 JSON 解析、身份/金额/订单匹配、伪造回跳、请求错误和禁止自动重复下单。不要用真实账户自动化发起充值作为测试。

## 9. 用户 10 元充值 HAR 的实际结果

来源：用户提供的本地 `ProxyPin10-21_07_32_01.har`，共 181 条请求。本节仅保存脱敏结论，不保存真实 Token、Cookie、订单 ID、账户 ID、AppID、支付签名或完整跳转链接。

### 时间线

以下时间来自 HAR 的 `startedDateTime`，从 UTC 转换为 Asia/Shanghai，日期均为 **2026-10-21**：

| 本地抓包时间 | 请求/结果 |
| --- | --- |
| 07:30:57 | 查询选中的统一账户，记录充值前余额 |
| 07:30:59 | `POST /charge/order/thirdOrder`，表单 `tranamt=10`，HTTP 302 跳到学校 `/payment/` |
| 07:31:00 | `GET /charge/pay/getpayinfo` 返回同一订单与微信支付方式 |
| 07:31:00 | `POST /blade-pay/pay` 返回微信 JSAPI 支付参数 |
| 07:31:11 | 两次 `GET /charge/order/personal_data` 都返回同一订单 `status=1`、`tranamt=10`、`actulamt=10` |
| 07:31:17 / 07:31:19 | 再次查询同一统一账户，余额较充值前增加 **1000 分（10 元）** |

两条结果记录的 `successdate` 不一致，响应 HTTP Date 与客户端抓包时间也不一致；不使用这些字段判定时序或超时，不将服务端成功时间硬编码到 UI。本次成功结论来自同一订单 ID、金额、项目和状态的交叉核对，余额变化是到账的补充证据，不能泛化为“任意余额增加就代表该订单到账”。

### 已确认的下单跳转

- 请求载体为 `synAccessSource=wechat-mp`，不是 `h5`。
- 收到 HTTP 302，Location 为固定学校 HTTPS 源站 `/payment/`，参数名为 `orderid`、`paytypeid`、`paytype`、`chooseAccount`、`token`。
- 可提取订单 ID 和支付渠道用于恢复单笔 pending；不能把含 Token 的完整 Location 转发到外部 App、日志或分享链接。
- 本次为 `paytypeid=81`、`paytype=CAMPUSCARD`、`chooseAccount=false`，不需要用户额外选择账户。这些是观察值，不是永久常量。
- 下单没有传 `redirect_url`，结果订单的 `redirect_url=null`；因此没有验证本 App 的自定义回跳。

### 已确认的支付请求与响应

- `userAgent=wechat-mp`、`synAccessSource=wechat-mp`。
- `opAppId` 字段存在但值为空；不能据此假定其他载体也不需要微信授权。
- `redirect_url` 指向学校 `/payment/?name=result`。
- 请求签名字段包含 `APP_ID`、`TIMESTAMP`、`SIGN_TYPE=SHA256`、`NONCE`、`SIGN`。
- 支付响应：`code=200`、`success=true`，`data` 字段名为：

```text
appId, timeStamp, nonceStr, package, signType, paySign, orderid
```

- `package` 形式为 `prepay_id=<脱敏>`，`signType=MD5`。
- 支付层的 `MD5` 与学校请求签名层的 `SHA256` 是两套协议，不能互相替换或自行重新签微信支付参数。
- 响应没有 `webUrl`、`paysubmit` 或原生 App SDK 类型字段。此样本走微信内网页 JSAPI，不能当作外部 Android App 可直接调用的支付链接或 OpenSDK 参数。

### 已确认的成功查询

两次查单 query 的 orderid、返回订单 ID、支付方式、项目及金额都匹配这笔新订单：

- `orderList` 仅一条，`status=1`。
- `tranamt=10`、`actulamt=10`，单位元。
- `payBean.name=微信`。
- 充值项目在 `feeitemlist[].feeitemid=401`。本次顶层 `order.feeitemid=0`，**不能仅依据顶层 feeitemid 验证充值项目**。
- `abstracts` 为本人充值、`third_party` 匹配下单选中的账户。

所以用户“点返回过快”没有丢失学校服务端确认支付成功的记录。HAR 不记录页面是否实际绘制成功界面，也没有记录 `WeixinJSBridge.invoke` 的 JS 回调函数返回值；但本 App 判断成功应依靠服务端查单，这部分数据已经具备。

### 仍缺的外部 App 验证

这份 HAR 覆盖了**微信内网页**充值全流程，不覆盖“本 App → 微信 → 本 App”。外部 App 一键跳转需服务端实际提供合法 H5 链接、受支持的小程序/网页入口或本 App 授权的 OpenSDK 支付路径。不能把 `wechat-mp` 伪装成真实载体，也不能把 JSAPI 参数硬塞进普通 Android WebView。完成回跳是否可自定义仍待验证。

## 参考源码与官方支付规范

本次官网资源：

- `/campus-card/js/chunk-0bef8af1.c842f783.js`：卡片充值、`89eb` 签名函数、thirdOrder 与 toPayment 分支。
- `/payment/js/chunk-6e840d41.7bb7da17.js`：收银台订单信息、支付参数及支付返回分支。
- `/payment/js/chunk-6b4d1c18.c387234a.js`：结果查询、状态映射、完成回跳与 WebSocket 分支。
- `/payment/js/app.84cef22a.js`：收银台路由、金额格式化。

微信官方开发说明：

```text
https://pay.weixin.qq.com/docs/merchant/products/h5-payment/development.html
https://pay.weixin.qq.com/docs/merchant/products/in-app-payment/development.html
https://pay.weixin.qq.com/docs/partner/apis/partner-h5-payment/h5-transfer-payment.html
```


## 挂失充值拒绝与官方提示（2026-10-22）

用户代充值 HAR 已确认：查询成功但目标 `lostflag=1`，10 元下单返回 302；随后 `POST /blade-pay/pay`（`CAMPUSCARD`、`paystep=2`）返回 HTTP 200、`code=60007`、`success=false`、`data=null`、`msg=账户已挂失`。同笔订单、金额与收款目标均匹配。此处是在获取支付参数时被拒绝，没有可拉起的微信参数；不是等待付款结果。

- 本人／代充值复用此支付处理。外层业务失败且结构一致时（包括已验证的 `60007`），直接结束本次尝试、显示原始 `msg`；缺失／空串按官网使用 `Error`，不通过正则或文案关键词分类。共用分类已按 2026-10-27 复核扩展。
- 获取支付参数期间继续显示创建中，只有获得合法微信参数才进入待支付。明确拒绝不发送拉起事件、不等待微信返回、不自动查单、不重发支付。已生成订单仅在本次结果内存对象中保留编号，点击完成即清除，不提供继续支付／自动取消；不宣称该订单已取消。
- 下单或获取付款参数的请求错误回金额表单显示原因；查单请求错误保留当前订单供手动重查，不重发支付。业务错误按官网 `code` 分支显示原始 `msg`，不要求额外的 `success/data` 条件。
- 原生目标选择遵循官网 `showLost`／`freezeRecharge`：`showLost="1"` 允许挂失卡进入候选；冻结配置非空且不为字符串 `"1"` 时禁用冻结卡。本 HAR 证明允许进入候选仍不保证支付阶段成功。两个限制同时生效时，按官网用冻结提示覆盖挂失提示。状态提示来自官方语言包，省略账户前缀，不额外根据 `acc_status` 猜测充值是否允许。
- 撤回上一轮额外增加的本人下单前状态请求／拦截，避免客户端自定顺序或新增请求失败改变官网流程。本人／代充值仅在已有账户选择请求中按官网配置处理状态；提交和支付由服务端最终判断。不拿付款人 `queryCard` 验证收款人，不重用验证码查询。
- `60007` 的含义按接口及层级限定：不得把这里的外层失败码与挂失接口的内层 `data.retcode=60007` 混用。本人充值同码通过共用代码离线覆盖；本份实际 HAR 直接证明的仅是代充值拒绝。

本次只读核对官网 JS／语言包及用户提供 HAR，离线测试不连接学校、不发起真实充值、消费、挂失或解挂。未修改登录／锁定逻辑，也不清除 Cookie 或自动重登。


### 上轮状态复查

此前对全部 `Failed` 状态禁用查单过宽，可能阻止微信拉起失败或首次查得未付款后的只读核验。现仅明确的业务拒绝停止查单（共用分类见 2026-10-27 复核），且这个标记只在单次尝试内有效，关闭／新开后清除；已成功状态也不重复查询。其他失败状态仍可重新只读查单，不重复创建订单或发送支付。


### 首页余额与充值入口解耦

账户列表不再按挂失／冻结标记过滤整个 `ACCOUNT`。如官网一样，先保留账户和余额，服务端 `status=0` 仅表示本次付款码不可用；可用时由取码接口返回结果。取码请求失败、无条码或返回业务错误也不丢掉本次余额，不把取码失败当作登录失败。首页充值入口不再要求 `state.account != null`，已知余额在取码刷新期间继续展示，只有从未取得余额时才显示加载／占位。进入充值后仍按官网充值配置决定目标是否可选，最终错误由服务端返回；不凭挂失状态自行禁止余额或充值入口。
