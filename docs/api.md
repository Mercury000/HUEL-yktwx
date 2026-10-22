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

账单列表使用 `/berserker-search/search/personal/turnover`，汇总使用 `/berserker-search/statistics/turnover/count`，分类与趋势通过对应 statistics 接口读取。金额以分处理，收入与支出方向由响应字段决定。

## 校园卡管理

卡状态使用 `GET /berserker-app/ykt/tsm/getCampusCards`，目标是卡片 `account`。挂失与解挂分别使用 `POST /berserker-app/ykt/tsm/lostCard`、`unlostCard`，按配置提交 `account`、`synAccessSource` 和数字安全键盘编码的查询密码。成功核对外层、内层返回码与目标状态；响应未返回新状态时重新查询。超时先查状态，不自动重发。明确拒绝保留平台原因，存在目标回显时必须匹配。

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

