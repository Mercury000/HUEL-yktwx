# 挂失与解挂接口调查

核验日期：2026-10-21（Asia/Shanghai）。通过 MCP DevTools 在本人官方账号上完成一次“挂失 → 解挂”，最终卡片恢复为未挂失状态。原始请求体、响应、Token、Cookie、账号、学号、证件号、密码和安全键盘映射均未写入仓库；本文只保留字段结构。

## 验证等级

- **实际验证**：无 Cookie 读取卡列表；网页会话内执行一次挂失和解挂，均返回成功；操作后重新查询确认 `lostflag=0`。
- **官网源码确认**：卡状态判断、密码弹窗条件、请求体和 `60007`/`60008` 的接受逻辑。
- **未验证**：无 Cookie 执行挂失/解挂、错误密码、重复操作、多卡选择、冻结/异常账户、网络超时、生物识别，以及 `60007`/`60008` 的确切语义。

## 1. 查询卡状态

```http
GET /berserker-app/ykt/tsm/getCampusCards?synAccessSource=h5
synjones-auth: bearer <access_token>
synAccessSource: h5
```

本次使用 `credentials: omit` 加 Token 请求成功，不依赖网页 Cookie。响应中的卡片位于 `data.card[]`，只解析操作需要的字段：

| 字段 | 含义 |
| --- | --- |
| `account` | 卡片统一账户，挂失/解挂请求使用此值，不是学号，也不是 `accinfo[].type` |
| `sno` | 学号，仅用于展示 |
| `lostflag` | `0` 未挂失，`1` 已挂失 |
| `freezeflag` | 冻结标记；与挂失不是同一状态，不能在此接口擅自解除 |
| `acc_status` | 账户状态；异常时不应继续执行写操作 |

响应还可能包含姓名、证件号、余额、账户列表等个人或账户字段，客户端只解析所需字段，不保存或输出完整响应。一个用户可能有多张卡，操作必须针对用户选中的卡片 `account`，不能假定只有一张卡。

## 2. 挂失

```http
POST /berserker-app/ykt/tsm/lostCard
Content-Type: application/json;charset=UTF-8
synjones-auth: bearer <access_token>
synAccessSource: h5
```

无查询密码时的请求体：

```json
{
  "account": "<card account>",
  "synAccessSource": "h5"
}
```

需要查询密码时，官网附加：

```json
{
  "account": "<card account>",
  "pwd": "1$1$<encoded 6 digits>$1$<keyboard uuid>",
  "pwdType": 1,
  "synAccessSource": "h5"
}
```

官网是否弹出密码框由运行时配置 `lockFlag` 控制。本次挂失没有要求用户输入查询密码，但抓到的请求中仍出现了空的安全键盘字段；该空值不应被复用，也不能视为可绕过密码校验的协议。客户端应按官网配置处理，或明确要求用户输入查询密码，不能硬编码空密码。

## 3. 解挂

```http
POST /berserker-app/ykt/tsm/unlostCard
Content-Type: application/json;charset=UTF-8
synjones-auth: bearer <access_token>
synAccessSource: h5
```

请求体与挂失相同，`account` 为目标卡片统一账户。本次解挂弹出了 6 位查询密码，实际请求带有：

```json
{
  "account": "<card account>",
  "pwd": "1$1$<encoded 6 digits>$1$<keyboard uuid>",
  "pwdType": 1,
  "synAccessSource": "h5"
}
```

官网是否要求密码由 `unlockFlag` 控制；本次账号配置下解挂要求输入查询密码。密码值不是明文，不能记录、回显或写入日志。

## 4. 查询密码与安全键盘

查询密码走官网数字安全键盘，不直接提交 6 位明文：

```http
GET /berserker-secure/keyboard?type=Number&order=1&synAccessSource=h5
synjones-auth: bearer <access_token>
synAccessSource: h5
```

响应 `data` 中本次使用的字段：

- `numberKeyboard`：数字 0-9 的本次映射。
- `uuid`：本次键盘会话标识，用于拼接 `pwd`。
- `numberKeyboardImage`：键盘图片，客户端不需要解析。

编码格式：

```text
1$1$<6 个按本次映射编码的字符>$1$<keyboard uuid>
```

`pwdType=1` 表示安全键盘。官网源码还支持生物识别分支 `pwdType=0`、`pwd="0$1$<equipmentId>"`，本次未使用，不应在没有真机验证的情况下接入。

现有登录用的 `KeyboardEncoder` 面向 `type=Standard`，要求四组字符映射，并且输出不含 `1$1$` 前缀；不能直接复用于 `type=Number` 的查询密码。接入前需要增加只处理数字映射的路径，并保持 `uuid` 与本次键盘会话一致。

## 5. 响应与成功判定

成功响应的外层结构：

```json
{
  "code": 200,
  "success": true,
  "data": {
    "retcode": "0",
    "errmsg": "<message>",
    "account": "<card account>",
    "sno": null
  },
  "msg": "<message>"
}
```

判定规则：

- HTTP 200 只表示请求到达服务端，不能单独认定操作成功。
- 必须同时检查外层 `code=200` 和 `data.retcode`。
- 官网源码对挂失接受 `retcode=0` 或 `60007`，对解挂接受 `retcode=0` 或 `60008`；本次只实际观察到 `0`，`60007`/`60008` 的含义未独立验证。
- 响应若回显 `account` 则校验与请求卡片一致；未回显时不强行判失败，仍以请求目标的后续状态查询确认，避免跨卡误判。
- 失败时优先展示 `data.errmsg`，没有时再使用外层 `msg`；不要因 HTTP 200 显示成功。
- 响应不返回新的 `lostflag`，操作成功后必须重新查询卡列表确认状态。

## 6. 客户端约束

- 挂失/解挂是一次性写操作，不自动重试；超时后先重新查询卡状态，避免重复提交。
- 操作期间禁止重复点击，按卡片串行处理。
- 成功后重新调用 `getCampusCards`，以 `lostflag` 为准更新界面；不能只做本地乐观更新。
- 若写请求成功但刷新失败，显示“操作已提交，请刷新确认”，不要再次发送写请求。
- 挂失与解挂不要同时支持批量操作；不同卡片的 `account` 必须逐张确认。
- 不把统一账户、学号、密码、键盘 UUID 或完整响应写入日志、崩溃上报或仓库。
- 冻结、账户异常、密码错误、重复操作等分支未验证，不能自行推断为成功。

## 7. 待验证项

- 无 Cookie 环境下执行挂失/解挂是否同样成功。
- 错误查询密码的响应码和锁定策略。
- 重复挂失/重复解挂是否稳定返回 `60007`/`60008`。
- 多卡账户、冻结卡、异常账户的操作限制。
- 网络超时后服务端是否已落库，以及安全的重试/查询顺序。
- 生物识别分支和真机 App 载体。

## 8. 2026-10-22 只读复核补充

本次通过 MCP 读取当前官网组件源码、配置和本人卡状态，**没有再次执行挂失或解挂**。本次 `getCampusCards` 在 `credentials: omit`、仅带 Token 请求头时仍返回 `code=200`、`data.retcode="0"`，当前卡片 `lostflag=0`、`freezeflag=0`、`acc_status=0`；只记录状态，不记录卡片账户和个人信息。

### 密码弹窗与请求字段条件并不完全一致

当前官网 `cardOperation` 组件的密码弹窗条件：

- 挂失：`frontConfig.lockFlag === "1"` **或** `businessHandleConfig.lockFlag === "1"` 时弹出查询密码。
- 解挂：`frontConfig.unlockFlag === "0"` **或** `businessHandleConfig.unlockFlag === "0"` 时不弹出密码；否则走查询密码（官方 App 中还可能先走生物识别）。
- `businessHandleConfig` 来自 `$businessConfig.businessHandleConfig` 的 JSON 字符串，不属于 `$ecardConfig`。

但写请求构造只检查 `frontConfig`：`lockFlag !== "0"` 或 `unlockFlag !== "0"` 时添加 `pwd`、`pwdType`，没有同时检查 `businessHandleConfig`。因此，**字段缺失不等价于字符串 `"0"`**；不能把缺失配置解释为无需密码。

本次当前页面内存中的上述四个配置字段均未提供。按现有源码，此组合会造成挂失不弹出密码、却仍构造安全键盘字段，解挂默认弹出密码。这也解释了先前抓包中的空安全键盘字段，但只是源码行为，不证明服务端允许绕过密码。客户端不应复用空密码或空 UUID。

密码输入源码还区分 `$ecardConfig.passwordType`：值转字符串后为 `"1"` 时使用另一输入模式，其他情况为 6 位数字路径。本次该字段缺失，适用数字路径；不能把 6 位数字限制视为所有学校或未来配置的通用约束。

### 实现边界

当前 Android 已接入 `lostCard` / `unlostCard`：付款页校园卡服务使用一个状态入口，单卡自动选择、多卡用户选择，再按实时 `lostflag` 显示挂失或解挂确认。使用独立数字安全键盘编码；挂失仅在 `frontConfig.lockFlag` 或业务配置 `lockFlag` 为 `"1"` 时要求密码，解挂任一 `unlockFlag="0"` 时免密码，否则要求 6 位查询密码。不复用登录密码或空 UUID。写请求不自动重登重放，操作后重新查状态，并失效旧付款码；超时只读复查结果。离线接口、视图模型及 UI 测试已覆盖；无 Cookie 真实写操作、错误密码及 `60007` / `60008` 的含义仍未在本次真实账户验证。


## 9. 2026-10-22 交互和编码修正

- 状态入口每次打开自动读取最新卡状态；单卡直接显示挂失或解挂确认，多卡才出现选择。使用简洁确认对话框，删除手动“刷新卡状态”和重复说明；用户仅确认本次操作。
- 挂失密码条件遵循官网 `onChange`：`frontConfig.lockFlag === "1" || businessHandleConfig.lockFlag === "1"`。当前两个字段均缺失，因此挂失不弹出密码，也不提交空安全键盘占位字段。解挂则任一配置明确为 `"0"` 时免密码，否则要求查询密码。
- 业务配置正确来源为 `frontInfo.data.getBusinessConfig.businessHandleConfig`，其中 `businessHandleConfig` 可为 JSON 字符串。先前从 `data.businessHandleConfig` 读取不正确，已修复。
- **解挂编码错误已修复**：只读检查键盘图像确认 `Number&order=1` 是乱序图像，`numberKeyboard[i]` 对应第 i 个图像位置，不对应数字 i。旧原生实现请求 `order=1` 后按数字下标取映射会编码成错误数字。原生现在请求 `Number&order=0`，本次图像顺序实际为 0–9，使用同次映射和 UUID 编码。不是绕过密码，也不识别乱序键盘图片。
- 官网组件在用户点错密码时刷新键盘；原生每次用户主动重新提交都会请求新的键盘。不自动重试写操作；明确的业务拒绝保留确认界面，用户重新输入，未知结果仅只读查询。
- 本次未再次提交真实挂失 / 解挂请求。仅核对源码、配置及两种 `order` 的键盘图像，不保存映射 / UUID，不要求用户继续尝试密码来定位编码问题。

## 10. 确认弹窗只打开一次

入口读取阶段 `visible=false`，只在入口图标上显示加载，读取完成后原子设置卡状态、所选卡片及 `visible=true`，不再先打开“挂失 / 解挂”加载弹窗再切换确认标题。入口只有一个点击目标，视图模型还会忽略进行中的重复打开；读取失败直接提示，不弹空确认框。关闭或切换账号后，迟到的读取响应不能再打开确认框。上述行为有带延迟读取和重复点击的回归测试。


## 11. 解挂失败分类修正（2026-10-22）

之前除内层明确拒绝外的普通异常都被视图模型转为“操作结果待确认”，会混淆未提交、已拒绝与已提交但无法确认。现按请求阶段区分：

- **提交前失败**：配置、当前卡片查询、安全键盘请求、映射／UUID／输入校验等失败，统一标记为未开始写请求，保留原错误；不进行额外结果复查、不关闭确认界面、不声称待确认。用户可修正问题后手动提交。
- **明确业务拒绝**：解析写响应的外层 `code`／`success` 和内层 `retcode`。HTTP 4xx 拒绝或明确的非成功业务码显示 `data.errmsg`、外层 `msg`／`message`／`error_description`（按优先级取非空字符串），缺失时 `Error`；不按文案关键词推断原因，不使用正则过滤，也不复查来覆盖原始错误。内层非成功 `retcode` 不被外层 `success=true` 的封装成功掩盖。
- **确实未确认的结果**：写请求超时／断网、响应缺失或格式异常、矛盾的成功字段、回显目标冲突，以及服务端接受后读取状态失败／尚未改变，标记为写结果未确认；仅只读复查一次卡状态。目标状态达到才提示成功，否则保留原失败线索并补充“操作结果待确认，请重新查看卡状态”。不自动重发 `lostCard`／`unlostCard`。
- HTTP 401／403 等认证拒绝独立处理，保持真实登录错误，不将其解释为待确认或密码错误。
- 合法成功响应没有回显 `account` 时，不额外强制失败；最终仍查询并确认请求目标的 `lostflag`。没有延迟重发、没有更改密码编码／验证码／Cookie 逻辑。

离线测试覆盖提交前每个读取阶段失败、外层和内层拒绝、原始文案优先级、成功封装下的内层拒绝、响应矛盾、无账户回显、状态查询失败、状态未改变和用户主动重试。真实高概率失败的服务端根因仍需失败那次响应定位；本修正只解决错误归类和提示覆盖，不宣称已验证真实解挂成功率。
