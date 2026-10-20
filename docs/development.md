# 开发说明

## 环境与配置

使用 JDK 22、Android SDK Platform 37 和仓库内的 Gradle Wrapper。SDK 路径配置在 `local.properties`，本地配置、密钥和签名材料不提交。

## 构建与预览

在根目录执行 `./gradlew :app:assembleDebug` 或 `:app:assembleRelease`，Windows 使用 `.\gradlew.bat`。APK 输出至 `app/build/outputs/apk/`；正式签名由 Actions 完成。

## 测试

按改动范围选择直接相关的测试，例如：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.mercury.yktwx.data.YktApiLoginTest"
```

界面测试使用 Robolectric；设备测试使用 Android instrumentation。

