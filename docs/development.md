# 开发说明

## 环境与配置

使用 JDK 22、Android SDK Platform 37 和仓库内的 Gradle Wrapper。SDK 路径配置在 `local.properties`，本地配置、密钥和签名材料不提交。

| 环境变量 | 本地属性 | 用途 |
| --- | --- | --- |
| `PANGGUI_API_SECRET` | `panggui.apiSecret` | 胖乖接口密钥 |

## 构建与预览

在根目录执行 `./gradlew :app:assembleDebug` 或 `:app:assembleRelease`，Windows 使用 `.\gradlew.bat`。APK 输出至 `app/build/outputs/apk/`；正式签名由 Actions 完成。

`assemblePreview` 使用独立 `.preview` 包名。调试和预览从 `app/src/uiPreview/` 读取样本，release 使用 `app/src/production/` 禁用预览实现。

## 测试

按改动范围选择直接相关的测试，例如：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.mercury.yktwx.data.YktApiLoginTest"
```

界面测试使用 Robolectric；设备测试使用 Android instrumentation。

