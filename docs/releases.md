# 自动构建与发版

GitHub Actions 在 main 推送、Pull Request 和手动触发时构建 APK。普通提交生成 Debug 制品；版本变化时构建 Release，完成签名后创建对应版本的 GitHub Release。

## 版本与签名

版本名称和编号在 `app/build.gradle.kts` 中维护。正式签名通过 Actions Secrets 的 `SIGNING_KEY`、`ALIAS` 和 `KEYSTORE_PASSWORD` 配置；签名材料不进入源码。

## 本地构建

本地 Debug 与 Release 命令见[开发说明](development.md)。手动工作流可以选择构建类型，APK 在 Actions 制品中下载。

