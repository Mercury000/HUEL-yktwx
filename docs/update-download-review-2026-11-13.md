# 更新下载与弹窗复核（2026-11-13）

本次复核覆盖签名清单进入下载计划、来源选择、失败重试、进度、缓存复用、安装授权返回，以及弹窗的返回手势和内联错误布局。本次提交包含以下实现和已完成的本地定向验证，尚未发版。没有连接到用户手机，不能据此确定手机上那次失败的具体原因。

## 确认的问题与修复

| 确认的行为 | 修复后的行为 | 实现证据 |
| --- | --- | --- |
| APK 按清单的固定顺序串行尝试，没有根据当前设备网络选源 | 下载开始时并发读取各来源最多 16KiB 前缀，限时 3 秒，优先使用最先返回合法文件数据的来源，取消其余探测；超时明确失败 | [AppUpdateInstaller.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/data/AppUpdateInstaller.kt:120) |
| 下载检查与 APK 来源不同，最快的清单来源不能代表最快的 APK 来源 | 分别处理两组地址；APK 选择核对 HTTPS、成功状态、声明大小和 ZIP 文件头，完整传输后继续校验 SHA-256 与身份 | 同上 |
| 签名、包名、版本等不兼容错误也被捕获后继续尝试其他来源 | 匹配预期 SHA-256 的文件若身份不兼容，直接终止；网络、大小、哈希失败仍可换来源 | [AppUpdateInstaller.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/data/AppUpdateInstaller.kt:98) |
| 发布清单已有证书指纹，客户端解析后没有保留它 | 保留并校验 `signerSha256`，下载前先与当前安装证书比较，安装前再核对实际 APK；旧清单无此字段仍执行实际签名连续性检查 | [AppUpdate.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/data/AppUpdate.kt:119)、[AppUpdateInstaller.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/data/AppUpdateInstaller.kt:106) |
| 缺少 Content-Length 时反复显示不确定进度；换来源没有说明原因 | 使用清单中的预期文件大小计算百分比，传输最高 99%；换来源明确显示“重试下载”；全失败保留最后的具体错误 | [AppUpdateInstaller.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/data/AppUpdateInstaller.kt:81) |
| 尚无本地文件时，开始下载先显示“正在校验” | 无缓存先显示“准备下载”；有缓存才进入本地校验。选源属于忙碌状态，禁止重复点击和关闭 | [AppUpdateViewModel.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/AppUpdateViewModel.kt:358) |
| 只在关闭回调中拒绝返回，Miuix 仍先对手势播放平移或缩放动画 | 在弹窗内部消费受限返回事件，选源、下载、校验及强制更新时不触发弹窗的返回动画；限制解除后恢复普通关闭 | [AppUpdateComponents.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/ui/components/AppUpdateComponents.kt:90) |
| 无错误时仍预留至少 36dp 的错误区和上方布局间距 | 错误区自身控制展开、收起与间距，没有错误时不占高度；保留原内联动画 | [AppUpdateComponents.kt](/D:/mercu/Desktop/yktwx/app/src/main/java/com/mercury/yktwx/ui/components/AppUpdateComponents.kt:120) |

前缀探测只用于选择来源，不能证明整个 APK 正确，也不能保证后续传输速度。所选来源后续发生网络或哈希失败时仍尝试其他来源。

## 定向验证

24 个不同用例通过，0 失败、0 跳过。没有追加全量测试、Lint 或 APK 构建。

| 用例组 | 数量 | 覆盖 |
| --- | ---: | --- |
| AppUpdateInstallerTest | 9 | 错误哈希换源、身份不兼容停止重下、缓存身份错误、未知响应长度的确定进度、失败原因与次数、取消清理、授权缓存复用、安装前重新核对当前清单证书 |
| AppUpdateSourceSelectionTest | 5 | 快速 APK 胜过首个阻塞来源，拒绝 HTTP 200 的 HTML 与错误大小，选源超时关闭连接，父任务取消，无缓存下载前兼容性检查，缓存绕过探测 |
| UpdateDownloadUiRegressionTest | 3 | 多阶段返回手势进行、取消与完成时弹窗边界不变；恢复普通关闭；错误区无预留高度及退出动画；换源提示 |
| AppUpdateApiTest 的直接相关用例 | 2 | 签名清单证书字段保留与格式拒绝；现有 schema、渠道、数字、哈希与来源校验 |
| AppUpdateViewModelTest 的直接相关用例 | 1 | 同一 APK 下载中手动复查保持弹窗和计划；撤销联网许可可取消并恢复重试 |
| UpdateInstallRecoveryTest | 4 | 授权返回等待前台、拒绝授权、文件改变、安装界面启动失败、过期清单不能安装 |

结果保存在 [verified-results](/D:/mercu/Desktop/yktwx/tools/update-download-diagnostic/verified-results)。传输/安装夹具明确跳过选源；选源用例使用真实默认选择逻辑、真实 OkHttp 调度和可控制的响应体，测试超时与取消不依赖实际公网波动。

## 当前 Release 的公网验证

检查了 main 上的 beta 指针及签名清单，对应 `v1.5.0-beta.1`。在此电脑上，`gh-proxy.org`、`ghfast.top` 完整返回与清单一致的 6,158,382 字节。单独通过 ghfast 重取 APK 用时约 11.8 秒，随后使用 Android Build Tools 核对：

- 包名：`com.mercury.yktwx`。
- 版本：`1.5.0-beta.1` / `100150`，minSdk 34。
- SHA-256：`0c0745b881e70e7052236529c8a44cc7a26fdf37fbf768a5fa038e33dec53b36`，与清单相同。
- APK 签名验证通过，证书 SHA-256 与清单中的 `signerSha256` 相同。

部分其他入口出现连接或读取超时；另一些未在诊断的传输时间预算内完成。并发下载会共享本机带宽，因此此次结果不能作为用户手机上的速度排名，也不能把超出诊断预算的入口判定为永久不可用。原始结果：[results.json](/D:/mercu/Desktop/yktwx/tools/update-download-diagnostic/results.json)、[signature-downloads.json](/D:/mercu/Desktop/yktwx/tools/update-download-diagnostic/signature-downloads.json)。

这次没有实际安装、上传 APK、推送代码或发布 Release。手机仍运行旧版客户端时，不会获得本地修复；手机上那次下载失败的最终原因仍需要设备上的实际错误或日志才能确定。
