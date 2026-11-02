# 一卡通内部接口说明

本文说明客户端的一卡通登录和业务接口。网络与存储结构见[项目架构](architecture.md)。

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

先通过 `GET /berserker-app/ykt/tsm/codebarPayinfo` 读取账户与余额，再通过 `GET /berserker-app/ykt/tsm/batchGetBarCodeGet` 请求付款码。账户参数从响应读取，解析 `barcode` 与 `expires`。

业务请求使用 `synjones-auth: bearer <access_token>` 与 `synAccessSource: h5`，取码失败清除旧码并显示服务端原因，不记录完整付款码。

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


## 校园卡管理

卡状态使用 `GET /berserker-app/ykt/tsm/getCampusCards`，目标是卡片 `account`。挂失与解挂分别使用 `POST /berserker-app/ykt/tsm/lostCard`、`unlostCard`，按配置提交 `account`、`synAccessSource` 和数字安全键盘编码的查询密码。成功核对外层、内层返回码与目标状态；响应未返回新状态时重新查询。超时先查状态，不自动重发。明确拒绝保留平台原因，存在目标回显时必须匹配。

修改密码使用 `POST /berserker-app/ykt/tsm/modifyPwd`，提交 `account`、`oldpw`、`newpw`、`renewpw`、`synAccessSource`。密码使用同次数字安全键盘映射与 UUID，成功要求外层 `code=200`、内层 `retcode=0`，随后清除旧凭据和 Token 并返回登录页。

## 充值与代人充值

| 接口 | 用途 |
| --- | --- |
| `GET /charge/feeitem/singleFeeitem` | 充值项目 |
| `GET /berserker-app/ykt/tsm/queryCard?scene=recharge` | 可充值卡 |
| `POST /charge/order/thirdOrder` | 创建订单 |
| `GET /charge/pay/getpayinfo?orderid=<订单>&userAgent=h5` | 原订单和付款方式 |
| `POST /blade-pay/pay` | 支付参数 |
| `GET /charge/order/personal_data?orderid=<订单>` | 订单结果 |

收银台金额以元计，卡余额和账单以分计。JSAPI 参数不是 H5 跳转地址，回跳和 HTTP 成功不代表到账，必须核对订单、金额、目标和服务端结果。代人充值目标来自响应，验证码由用户完成，修改目标或退出时清除旧授权。本人和代充值共享拒绝处理，明确挂失拒绝不拉起支付、不自动重试。

## 水电与校园网缴费

按项目配置读取级联位置、房间或账户，选择有效目标后下单。付款方式来自当前项目与原订单，不由本地余额推断。密码和安全键盘属于原订单，不保存或复用到新单。结果未知保留原单只读查询，取消恢复不重放下单。临时缴费与保存绑定分别处理，电表、水表只按明确有效关联配对，不凭名称猜测。

## 消费反馈

转回检测通过同账户余额变化显示消费提示并刷新付款码，在应用设置中开启。充值缴费独立查询原订单状态，确认成功后更新相关余额与账单数据。
