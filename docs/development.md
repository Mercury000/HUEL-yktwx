# 开发说明

## 环境与配置

使用 JDK 22、Android SDK Platform 37 和仓库自带的 Gradle Wrapper。`local.properties` 只用于本机配置，不提交到 Git。环境变量优先于本机属性：

| 环境变量 | 本机属性 | 用途 |
| --- | --- | --- |
| `PANGGUI_API_SECRET` | `panggui.apiSecret` | 胖乖接口配置 |
| `HUI798_API_GATEWAY` | `hui798.apiGateway` | 798 网关，默认 `https://i.ilife798.com/api/v1` |
| `HUI798_SIGN_SALT` | `hui798.signSalt` | 798 登录签名配置 |
| `UMENG_APP_KEY` | `umeng.appKey` | 正式统计应用 |
| `UMENG_CHANNEL` | `umeng.channel` | 安装分发渠道 |

缺少生活服务配置的 debug 包仍可编译，对应平台无法登录。Actions 通过同名 Secrets 注入接口配置，release 缺少 798 签名配置时失败，PR 不注入接口 Secrets。统计开关规则见[使用统计](analytics.md)。

依赖版本由 `gradle/libs.versions.toml` 维护，GitHub Packages 下载所需的 `MIUIX_PACKAGES_TOKEN` 按构建环境配置。密钥、签名和真实支付数据不提交。

## 构建与预览

在仓库根目录执行 `./gradlew :app:assembleDebug`、`:app:assemblePreview` 或 `:app:assembleRelease`，Windows 使用 `.\gradlew.bat`。APK 位于 `app/build/outputs/apk/<变体>/`，本地 release 未签名。

debug 与 release 使用正式包名，preview 使用 `.preview` 后缀。覆盖安装需要相同包名与签名。版本读取 `version.txt` 和 `ci-version.properties`，CI 只有显式设置 `APP_BUILD_VERSION_FILE` 才读取生成文件。

`debug` / `preview` 使用 `app/src/uiPreview/kotlin/` 的虚构数据，`release` 使用 `app/src/production/kotlin/` 的禁用实现。正式包不能通过预览参数开启虚构账户。

```powershell
adb install -r app/build/outputs/apk/preview/app-preview.apk
adb shell am start -n com.mercury.yktwx.preview/com.mercury.yktwx.MainActivity --ez ui_preview true
```

常规模式使用 `FLAG_SECURE` 限制截图与任务预览，预览模式允许截图。预览不读取真实账户、不请求业务服务，不能用于付款。

## 测试

按改动范围选择直接相关的测试，例如：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.mercury.yktwx.data.YktApiLoginTest"
.\gradlew.bat :app:testReleaseUnitTest --tests "com.mercury.yktwx.UiPreviewIsolationTest"
python -m unittest discover -s .github/scripts -p "test_verify_profiles.py"
```

数据与界面测试使用独立样本和模拟响应。发布脚本的 Python 测试依赖 `cryptography`，Release Please 测试依赖与工作流版本匹配的 `release-please` 模块，可通过 `RELEASE_PLEASE_MODULE` 指定位置。

本地依赖的来源、摘要及许可证见 [app/libs/README.md](../app/libs/README.md)，版权与许可声明随对应源码保留。
