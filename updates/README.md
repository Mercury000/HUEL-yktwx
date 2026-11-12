# 静态更新清单上线配置

客户端和工作流只共享公钥；私钥只交给 GitHub Actions Secret。

本次已生成并将公钥写入 `updates/public-key.txt`。对应的私钥 PEM 仅保存在本机：

`C:/Users/mercu/.codex/visualizations/2026/11/10/01a1c570-0790-7052-b844-bcd721696e05/update-signing-key.pem`

请将该文件的完整内容配置为本仓库 Actions Secret **`UPDATE_SIGNING_KEY`**，保留安全备份；不要把私钥提交到仓库、上传到 Release 或写进客户端。发布脚本会确认私钥与编译时的公钥一致。不需要新增自建服务。

`updates/policy.json` 在 main 分支分别管理 stable/beta 强制边界。初始 0 表示不强制。修改时填写原因，且旧版本上限必须小于新 APK versionCode，已经发布的边界不允许下降。**只有你明确要强制旧版本更新时才修改这个值。** 客户端读取发布后签名清单，不直接读取策略文件。

main 版本发布 PR 合并后自动构建 stable 并发布正式 Release；beta 分支推送自动构建 beta 并发布 Pre-release。APK 内嵌渠道必须与 Release 类型相符；旧版本尚无该字段，不能直接用它发布新方案清单。用户在应用内选择的接收渠道独立持久保存。

首次上线：

1. 配置 `UPDATE_SIGNING_KEY`，提交接入代码、公钥、策略和候选池。
2. 按 [自动构建与发版](../docs/releases.md) 启用版本 PR。工作流自动分配高于旧版本的 versionCode，构建带新功能的签名 APK。
3. 自动构建创建 Release 后会显式调用清单任务。若人工发布或需要重试，手动运行 **Publish app update metadata**，填写已有 Release tag。
4. 任务完整验证五个 CDN、APK 身份和签名后才更新 latest。检查失败时旧 latest 不变，应调整候选池或排查发布配置后重试。
5. 在实际设备验收横幅、手动详情、下载及安装授权；强制策略仍保持 0，经过验收后再由你决定是否启用。

清单有效期为 90 天。每日北京时间 08:30 自动检查，剩余有效期不足或等于 14 天时验证原 APK/CDN 并续签。策略与候选池变更自动触发维护，临时失败自动重试。已确认的强制状态即使清单过期也不会解除，持续失败仍需处理 Actions 中的异常。

自动发版从现有 APK keystore 导出公有证书指纹给清单任务；签名清单记录 signerSha256，后续维护无需 APK 私钥。不一致的证书会拒绝发布。只有首次人工发布且无已有记录时才需要额外提供 APK_SIGNER_SHA256。

详细格式、客户端行为和迁移边界见 [应用更新](../docs/app-updates.md)。
