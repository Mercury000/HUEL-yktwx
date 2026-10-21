# 电费、水费与校园网缴费接口调查

核验日期：2026-10-21（Asia/Shanghai）。离线分析用户提供的本地 `缴费..har`（207 条请求），并只读取旧缴费前端 JS。**没有重放抓包、没有创建订单或缴费、没有使用 ADB。** 原始 HAR、密码、Token、Cookie、订单 ID、房间 ID、校园网用户 ID 和键盘映射不进入仓库。

## 结论和验证范围

本次抓包来自普通手机浏览器，业务请求不含 MicroMessenger 标识，实际支付表单为 `isWX=0`。与此前微信网页样本不同，本次已经证明郑东宿舍电费的一卡通和微信 H5 流程在该浏览器中可用；不能据此保证所有设备或所有收费项目均已验证。

| 收费项目 | feeitemid | 当前快捷金额（元） | 可选支付方式 | 本次验证范围 |
| --- | --- | --- | --- | --- |
| 郑东校区学生宿舍电费 | 410 | 10、50、100 | 电子账户、微信 | 两笔 1 元订单：一卡通和微信均查单成功 |
| 文北校区学生宿舍电费 | 409 | 10、50、100 | 电子账户、微信 | 查询、下单、账户准备；没有实际支付结果 |
| 新校园全光无线网网费（融合用户） | 412 | 15、30、45 | 仅电子账户 | 用户套餐查询、15 元下单、账户准备；没有密码提交或成功结果 |
| 家属区电费 | 448 | 50、100、200、500 | 电子账户、微信 | 查询、1 元下单、账户准备；没有实际支付结果 |
| 家属区水费 | 449 | 50、100、200、500 | 电子账户、微信 | 查询、1 元下单、账户准备；没有实际支付结果 |
| 商业街水电费 | 408 | 500、1000、2000 | 电子账户、微信 | 查询、1 元下单、账户准备；没有实际支付结果 |

这些项目名、ID、金额和渠道来自本次样本，应读取当前配置，不应固定为所有账户或所有时间的永久规则。宽带已观察到只有一卡通渠道；**抓包中没有宽带实际支付成功，不将它写成已经实测扣费成功。**

## 固定源站与鉴权

学校源站：`https://yktwx2.huel.edu.cn`。

业务接口请求包含：

- `synjones-auth: bearer <access_token>`。
- 官网旧缴费前端公开的 OAuth Basic 客户端标识，客户端名是 `charge`，不同于移动服务平台登录客户端。
- `Content-Type: application/x-www-form-urlencoded` 用于 getThirdData 与 blade-pay 表单。
- 本次学校请求同时携带 Cookie。仅凭 Token/公开客户端标识的原生查询与支付仍未实测，不能把这份 HAR 当作无 Cookie 已验证。

仅向学校固定 HTTPS 源站提交学校凭证。微信中间页请求不携带学校 Token、Basic 或 Cookie。不关闭 TLS 校验，不自动跟随带凭证的重定向，不保存整个接口响应。

## 1. 读取收费项目配置

`GET /charge/feeitem/singleFeeitem?feeitemid=<项目>`

顶层响应：`{code, msg, view, feeitem, sceneinfo?}`；本次 `view=choose`。

读取 `feeitem.name`、`layout`、`retain_money`、`maxmoney`、`flag`、`impl_interface` 等。`sceneinfo` 可能记录上次选中的房间，是个人信息，不能公开。

- 项目 408：本次最小 1 元、最大 2000 元。
- 项目 448/449：本次最小 1 元、最大 1000 元。
- 项目 409/410/412：本次 retain_money/maxmoney 为空，不能认定服务端无金额限制。
- `layout` 不是唯一允许金额：本次 410 的快捷金额为 10/50/100，但用户自行提交 1 元并成功支付。

不要套用卡片充值的客户端 500 元上限到所有缴费项目；读取缴费配置，并针对有明确上限的项目正确限制。

## 2. 级联查询与费用信息

`POST /charge/feeitem/getThirdData`

### 宿舍电费（409/410）

表单均带 `feeitemid`：

1. `type=select, level=0`：查询校区。
2. `type=select, level=1, campus=<值>`：查询楼栋。
3. `type=select, level=2`，再带 `building`：查询楼层。
4. `type=select, level=3`，再带 `floor`：查询房间。
5. `type=IEC, level=4`，再带 `room`：查询剩余电量及下单目标信息。

列表响应为 `map.total[]` 的 `{code, level, name}` 和 `map.data[]` 的 `{name, value}`。从服务端 value 取选择值，不以显示名字代替 ID。

最终 `map.data` 包含 `area`、`building`、`floor`、`room`、`aid`、`account`、名称和 `extdata`。显示电量在 `map.showData.信息`，本次是“当前剩余电量为…度”的文本，不是固定的 balance 数字字段。

### 商业街、家属区水电费（408/448/449）

同样使用 select 0～3 和 IEC 4，但级联参数名不同：

`xiaoqu_id → loudong_id → louceng_id → room_id`。

最终 `map.data` 包含这些 ID，以及 `allAmp`、`usedAmp`；显示信息在 `map.showData`，标签分别为剩余水电量、剩余水量、剩余电量。

**配置的 interfacechoice 文本与实际 map.total 的 code 不一定相同。** 本次部分配置写 campus/building/floor/room，真实列表要求 xiaoqu_id 等，应按接口返回的级联 code 建参数。

### 宽带/校园网（412）

本次请求只有 `feeitemid`、`type`、`level`，不输入宿舍房间：

- `type=select, level=0`：读取查询结构。
- `type=IEC, level=0`：查询当前登录用户的套餐/状态。

最终 `map.data` 包含 `packageName`、`userId`、`statusflag`，`map.showData` 包含“用户套餐”“用户状态”。不要因为 interfacechoice 写了房间而强行加入 room，也不要接受任意 userId 去替其他用户查询/缴费。

## 3. 创建缴费订单

`POST /blade-pay/pay`，本次表单：

- `feeitemid`、`tranamt`（元）。
- `flag=choose`、`paystep=0`。
- `source`、`abstracts`、`redirect_url` 按官网当前协议；本次部分字段为空。
- `third_party`：将最终查询的 `map.data` JSON 序列化，官网可附加 `myCustomInfo` 展示信息。**不同于卡片充值的单个账户 ID**，不要直接套用充值目标。
- `APP_ID`、`TIMESTAMP`、`SIGN_TYPE=SHA256`、`NONCE`、`SIGN` 使用旧缴费前端公开接入签名协议；不记录真实签名或键盘值。

本次响应 `code=200`、`data.orderid` 等；取得 orderid 只代表创建/准备流程完成，不是支付成功。

`GET /charge/pay/getpayinfo?orderid=<本人订单>`

从顶层 `payList` 动态选择渠道：

- 一卡通：`code=ACCOUNTTSM`，本次 `payid=64`、`nopassword=0`、`chooseAccount=false`。
- 微信：`code=TSM`，本次 `payid=101`、`remark=weixin`。
- 宽带订单本次顶层 payList 仅 ACCOUNTTSM，无微信。中间步骤的 data.payList 不一定是相同结构，应使用实际订单支付方式列表。

下单、密码支付、微信预支付都不能自动重试。一个接口 code=200 或 data.paystep=2 也不能当作扣款。

## 4. 一卡通电子账户支付（410 已有真实成功样本）

准备账户：再请求 `POST /blade-pay/pay`，带 `orderid`、`paytypeid`、`paytype=ACCOUNTTSM`、`paystep=2` 和签名。

响应 `data`：

- `accountno`。
- `ccctype[]`：电子账户类型和 balance；官网直接按元格式化该接口 balance，不能和账单/卡查询的分单位混用。
- `passwordMap`：本次为 `{<键盘uuid>: <十位数字排列>}`。

密码键盘编码（官网 JS 与实际提交交叉核对）：

1. 网页显示数字排列对应的数字。
2. 用户点击后，提交的是数字在这次排列里的**索引位置**（0～9），不是明文数字。
3. 六位位置编码组成 password；uuid 取 passwordMap 的键。
4. 必须用当前订单/当前返回的映射，不跨订单或重用过期键盘。

最终 `POST /blade-pay/pay`：

`orderid, paystep=2, paytype=ACCOUNTTSM, paytypeid, ccctype, password=<六位位置编码>, uuid, isWX=0` 加签名。

本次没有 `accountno`、`pwdType` 字段；官网在 chooseAccount=false 场景可由服务器选默认账户，客户端不能凭空添加或混用其他支付分支。

本次返回 `code=200`，`data` 为订单号字符串；之后查单 `status=1`、`tranamt=actulamt=1`、项目 410、渠道 ACCOUNTTSM。

这是缴费支付密码协议，**不是网页登录密码的 Standard 键盘协议**。不能自动使用本机保存的登录密码付款；支付密码由用户输入，只在内存中编码，不持久化、日志输出或上传其他服务。官网还存在免密/短信验证分支，按配置走，不绕过验证。

## 5. 微信 H5 支付（410 已有真实成功样本）

最终 `POST /blade-pay/pay` 带同一 orderid、`paystep=2`、`paytype=TSM`、`paytypeid`、`isWX=0` 及签名。本次无 userAgent 参数。

响应 `data` 为 `{orderid, paysubmit, redirect_url}`，不是此前微信网页的 JSAPI 参数。

- paysubmit 是 POST 表单，action 指向 `https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb`。
- 嵌套 redirect_url 指向学校 `/charge-app?name=payResult&orderid=<订单>&payid=<渠道>`。
- 实际抓到微信 checkmweb 的 POST，请求 Referer 是学校源站，无学校 Token 请求头。
- 后续同一订单查单 `status=1`、`tranamt=actulamt=1`、项目 410、渠道 TSM。

可参考既有充值的原生 H5 表单桥接实现，但其校验目前固定 `/payment/` 返回地址和 CAMPUSCARD 渠道；缴费需单独允许受约束的 `/charge-app` 返回地址并验证订单/支付渠道，不能无限放宽域名或执行返回任意 HTML/JS。

## 6. 结果与到账边界

`GET /charge/order/personal_data?orderid=<本人缴费订单>`

本次两个真正完成支付的项目都是 410：分别由电子账户和微信支付，响应 orderList 只有匹配订单，status=1，金额 1 元。

验证目标、项目、金额、订单、登录身份与渠道。学校第三方参数是 JSON，不适用卡充值里 third_party == 单个 account ID 的匹配方式。

支付成功和电量/宽带套餐最终生效应分别表述。本次有一卡通后续电量查询，但没有宽带最终扣费或套餐更新样本，也没有各生活区最终支付样本；不能承诺这些后端执行结果全部验证。

## 接入建议

原生统一缴费页面可共享：项目选择、动态级联/当前用户查询、费用信息、配置金额、支付渠道、密码弹窗及结果页。

- 410：首批接入一卡通与微信 H5；已有完整成功证据。
- 409/408/448/449：可复用框架，动态解析各自 map.total/code 和 third_party；只把查询/下单已验证当作当前覆盖范围。
- 412：当前用户套餐查询 + 一卡通渠道；需要后续用户自愿实际支付时验证最终结果，不为验证额外扣费。
- 不写死支付渠道/ID；宽带不显示未返回的微信选项。
- 不保存或重放密码、键盘映射或原始 HAR；凭证只发送学校固定源站。
- 纯原生 Token 鉴权需另外核验，因为这份样本同时有学校 Cookie。仅分析静态 JS 不能保证去掉 Cookie 后行为完全相同。

参考前端静态资源：`/charge-app/static/js/app.1c8252c467c184eabc77.js`。本次只读取其文本，核验 getAccounttype、getPasswordKey、keyUpHandle、submitPay、postPay 与结果查询分支；没有执行支付脚本。
