# 网络层

应用自己发起的 HTTP 请求统一使用 OkHttp 5.3.0，覆盖一卡通登录、付款码、账单、校园卡操作、充值与缴费、胖乖生活、慧生活798、头像、更新元数据和 APK 下载。

`AppHttp` 在进程内持有客户端，派生客户端共用请求调度器。公开只读请求和更新下载共用可复用连接池；校园卡和生活业务请求不保留空闲连接，结束响应后立即释放 TCP/TLS 连接。服务端支持时，公开读取仍可使用 HTTP/2 多路复用。OkHttp 自动协商 gzip，JSON 和图片的大小限制应用于解压后的字节。查询参数使用 `HttpUrl.Builder`，表单使用 `FormBody`，避免手工拼接或重复编码。

## 超时与重试

| 用途 | 连接 / 读取 / 写入超时 | 整次请求时限 | 连接故障重试 |
| --- | --- | --- | --- |
| 校园卡和生活服务 | 各 15 秒 | 30 秒 | 禁用 |
| 公开头像 | 各 15 秒 | 30 秒 | 允许 |
| 更新清单 | 连接、读取 10 秒；写入 15 秒 | 单个元数据读取 1.5 秒；整个检查 3 秒 | 允许 |
| APK 下载 | 连接、读取 10 秒；写入 15 秒 | 无总时限，持续有数据即可继续 | 允许 |

业务接口中的 GET 也可能发放付款码或启停设备，因此业务客户端统一禁止连接故障重试。服务端关闭空闲连接后，复用旧 socket 会在请求写入时产生 `SocketException` 或 `unexpected end of stream`；同一连接池可能保留了多条此类连接，所以页面层重试仍可能连续失败。业务客户端通过零空闲连接池避免跨空闲期复用，同时不允许网络层自动重放可能已到达服务端的操作。该配置会增加顺序业务请求的 TCP/TLS 建连成本，属于对当前服务端和不可重放接口的明确取舍；仍在使用的 HTTP/2 连接可以承载并发请求，零空闲连接不等于每个并发请求都建立独立连接。

登录恢复、查单轮询及 CDN 切换仍由原有业务流程控制，不由网络层重放操作。传输异常作为 `NetworkFailure.cause` 保留，避免诊断时丢失底层 `SocketException`、TLS 或超时类型。

业务客户端同时阻止 OkHttp 对 `503 + Retry-After: 0` 和 HTTP/2 `421` 响应的自动重发：503 移除重试提示并保留原状态与正文，421 关闭响应并作为连接故障返回。公开读取保留 OkHttp 的故障恢复能力；对于 408/503 中超出 Int 范围的数字 `Retry-After`，将等待值限制为 Int 最大值，避免 OkHttp 5.3.0 的整数解析溢出，同时保持不立即重发的语义。

## 请求生命周期

`Call.Factory.readResponse` 使用 `enqueue`，响应读取和 JSON 解码在 OkHttp 的工作线程执行。OkHttp 异步调用自身的 `callTimeout` 从调度器开始执行时计时，因此外层使用同一时限覆盖排队、执行和响应消费；超时取消该 Call 并传播 `InterruptedIOException`，不把正常超时误作页面取消。协程取消直接调用 `Call.cancel()`，覆盖等待调度、等待响应和响应体读取；父协程取消继续以取消异常传播，不转成网络故障。时限为零的更新下载不添加总时限，元数据仍由单次读取和整体检查时限控制。响应在消费完成或发生异常时关闭，释放连接。调用方无需创建额外线程或取消监视协程。

JSON 响应最多读取 1 MiB，头像和图形验证码最多读取 2 MiB。APK 直接写临时文件，不整体加载到内存；大小、SHA-256、包名、版本和签名检查继续使用原有安装流程。

## 连接状态与页面恢复

默认网络由一个 `registerDefaultNetworkCallback` 监听器维护。`onAvailable` 只记录网络身份；`onCapabilitiesChanged` 使用传入的能力；`onBlockedStatusChanged` 使用传入的应用阻断状态。回调中不再调用 `activeNetwork` 或 `getNetworkCapabilities`，前台刷新也不能用同步快照覆盖已经收到的回调。同步查询仅用于监听建立前的初始快照。

这是 Android 官方 [读取网络状态](https://developer.android.google.cn/develop/connectivity/network-ops/reading-network-state) 和 [NetworkCallback](https://developer.android.google.cn/reference/android/net/ConnectivityManager.NetworkCallback) 的明确约束：同步查询与异步通知存在竞态，不能保证返回当前状态。[getActiveNetwork](https://developer.android.google.cn/reference/android/net/ConnectivityManager#getActiveNetwork()) 返回 `null` 还可能表示默认网络暂时被阻止供当前应用使用，并不只表示设备断网。

失去当前默认网络延迟 350 毫秒确认，避免 Wi-Fi／蜂窝切换短暂出现离线横幅；新网络及解除阻断及时发布。旧默认网络和已经注销的监听器的迟到通知不能覆盖当前连接。校园网不强制要求 `NET_CAPABILITY_VALIDATED`，能力未知时允许真实请求；实际请求成功与否仍由接口结果决定。

付款、大厅、账单、“我的”、个人信息和账户详情统一使用这份状态。网络恢复、真实页面重新激活、应用切回前台都能恢复有限的读取机会；同一页面重复重组不重新发放预算。前后台状态与网络重连分别记录，后台立即撤销展示读取许可，不等待下一帧。详细规则见 [展示读取调度](read-scheduling.md)。

错误类别贯穿传输层、接口包装、调度和展示。付款码接口在保留已取得账户信息时也保留 `Network` 类别和完整异常链，避免把连接中断改写为校园卡业务拒绝而失去恢复机会，或在第二次包装时丢失原始异常。认证与业务拒绝不进入普通网络重试；交易写操作不由展示读取调度重放。

## 请求隔离

共享客户端没有 Cookie 仓库、磁盘 HTTP 缓存和认证拦截器。学校 Token、生活服务 Token、签名和 OAuth 客户端标识只添加到对应请求。公开头像和更新请求不携带业务凭据，不记录请求或响应正文。

业务请求不自动跟随重定向。更新下载最多手动跟随五次，每一步重新验证 HTTPS、无用户信息和标准端口；跨主机跳转重新构造公开请求。微信支付桥接仍先校验支付地址与预支付标识，再使用独立请求头，不发送学校 Token。

## 验证

协议回归通过可注入的 `Call.Factory` 和 OkHttp 拦截器返回离线样本，继续验证请求参数、认证、签名和业务结果。`AppHttpTest` 使用本地 HTTPS 服务验证业务请求不复用空闲连接，以及公开读取的连接复用、HTTP/2、gzip、取消、重试和 Cookie / 重定向行为。测试证书仅用于本地测试，生产使用系统 TLS 校验。

迁移静态审查另覆盖排队超时后不发出请求、408/503 超大重试提示、公开读取的正常故障恢复、无总时限下载，以及付款码异常链保留。相关行为核对的是当前依赖版本的官方源码：[异步调用计时与执行](https://github.com/square/okhttp/blob/parent-5.3.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/RealCall.kt)、[响应重发与重试延时解析](https://github.com/square/okhttp/blob/parent-5.3.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt)、[零空闲连接的同步移除](https://github.com/square/okhttp/blob/parent-5.3.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/RealConnectionPool.kt)。
