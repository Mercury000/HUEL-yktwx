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

## 刷新与错误边界

- Android 使用加密保存的 Token；过期或被拒绝时，在账户仓库互斥锁中最多自动重登一次。账密无效或需要验证码时清除保存登录状态并要求重新登录，网络故障不等同于账密错误。
- Python 不自动重试登录；下次启动 `--login` 再次隐藏输入账密。
- 官方前端显示周期为 60 秒；若 `expires` 更短则缩短周期，不将批次有效期当成离线可用承诺。
- Android 每个周期重新取批次，只显示首码；后台移除当前码，回前台重新获取。
- 接口格式、键盘布局或验证码规则改变需重新验证；不支持官网键盘以外的字符。
- 多客户端取码对旧码的影响、离线付款、实际扫码消费及消费结果监听未验证。

## 测试原则

测试使用虚构学号、密码、Token、账户和付款码。HTTPS 请求构造测试使用替代连接验证参数及解析，不连接真实登录接口。真实验证只在手机或本地隐藏输入中提供凭证，不保存网络抓包或敏感响应到仓库。
