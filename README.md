# HUEL-yktwx

河南财经政法大学校园一卡通个人付款码客户端，包含 **Kotlin / Jetpack Compose / miuix 安卓应用**及用于验证接口的 **Python 命令行客户端**。

> 非官方项目，仅供本人账户使用，不隶属于学校或一卡通服务提供方。使用官网内部接口，接口和登录规则可能变化。不要分享密码、Token 或完整付款码。

## 安卓应用

- **付款 / 关于**两个 Tab，默认进入付款页；显示二维码、CODE-128 条码，顶栏提供刷新入口。
- 使用系统输入法；密码显示 / 隐藏使用图标，登录结果通过 Toast 提示。
- 账密和 Token 使用 **Android Keystore + AES-GCM** 加密保存在本机，禁用应用数据备份。
- Token 过期或被拒绝时自动登录，单次刷新最多重登一次；账密无效或需要验证码时停止并要求重新登录。
- **关于 → 设置 → 主题**：主题模式、莫奈强调色、悬浮底栏及 iOS 样式、顶底栏模糊、预测性返回及跟手距离。
- 关于页含动态背景、开发者卡片、源码入口及 AboutLibraries 自动生成的引用项目列表。
- 前台显示按最多 60 秒刷新；后台清除付款码显示，回到前台重新获取。不承诺离线付款码可用性。

### 构建环境

| 项目 | 当前配置 |
| --- | --- |
| Android 最低版本 | Android 14 / API 34 |
| compileSdk / targetSdk | 37 / 37 |
| JDK toolchain | 22 |
| Gradle Wrapper | 9.7.1 |
| Android Gradle Plugin | 9.4.0 |
| Kotlin | 2.4.20 |
| miuix | 0.9.4-rc01 |

用 Android Studio 打开仓库，安装 Android SDK Platform 37，设置 SDK 路径并同步 Gradle。`local.properties` 由本机生成，**不要提交**。项目使用 Foojay toolchain resolver，首次构建可能需要联网下载 JDK 和依赖。

在仓库根目录执行：

```powershell
# 可安装的调试版
.\gradlew.bat :app:assembleDebug

# 独立包名的 UI 预览版
.\gradlew.bat :app:assemblePreview

# 压缩优化，仍使用本机 debug 签名的个人版
.\gradlew.bat :app:assemblePersonal
```

macOS / Linux 使用 `./gradlew`。

| 变体 | 包名 | 输出 |
| --- | --- | --- |
| debug | `com.mercury.yktwx` | `app/build/outputs/apk/debug/app-debug.apk` |
| preview | `com.mercury.yktwx.preview` | `app/build/outputs/apk/preview/app-preview.apk` |
| personal | `com.mercury.yktwx` | `app/build/outputs/apk/personal/app-personal.apk` |

本地 `release` 构建不配置签名；`personal` **不是正式发布签名构建**。不要把私钥、密码或签名配置提交到 Git。

### 自动发布

`.github/workflows/build.yml` 在推送 `main` 时读取 `app/build.gradle.kts` 的 `versionCode` / `versionName`：

- 版本未变化：构建 debug APK 并上传为 workflow artifact。
- 版本变化：构建 release APK，使用仓库 Actions Secrets（`SIGNING_KEY`、`ALIAS`、`KEYSTORE_PASSWORD`）签名，并创建 `v<versionName>` GitHub Release。
- 也可手动触发 workflow，选择 debug 或 release。

### 安装与 UI 预览

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

首次启动在手机上输入本人学号 / 工号及密码。常规模式启用 `FLAG_SECURE`，限制截图及任务预览。

可调试构建可用虚构码检查 UI；此模式不使用真实账户、不请求付款码：

```powershell
adb install -r .\app\build\outputs\apk\preview\app-preview.apk
adb shell am start -n com.mercury.yktwx.preview/com.mercury.yktwx.MainActivity --ez ui_preview true
```

预览模式关闭截图限制，**不可用于真实付款**。同签名、同包名可覆盖更新；独立 preview 包不与常规包共享账户数据。

## Python 客户端

Python 3.10+，仅使用标准库，无需第三方包。在仓库根目录运行：

```powershell
# 隐藏输入密码，自动登录、取码，默认仅输出脱敏结果
python .\payment_api.py --login

# 显式输出一个完整码，仅交给可信本地渲染器
python .\payment_api.py --login --reveal
```

Python 只返回码字符串，不生成图片，不自动付款或转账。账密不保存到文件，Token 仅在本次进程内使用；不接受密码命令行参数。兼容不带 `--login` 的隐藏 Token 输入，或由安全运行环境提供 `YKT_ACCESS_TOKEN`。

## 测试与验证

```powershell
# Python 测试
python -m unittest discover -s . -p "test_*.py" -v

# Kotlin 数据层、登录协议和请求构造回归测试
.\gradlew.bat :app:testDebugUnitTest --tests "com.mercury.yktwx.data.*"

# Android 静态检查
.\gradlew.bat :app:lintDebug

# 全部 JVM 测试，包括 Robolectric native graphics UI 冒烟测试
.\gradlew.bat :app:testDebugUnitTest
```

数据层测试使用虚构账密和响应，不向真实登录服务提交数据。完整 UI 测试、真实设备登录和实际扫码支付需另行验证；不要将单元测试通过当成实际消费已验证。

## 接口与安全边界

请求顺序和限制见 [接口说明](docs/api.md)。

- 仅向固定 HTTPS 官网发送请求，不禁用 TLS 校验，不跟随重定向发送账密，不使用浏览器 Cookie。
- 遇到验证码、首次登录初始化或密码更新要求时停止，不绕过验证。
- 安全键盘是网站请求编码协议；安卓输入仍使用系统键盘，编码后提交。
- 安卓加密保存账户与 Python 仅内存使用是不同模式，不混用持久化策略。
- 不把真实密码、Token、付款码放进代码、截图、日志、URL、Git 或公开服务。
- 不监听消费结果，不主动付款、充值或转账；多端取码与离线码行为尚未验证。
- Python / JVM 字符串不能保证安全擦除内存；加密存储也不等于设备失陷后绝对安全。

## 项目结构

```text
app/
  src/main/java/com/mercury/yktwx/
    data/                 # 接口、登录配置、密码编码、加密存储与账户仓库
    ui/                   # miuix 主题、Tab、页面和分层导航
    MainActivity.kt
    PaymentViewModel.kt
  src/main/res/           # 应用资源、双语文案、备份规则
  src/test/               # 数据层回归测试与 UI 冒烟测试
docs/api.md               # 内部接口协议说明
gradle/wrapper/           # Gradle 引导文件（含 wrapper JAR）
payment_api.py            # Python 命令行 / API 客户端
test_password_login.py
test_payment_api.py
```

`.gitignore` 排除机器 SDK/IDE 配置、缓存、构建产物、`dist/`、签名和账户文件。`tools/` 是本机调试日志、下载文档和截图目录，不提交；应用资源和 Gradle Wrapper 保留。

## 许可证

使用 **GNU GPL v3**，见 [LICENSE](LICENSE)。第三方来源声明见 [NOTICE](NOTICE)。分发 APK 时按许可证要求提供对应源码，保留相关版权和许可证声明。
