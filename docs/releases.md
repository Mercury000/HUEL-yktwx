# 自动构建与发版

main 与 beta 使用同一构建工作流，日常提交生成 Debug 制品。Release Please 为对应分支提出版本与日志 PR，版本 PR 合并后构建、签名并发布对应渠道的安装包。

## 版本与签名

版本名称由 `version.txt` 和 Release Please manifest 管理。`ci-version.properties` 配置本地默认编号与 CI 偏移；CI 使用 `APP_BUILD_VERSION_FILE` 读取当前构建生成的版本文件。

正式签名使用 `SIGNING_KEY`、`ALIAS` 和 `KEYSTORE_PASSWORD`，缺少签名配置时停止正式发布。

## 更新清单

APK 发布后由 `publish-updates.yml` 校验 APK 与 CDN 内容，再签署对应渠道的不可变清单并切换 latest 指针。失败保留原指针，重试复用同一已发布 APK。配置见[应用更新](app-updates.md)及[updates/README.md](../updates/README.md)。

接口与本地构建配置见[开发说明](development.md)。
