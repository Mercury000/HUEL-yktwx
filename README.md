# 财小通 · CaiXiaoTong

河南财经政法大学校园一卡通个人付款码客户端，包含 **Kotlin / Jetpack Compose / miuix 安卓应用**及用于验证接口的 **Python 命令行客户端**。

> 非官方项目，仅供本人账户使用，不隶属于学校或一卡通服务提供方。使用官网内部接口，接口和登录规则可能变化。不要分享密码、Token 或完整付款码。

## 安卓应用

- 未登录时进入独立登录页，不显示付款码或主界面 Tab；本机已有登录记录时直接进入主界面，失效时按原有规则自动重登。
- 登录后显示 **付款 / 大厅 / 账单 / 我的**四个 Tab，默认进入付款页；显示二维码和 CODE-128 条码。**我的 → 应用设置 → 退出登录**需确认，随后返回独立登录页并清理主界面状态。
- 大厅展示已绑定宿舍、生活缴费和代人充值，下拉刷新宿舍电量。宿舍通过官方校区、楼栋、楼层、房间选项核验照明与空调电表，显示剩余电量；两种缴费入口打开对应的真实房间选项，查看或查询不修改官方绑定。
- 我的展示后端返回的姓名、完整学工号、书院、专业、头像、校园卡余额和状态、本月概览，以及独立的设置与关于入口。首页书院／专业提取官方首页信息接口中 `thirdLevelDepartment`／`fourthLevelDepartment` 的已解析文本，不渲染卡图。个人信息详情按官方页面展示头像、学工号、姓名、手机号和非空部门。账户详情里的充值直接打开金额弹层。
- **我的 → 校园卡管理**：挂失 / 解挂合并一个入口，根据实时卡状态切换；读取期间禁用重复进入，读完只弹一次确认框；单卡直接确认，多卡手动选择；挂失按官网配置决定是否需要密码（当前默认不需要），解挂输入校园卡查询密码。查询密码修改成功后清除本机登录信息并返回登录页。
- 账单页内切换明细与统计，共用日期范围；保留原有收支卡片，交易类型、金额和搜索只作用于明细。日期选择支持日、月、年、自定义范围，点击与按压范围限定在日期文字和箭头处。
- **代人充值**支持学工号 / 证件号与用户输入图形验证码，验证码图片在输入框左侧，点击图片更换；查询后进入独立确认页，姓名 / 学工号 / 账户按后端值完整显示，“上一步”保留查询对象并清空、更换验证码。收款账户未选中灰底、选中主题色底白字；确认后复用金额选择、微信唤起及查单流程。尚未专门测试代充值实际扣款，不要求为验证另付一笔。
- 底部弹窗内容预留系统导航栏 / 手势条安全区，并与键盘 Insets 取较大值，避免按钮被“小白条”遮挡或重复叠加空白。
- 使用系统输入法；密码显示 / 隐藏使用图标，登录失败在登录页内联提示，登录成功使用 Toast。
- 账密和 Token 使用 **Android Keystore + AES-GCM** 加密保存在本机，禁用应用数据备份。
- Token 过期或被拒绝时自动登录，单次刷新最多重登一次；账密无效或需要验证码时停止并要求重新登录。
- **我的 → 应用设置 → 主题**：主题模式、莫奈强调色、悬浮底栏及 iOS 样式、顶底栏模糊、预测性返回及跟手距离。miuix 原生悬浮底栏保留图标形式。
- 关于页含动态背景、开发者卡片、源码入口及 AboutLibraries 自动生成的引用项目列表。
- **我的 → 应用设置 → 转回检测支付**开关默认关闭，选择启用后，付款页识别手机“转向扫码器再转回来”后，独立静默查询账户余额（最多 3 次，覆盖短暂延迟）；同一账户余额减少时以差额复用“支付成功”底部抽屉，并立即刷新付款码和余额。余额不变、增加或查询失败不提示，不查账单；无可用旋转传感器时不启用，关闭开关、切页或后台停止监听和核验。此提示依据余额差额，不是官方交易回执，动作阈值与余额延迟仍需真实扫码验证。
- 前台显示按最多 60 秒刷新；后台清除付款码显示，回到前台重新获取。不承诺离线付款码可用性。
- 展示付款码前核验当前账户对应卡片状态；挂失时以“付款已暂停”替代码区，保留余额，点击“解挂”直接进入原有解挂流程，手动刷新仅支持下拉刷新。卡状态无法确认时不显示码，解挂完成后重新核验并取得新码。

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

`release` / `personal` 保留 R8 代码优化和资源裁剪，并压缩 APK 内的 DEX，以减小下载文件；`debug` / `preview` 不压缩 DEX，便于快速打包调试。DEX 压缩不改变代码内容，APK 变小不代表安装后的磁盘占用同比减少。

本地默认开启 Gradle 构建缓存、配置缓存和项目并行执行，最多使用 4 个 Gradle worker 控制并发内存峰值（可用 `--max-workers` 覆盖）。重复执行相同构建命令可复用配置；任务输出也可在输入一致时从构建缓存恢复。日常构建无需先执行 `clean`。首次构建仍需要生成缓存；排查配置缓存兼容问题时可临时追加 `--no-configuration-cache`。

### 自动发布

`.github/workflows/build.yml` 在推送 `main` 时读取 `app/build.gradle.kts` 的 `versionCode` / `versionName`，并检查 `versionCode` 对应的 tag 是否存在：

- 已存在：构建 debug APK 并上传为 workflow artifact。
- 不存在：构建 release APK，使用仓库 Actions Secrets（`SIGNING_KEY`、`ALIAS`、`KEYSTORE_PASSWORD`）签名，并创建 tag 为 `versionCode`、名称为 `versionName` 的 GitHub Release。
- 也可手动触发 workflow，选择 debug 或 release；手动 release 只构建签名 APK，不创建 Release。

Actions 通过 `setup-gradle` 复用依赖和 Gradle 构建缓存，PR 只读取缓存；手动构建及 PR 使用浅克隆，自动发布保留完整历史及 tag。临时 runner 每次仅调用一次 Gradle，禁用配置缓存以避免生成无法跨运行复用的配置；本地配置缓存不受影响。debug artifact 使用较低压缩等级以减少上传前的压缩耗时，release 直接上传已打包的 APK。

### 安装与 UI 预览

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

首次启动在手机上输入本人学号 / 工号及密码。常规模式启用 `FLAG_SECURE`，限制截图及任务预览。

`debug` / `preview` 构建可用虚构码检查 UI；此模式不使用真实账户、不请求付款码：

```powershell
adb install -r .\app\build\outputs\apk\preview\app-preview.apk
adb shell am start -n com.mercury.yktwx.preview/com.mercury.yktwx.MainActivity --ez ui_preview true
```

预览模式关闭截图限制，**不可用于真实付款**。同签名、同包名可覆盖更新；独立 preview 包不与常规包共享账户数据。

预览假数据、提示文案和启动参数仅位于 `app/src/uiPreview/kotlin/`，由 `debug` / `preview` 的 Kotlin source set 编译。`release` / `personal` 使用 `app/src/production/kotlin/` 的禁用实现；共用代码中的预览分支由 `UiPreview.enabled` 编译期常量关闭，正式包不包含预览数据和文案，传入 `ui_preview` / `ui_preview_lost` 也不会开启预览。

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

# 正式构建的预览隔离回归（虚构账户，不请求网络）
.\gradlew.bat :app:testReleaseUnitTest --tests "com.mercury.yktwx.UiPreviewIsolationTest"
```

测试优先覆盖金额精度与限额、鉴权、订单响应匹配和支付状态流程。充值流程测试验证重复点击不重复下单、微信回跳不等于支付成功、查单异常不冒报结果，以及取消或切换账户后旧响应不能更新新状态；不依赖弹窗文案和布局。

UI 自动化仅保留少量入口与交互回归，例如账单 Tab 切换后页面实际可见；不要随界面实现逐项复制文案、金额按钮和结果提示断言。已有房间管理、关于页图形等专项回归用于守住明确的问题，不作为完整 UI 验收。

界面改动仍需实际运行检查入口、弹窗、键盘输入与页面切换。数据层和状态流程测试使用虚构账密和响应，不向真实登录服务提交数据。真实设备登录、充值和实际扫码支付需在明确授权后另行验证；不要将单元测试通过当成实际消费已验证。

`DashboardPreviewTest` 使用 Robolectric native graphics 直接渲染 Compose 页面，将虚构数据截图写入本机 `tools/dashboard-preview/`。截图选择已有 iOS 样式悬浮底栏，覆盖明暗主题、日期与筛选、个人资料和精确电表入口；这类截图不是设计稿，也不代表实机支付已验证。

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
  src/uiPreview/kotlin/   # 仅 debug / preview 编译的预览数据与文案
  src/production/kotlin/  # release / personal 的预览禁用实现
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
