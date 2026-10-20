# 一卡通内部接口说明

记录当前客户端使用的官网协议，不代表稳定的公开 API。仅用于本人账户；不要向聊天、日志或仓库提供真实账密、Token 和付款码。

## 固定源站

`https://yktwx2.huel.edu.cn`

Python 与 Android 均只向该 HTTPS 源站请求，不依赖网页登录 Cookie，不跟随重定向发送账密，不关闭 TLS 校验。

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
   - 解析 `access_token`、`token_type`、`expires_in`。Android 加密保存访问 Token，不使用 Refresh Token；Python 仅在进程中使用访问 Token。
   - 首次登录、密码过期、验证码或账户异常需在官方渠道处理，不绕过验证。

## 获取付款码

1. `GET /berserker-app/ykt/tsm/codebarPayinfo?synAccessSource=h5`
   - 选择可用、未挂失、未冻结的统一账户 `ACCOUNT`。
   - 从响应读取 `account`、`payacc`、`paytype`，不硬编码个人账户字段。
2. `GET /berserker-app/ykt/tsm/batchGetBarCodeGet`
   - 参数：前一步的 `account`、`payacc`、`paytype`，加 `synAccessSource=h5`。
   - 解析 `data.barcode` 和 `data.expires`，不将完整码写入日志。

取码使用自定义 Token 头，与登录的 OAuth Basic 头不同：

```text
synjones-auth: bearer <access_token>
synAccessSource: h5
```

## 账单

账单请求同样使用 `synjones-auth: bearer <access_token>`，不携带网页 Cookie，不跟随重定向。列表、统计和排行均只查询当前账户；时间范围按自然月传入 `yyyy-MM-dd`。

1. `GET /berserker-search/search/personal/turnover`
   - 列表/搜索参数：`current`、`size`、`timeFrom`、`timeTo`，可选 `typeId`、`amountFrom`、`amountTo`（单位分）、`info`，以及 `synAccessSource=h5`。
   - 搜索限定当前月份并传 `info`；不在本地保存搜索词或账单明细。
   - 排行复用该接口，附加 `current=1`、`size=5`、`type=2`、`sortFields=tranamt`、`sortType=desc`。
   - 解析 `data.{records,total,size,current,pages}`；记录字段包括 `orderId`、`resume`、`payName`、`turnoverType`、`icon`、`typeId`、`typeFrom`、`tranamt`、`cardBalance`、`feeAmt`、`effectdateStr`、`jndatetimeStr`、`fromAccount`、`posCode`、`remark`、`toMerchant`、`sno`、`labelName`。
2. `GET /berserker-search/statistics/turnover/count?timeFrom=<date>&timeTo=<date>&synAccessSource=h5`
   - 解析 `data.income`、`data.expenses`，用于月收入/支出汇总。
3. `GET /berserker-search/search/turnoverType?synAccessSource=h5`
   - 解析 `data[].{id,name,icon}`，作为交易类型筛选项。
4. `GET /berserker-search/statistics/turnover?type=2&timeFrom=<date>&timeTo=<date>&synAccessSource=h5`
   - 支出分类饼图；`type=1` 为收入、`type=2` 为支出。
   - 解析 `data[].{turnoverType,amount,typeId,icon}`，按金额降序展示。
5. `GET /berserker-search/statistics/turnover/sum/user?dateStr=2026-10&dateType=month&statisticsDateStr=day&type=2&synAccessSource=h5`
   - 按日聚合；`dateStr` 为 `yyyy-MM`，响应 `data` 是日期到金额的对象。
   - 只接受可解析为 `yyyy-MM-dd` 的键，其余忽略。

约定：

- `tranamt` 等金额字段按分处理；接口可能返回 `800.0` 形式，解析时四舍五入为整数分。`tranamt` 恒为正，收支方向看 `typeFrom`：`"1"` 为收入，其他为支出。收入显示 `+`，支出显示 `-`。
- 空值字段可能为字符串 `"null"`，解析时统一转为空字符串，避免界面显示 `null`。
- 列表按 `orderId` 去重；分页加载到 `current >= pages` 后停止。
- Token 过期或被拒绝时映射为 `AuthenticationRequired`，由账户仓库最多重登一次；不在此处保存账单响应。
- Android 的账单预览模式只注入虚构记录、金额和账户名，不读取真实账单。

## 刷新与错误边界

- Android 使用加密保存的 Token；过期或被拒绝时，在账户仓库互斥锁中最多自动重登一次。账密无效或需要验证码时清除保存登录状态并要求重新登录，网络故障不等同于账密错误。
- Python 不自动重试登录；下次启动 `--login` 再次隐藏输入账密。
- 官方前端显示周期为 60 秒；若 `expires` 更短则缩短周期，不将批次有效期当成离线可用承诺。
- Android 每个周期重新取批次，只显示首码；后台移除当前码，回前台重新获取。
- 接口格式、键盘布局或验证码规则改变需重新验证；不支持官网键盘以外的字符。
- 多客户端取码对旧码的影响、离线付款、实际扫码消费及消费结果监听未验证。

## 测试原则

测试使用虚构学号、密码、Token、账户、付款码和账单记录。HTTPS 请求构造测试使用替代连接验证参数、请求头及解析，不连接真实接口。真实验证只在手机或本地隐藏输入中提供凭证，不保存网络抓包或敏感响应到仓库。
