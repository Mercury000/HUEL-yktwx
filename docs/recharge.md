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
| `2` | 交易超时 | 明确显示超时/待核验，避免双重充值 |
| 空 `orderList` | 补出 `status=0`，参与相同的有限查询 | 成功响应的空数组按未支付处理，最终显示充值失败 |
| 其他 / 网络异常 / 格式异常 | 未确认 | 不展示成功，也不将错误伪装成取消或未支付 |

关键字段：`orderid`、`status`、`tranamt`、`actulamt`、`payid`、`payBean.name`、`feeitemlist[].feeitemid`、`redirect_url`。**此处 `tranamt` 和 `actulamt` 单位是元**：官网 money 函数直接格式化，没有除以 100。

官网结果页在完成回跳时，会在订单 `redirect_url` 后追加 `orderid`、`payid`。回跳参数只用于定位/唤醒，仍要用当前登录身份重新查询服务器，不能信任 URL 中的 `status=success`。

官网源码还有 `${getFrontConfig.websocket}mobile_service_platform/pay_result/<orderid>` 消息监听。本次没有连接，鉴权、消息绑定及真实性未验证，暂不作为本 App 的成功判据。

**支付订单成功不应无条件等同于已核验校园卡实际到账。**显示“充值成功/已到账”前，应结合学校可用的充值执行状态或可关联到账记录确认；仅余额变大也不足以证明指定订单到账，可能有其他并发交易。若只能核验支付 `status=1`，文案应为“支付成功，到账状态待确认”，并刷新卡片信息。

## 7. 当前 Android 交互与原始接入建议

当前实现已调整为原生金额弹窗和原生结果弹窗，不使用 WebView。结果顺序为图标、状态文本、金额，底部仅“完成”；支付结果不使用 Toast，只有读取配置失败等入口错误使用 Toast。

每次打开充值都进入新的金额选择。仅保留本次内存状态，关闭后结束，不读取旧订单、不归档、不后台轮询，也不阻止下一次充值。从本次微信返回时按官网普通结果页执行最多三次查询，首次立即查询，后两次各间隔 5 秒；任一次成功立即显示对勾及“充值成功”。有效成功响应的空 `orderList` 按官网补为 `status=0`，最终仍为 0 显示叉号及“充值失败”；状态 2 显示超时。网络异常、响应格式异常或订单匹配失败仍显示“结果待确认”，不把这些异常当作未支付。关闭弹窗或新开充值时取消本次剩余查询；失去焦点或离开前台时暂停，恢复交互后仅续查当前弹窗对应订单的剩余次数，不恢复历史订单。`status=0` 不代表收到微信取消回调，也不保证服务器订单已关闭；不将返回 App 本身当作取消证据。关闭本地界面不代表取消服务器订单，不自动重试下单。

### 静态复核后的解析与中断边界

- 已有待支付 `getpayinfo` 抓包中 `status`、`payid`、`actulamt` 为 JSON null；成功 `personal_data` 抓包中这些字段有值。没有取消支付的 `personal_data` 样本，不能断言其字段相同。
- 官网普通结果页只在空列表合成 `status=0`；`i.status || set(status, 0)` 位于 `payWsData/payData` 兜底分支，不能据此把任意现有记录的空状态归为失败。现有记录空/无效状态继续作为未知处理。
- 当前客户端先确认返回订单 ID。未完成订单允许支付渠道、金额、目标及项目字段暂时缺失；如果字段有值却与本次订单冲突，仍拒绝。成功记录仍须完整匹配目标、收费项目、金额、支付渠道及实付金额。
- 单次网络、解析或匹配错误也计入三次预算，后续只读查询仍可恢复；最终错误不借用之前的 `status=0` 伪装成失败。登录失效或账户切换提前停止。
- 暂停当前查单不会直接制造“待确认”；恢复焦点和交互后等待旧协程取消完成，再继续剩余次数。被打断的只读请求可能重发，不重放下单或支付 POST。
- 这些改动只经本地 JS/代码静态复核及编译，不代表真实取消支付行为已经验证。

下方为早期接入调查建议，涉及持久化 pending 与恢复流程的条目已被上述当前行为替代。微信实际唤起仍需真机验证。


建议链路：

```text
输入/确认金额和本人账户
  -> 创建一笔订单并绑定本地 pending 状态
  -> 查询该订单支付渠道
  -> 学校收银台 H5 / 实际响应指定的合法微信跳转
  -> 回到 App（回跳、前台恢复、手动“我已支付”）
  -> 重新查询同一订单
  -> 支付成功 + 到账已核验：充值成功页
  -> 仅支付成功：支付成功、到账待确认页
```

- 不能只用 `Intent` 打开微信首页就当作调起指定订单支付。
- 优先保持学校原有 H5 收银台路径；若需要商户 H5 支付链接，验证允许的 HTTPS 域名、Referer、回跳规则，不给第三方域名附加学校凭证。
- 学校签名表单必须 POST 至固定源站，不能为了外部浏览器登录把长期 Token 放进 query、剪贴板或分享链接；实际可用的短期收银台 URL 需先核验。
- 若采用独立支付 WebView，应隔离可信学校页面与外部页面，不开放通用 JavaScript bridge，不执行服务器任意脚本指令；明确校验外部 HTTPS/微信 scheme。
- App 层候选回跳如 `yktwx://recharge/result?state=<随机值>`，需要先核验学校是否接受，尚未写入 manifest。回跳 URI 不包含 Token 或个人账户信息。
- 本地 pending 绑定登录账户、服务端 orderid、金额、收费项目、随机 state。拒绝其他账户、未知订单、重复回调和任意回跳参数。
- 单笔状态：Idle / Creating / AwaitingPayment / Checking / PaidAwaitingCredit / Succeeded / Failed / Unknown；创建和检查互斥，避免双击下单。
- 创建/支付请求不能自动重试；读接口可有限重登/重查。网络失败、取消、超时、进程重建都保留“未确认”而非假成功。
- 页面前台恢复时有限次查询，后台停止轮询；保留手动查询入口，避免无限刷新。
- “微信异步支付通知”发给学校/其支付网关，不发给本 App。客户端回跳不是商户服务端通知。没有商户侧授权，不能改 `notify_url` 或读取/持有学校微信支付私钥。
- 原生微信 OpenSDK APP 支付要求自己的合法 AppID、包名/签名及商户配置；不能把官方 App 的参数直接拿来假定适用于当前 APK。

## 8. 外部 App 的下一步真机验证边界

1. 用户选定金额、确认本人账户；如需联网创建测试订单，应由用户明确同意。
2. 记录创建订单的响应结构/受信任跳转与订单 ID，不能记录 Token、支付签名或原始支付配置。
3. `wechat-mp` HAR 已确认待支付订单返回微信 `CAMPUSCARD`；外部 App/H5 载体仍需确认服务端返回的合法支付方式。
4. 只抽取支付响应类型与必要字段名，确认是 H5 URL、表单、二维码或 SDK 分支；由用户在微信自行确认支付。
5. 对照同一订单验证成功、取消、回跳、查单延迟、到账、重复点击和进程重启场景。

离线测试应覆盖金额单位、签名一致性、请求源站、顶层 JSON 解析、身份/金额/订单匹配、伪造回跳、支付未确认和禁止重复下单。不要用真实账户自动化发起充值作为测试。

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

- 本人／代充值复用此支付处理。已验证的外层 `60007` 且失败结构一致时，直接结束本次尝试、显示原始 `msg`；缺失／空串按官网使用 `Error`，不通过正则或文案关键词分类。
- 获取支付参数期间继续显示创建中，只有获得合法微信参数才进入待支付。明确拒绝不发送拉起事件、不等待微信返回、不自动查单、不重发支付。已生成订单仅在本次结果内存对象中保留编号，点击完成即清除，不提供继续支付／自动取消；不宣称该订单已取消。
- 非法／矛盾响应、超时、断网仍保留已有订单并允许只读核验，不自动重新下单或重发支付。未知业务错误也原样显示 `msg`，但不根据文字断言未扣款。
- 原生继续已有的保守策略：挂失／冻结目标不可选。状态提示采用官网语言包 `lostNotRchargeDescription`／`freezeNotRchargeDescription`（省略账户前缀）。官网当前 `showLost="1"` 可能允许进入流程，但本 HAR 证明支付阶段仍可能拒绝。
- 本人下单前增加一次只读目标状态检查，精确匹配卡片／电子账户，发现挂失／冻结或检查失败时不下单。代充值不拿付款人 `queryCard` 验证收款人，也不偷偷重用验证码查询目标，保留用户查询时的检查及服务端拒绝兜底。
- `60007` 的含义按接口及层级限定：不得把这里的外层失败码与挂失接口的内层 `data.retcode=60007` 混用。本人充值同码通过共用代码离线覆盖；本份实际 HAR 直接证明的仅是代充值拒绝。

本次只读核对官网 JS／语言包及用户提供 HAR，离线测试不连接学校、不发起真实充值、消费、挂失或解挂。未修改登录／锁定逻辑，也不清除 Cookie 或自动重登。
