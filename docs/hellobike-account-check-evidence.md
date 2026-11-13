# 哈啰账号检查与无骑行结果核验

账号检查使用官方原生单车查询 `user.ride.bikeCheck`，读取 `data.ride.order`。没有骑行中订单是正常结果，设置页显示账号信息和「已登录」，不展示无订单提示。登录失效和实际查询失败分别处理。

依据用户提供的哈啰 7.0.75 APK，SHA-256 为 `7e7362c0099069334be458347e567b87eb9baf0ab0b1f4ea8a410246d3e7c62b`。REA 分析的 `hellobike-payment-slice.apk` 只复制原始 DEX、清单和资源，SHA-256 为 `0a324bde7bfd7ec83955948edacc53f79287f80cd04f9c785de4cbacb91fc307`。原始条目摘要、4 项完整 REA 记录、原 DEX 注解与静态字段解码保存在[核验文件](hellobike-account-check-evidence.json)。没有执行 APK，也没有发送真实骑行或开关锁请求。

| 官方位置 | 确认内容 | Evidence |
| --- | --- | --- |
| `BikeRideCheckApiModel` 原 DEX 类注解 | `ActionValue("user.ride.bikeCheck")`，继承原生 `BaseApiModel` | 原始 DEX 注解解码，见保留的核验文件 |
| `BaseApiModel.<init>` 与解析字段清单 | 原生公共字段为 action、clientId、systemCode、ticket、token、version、__sysTag；没有 H5 或 bizType 字段 | `ev_ece3d5f913af31c76e8678e77ebdeffc2ad8d23e3c68b79efbf254928084a70c` |
| `RidingOrderRepository.a`（Kotlin 名 checkOrder） | 请求 `CommonNetService.a(new BikeRideCheckApiModel())`；`!isSuccess()` 返回 null；成功数据的 ride 或 order 为空也返回 null | `ev_e3ec35e6f9eea6d0ff3e22b48f5cd39681d0432a85325726e6b9bf341f83fd4c` |
| `BikeCheckRide`、`BikeRideCheck` | 返回路径为 `data.ride.order`，订单字段为 orderGuid、bikeNo、status；原 DEX 常量 GOING=0、BILLING=1、FINISH=2、ABNORMAL=3 | `ev_37b92a7fca2ee4a28afbb4fbffed6e7d7f56f86f2c6e7500a01e602e6858a207`、`ev_30b84f3c1e9d468bbbf6a38b029c6d46c9a145ff19212eafdab4732e6f29dc3a` 与原 DEX 静态字段解码 |

原 DEX 注解同时确认 `CommonNetService.a` 使用空路径 `POST` 和 `MustLogin`；既有官方网关初始化证据给出单车基址 `https://bike.hellobike.com/api`。仅对骑行发现采用官方非成功／空结果语义，不把业务码 301 单独定义成「无骑行」，也不通过提示文字猜测结果。

登录失效仍优先按已核验的 `103/-10003` 清除凭据。HTTP 拒绝、网络失败、无法解析的响应仍是实际查询失败。有订单对象却缺少订单标识或状态时，仍报告不完整信息，不虚构订单。无骑行不会直接结束本地原订单，原订单查询、预校验、开还车和支付的拒绝处理保持各自规则。

证据确认静态客户端处理，不代表已经观测用户设备的实际响应码，也不代表其他原型接口和整套线上业务均已验证。界面回归使用模拟返回「无骑行中订单」，核对自动恢复和手动检查均显示已登录；另覆盖登录失效、HTTP 失败、原订单和还车拒绝不误判成功。

直接相关定向测试覆盖无订单、登录失效、查询失败及原订单恢复，共 11 项通过，0 失败、0 错误。
