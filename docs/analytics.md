# 使用统计

## 构建配置

正式包使用友盟 U-App，版本由 Version Catalog 固定。debug / preview / performance 不初始化统计，本地 release 也关闭。业务共用 `AppAnalytics`，关闭时使用停用实现。

`UMENG_APP_KEY` 为 Actions Secret，`UMENG_CHANNEL` 为 Variable，本机属性分别为 `umeng.appKey`、`umeng.channel`。AppKey 为 24 位十六进制，渠道为 1–64 位字母、数字、下划线或连字符。分发渠道与 stable / beta 更新选择独立。

`UMENG_ENABLED` 只由 Actions 策略提供：自动发布 stable / beta 开启，手动 release 的 `umeng_enabled` 默认关闭，普通 push、PR 和调试关闭。启用而缺少有效 AppKey 或渠道时构建失败，本机配置不能开启统计。

## 授权

Application 在首个 Activity 前读取须知同意记录，有效时配置采集限制、预初始化、提交同意并初始化。首次未同意不预初始化，接受后再初始化。业务事件要求有效授权和实际前台，生命周期维护基础会话，不额外生成通用启动事件或绑定账号。

限制 IMEI、IMSI、ICCID、Wi-Fi MAC、应用列表和安装来源采集，关闭自动崩溃内容上报。SDK 仍可能使用 Android ID、OAID、GUID 等设备识别，须知披露接收方与范围。

撤销立即使操作凭据失效并停止新增事件。SDK 没有对称重新启用方法，同进程重新同意仍停用，下次冷启动按有效同意恢复；停用不能取消已排队或已入库的数据。

## 事件口径

定义集中在 `AppAnalytics`，分类使用枚举，不上传账号、手机号、订单、设备码、付款码、坐标、金额、密码、Token 或原始错误文案。

- 付款码成功请求计一次，多码、缓存重显与重复渲染不增加计数。
- 账单进入和切换计访问，搜索仅确认或明确清除计数，输入与防抖不上报。
- 充值缴费、设备启动、洗衣支付、哈啰开锁和付款实际开始提交时计数，恢复、轮询与回跳不重复计数。
- 取消、待确认和轮询中间错误不算明确失败，故障只传受控分类。
- 手动登录按明确结果计一次，后续设备恢复失败不改变登录结果。
- 下载结果要求实际传输与完整性校验，缓存 APK 不计下载；安装只表示交给系统安装器，不宣称安装完成。

连续故障和同次结果去重，撤销前开始的异步回调不能继续上报。启用统计的 release 通过 `um.<AppKey>` 入口由 SDK 处理扫码集成测试，关闭的包不能临时启用。

参考：[Android 集成测试](https://developer.umeng.com/docs/119267/detail/118639)、[合规指引](https://developer.umeng.com/docs/147377/detail/210108)。
