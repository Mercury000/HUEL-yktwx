# 哈啰账号检查与无骑行结果核验

账号检查仅借用官方原生单车查询 `user.ride.bikeCheck` 检查响应体的登录失效码，不读取或核对 `data.ride.order`。只有官方 `103/-10003` 使账号进入登录失效状态。没有订单、其他骑行业务结果及无法完成的检查均不写入账号错误提示；无法完成检查时保留凭据及未确认状态，不把账号标成已验证。订单字段仅供独立的骑行发现与恢复流程使用。

依据用户提供的哈啰 7.0.75 APK，SHA-256 为 `7e7362c0099069334be458347e567b87eb9baf0ab0b1f4ea8a410246d3e7c62b`。REA 分析的 `hellobike-payment-slice.apk` 只复制原始 DEX、清单和资源，SHA-256 为 `0a324bde7bfd7ec83955948edacc53f79287f80cd04f9c785de4cbacb91fc307`。原始条目摘要、4 项完整 REA 记录、原 DEX 注解与静态字段解码保存在[核验文件](hellobike-account-check-evidence.json)。没有执行 APK，也没有发送真实骑行或开关锁请求。

| 官方位置 | 确认内容 | Evidence |
| --- | --- | --- |
| `BikeRideCheckApiModel` 原 DEX 类注解 | `ActionValue("user.ride.bikeCheck")`，继承原生 `BaseApiModel` | 原始 DEX 注解解码，见保留的核验文件 |
| `BaseApiModel.<init>` 与解析字段清单 | 原生公共字段为 action、clientId、systemCode、ticket、token、version、__sysTag；没有 H5 或 bizType 字段 | `ev_ece3d5f913af31c76e8678e77ebdeffc2ad8d23e3c68b79efbf254928084a70c` |
| `RidingOrderRepository.a`（Kotlin 名 checkOrder） | 请求 `CommonNetService.a(new BikeRideCheckApiModel())`；`!isSuccess()` 返回 null；成功数据的 ride 或 order 为空也返回 null | `ev_e3ec35e6f9eea6d0ff3e22b48f5cd39681d0432a85325726e6b9bf341f83fd4c` |
| `BikeCheckRide`、`BikeRideCheck` | 返回路径为 `data.ride.order`，订单字段为 orderGuid、bikeNo、status；原 DEX 常量 GOING=0、BILLING=1、FINISH=2、ABNORMAL=3 | `ev_37b92a7fca2ee4a28afbb4fbffed6e7d7f56f86f2c6e7500a01e602e6858a207`、`ev_30b84f3c1e9d468bbbf6a38b029c6d46c9a145ff19212eafdab4732e6f29dc3a` 与原 DEX 静态字段解码 |

原 DEX 注解同时确认 `CommonNetService.a` 使用空路径 `POST` 和 `MustLogin`；既有官方网关初始化证据给出单车基址 `https://bike.hellobike.com/api`。仅对骑行发现采用官方非成功／空结果语义，不把业务码 301 单独定义成「无骑行」，也不通过提示文字猜测结果。

登录失效仍优先按已核验的 `103/-10003` 清除凭据。HTTP 拒绝、网络失败、无法解析的响应不能证明账号过期，不作为哈啰账号行的「检查失败」或错误摘要。独立的骑行查询有订单对象却缺少订单标识或状态时，仍报告不完整信息，不虚构订单。无骑行不会直接结束本地原订单，原订单查询、预校验、开还车和支付的拒绝处理保持各自规则。

证据确认静态客户端处理，不代表已经观测用户设备的实际响应码，也不代表其他原型接口和整套线上业务均已验证。界面回归使用模拟返回「无骑行中订单」，核对自动恢复和手动检查均显示已登录；另覆盖登录失效、HTTP 失败、原订单和还车拒绝不误判成功。

以上既往定向测试没有覆盖账号检查与骑行恢复的异步状态覆盖，不能作为整条账号提示路径正确的依据。

## 2026-11-16 账号与骑行业务状态串写修复

复现根源在 `HellobikeViewModel.resume`：它无条件请求 H5 骑行页接口，并在异常时调用账号核验器的 `failed`；成功时还调用 `verified`。因此独立的骑行业务结果可以覆盖账号检查。原账号检查也额外解析订单字段并比较 `userGuid`，偏离了仅检查登录失效的要求。这些判断和状态写入均已删除。

本次 REA 再读原 APK 的既有 DEX 切片，完整结果保存在[本次证据](evidence/hellobike-account-expiry-root.json)：

- `RidingOrderRepository.a`：非成功或空订单返回 null，Evidence `ev_8a88fc6f22d51d0f7c74769c703d747494d6d1e33041177d377057d11085e876`。
- `OrderStateHubHelper$jumpToRiding$1$1.invokeSuspend`：只有非空原生订单才调用骑行页面入口，Evidence `ev_53d105cd8e675cdcd802abefc2da7de9760bbedbddb1e1593e6034569035be8f`。恢复流程据此先发现订单；无新订单时继续查询本地待确认的原订单，不进入新的骑行页面查询。
- `AfterHomePageInitTask.AnonymousClass1.invokeSuspend`：消费原生查单结果以管理定位服务，没有将空订单变成账号失败，Evidence `ev_fbfb518ccfabf5fe6accb2ed0eee8193102d706ccf69ec5a3b3797409fae773b`。

账号过期提示的范围由用户要求确定；上述官方方法证明的是骑行发现和页面进入条件，不证明官方存在本项目的账号设置行。没有按响应文案或单独业务码新增「无订单」判定。

新增定向用例分别控制原生账号查询和 H5 恢复查询，覆盖空订单不进入骑行页、恢复错误晚于账号检查返回、网络错误不成为账号错误、官方过期仍清除凭据，以及账号核验不解析骑行字段。另验证本地待开锁原订单仍可恢复及其他平台原有错误展示行为。

本轮执行上述 7 项定向测试，全部通过，0 失败、0 错误。验证使用已提交版本的独立副本并仅复制本轮改动，日志为 `build/code-warning-review/account-root-fix-tests.log`；未混入工作区其他主题改动，也未执行全量测试、Lint 或实车操作。
