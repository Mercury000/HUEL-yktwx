# 更新清单配置

客户端和工作流共享 `public-key.txt` 中的 Ed25519 公钥，私钥只通过 Actions Secret `UPDATE_SIGNING_KEY` 提供。发布时确认公私钥匹配，不将私钥放入仓库、Release 或客户端。

## 配置

`policy.json` 管理 stable / beta 强制边界，`sources.json` 管理候选来源，渠道目录包含签名 latest 与不可变 manifest。`maxForcedVersionCode=0` 不强制，上限必须小于新 APK versionCode，已发布边界不得下降。变更填写原因，由发布任务签入清单，客户端不直接读取策略文件。

## 发布与维护

1. 配置更新私钥、APK 签名和仓库权限，按[构建与发版](../docs/releases.md)维护版本 PR。
2. 发布签名 APK，确认包名、版本、渠道、签名、SHA-256 和至少五个独立 CDN 的完整内容。
3. 先提交不可变清单并核对摘要，再切换签名 latest。前置失败保持旧指针。
4. 补发运行 `Publish app update metadata` 并填写原 tag，重试复用原 APK。

清单有效期 90 天，每日北京时间 08:30 检查，剩余不超过 14 天时校验并续签，策略和候选池变化也触发维护。自动发布从 keystore 导出公有证书指纹，清单保存 `signerSha256`，维护无需 APK 私钥。首次人工发布且无既有记录时提供 `APK_SIGNER_SHA256`，证书不一致拒绝发布。

客户端下载和安装校验见[应用更新](../docs/app-updates.md)，实际安装仍需 Android 未知来源授权与系统确认。
