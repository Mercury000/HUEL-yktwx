# 性能基准与应用 Profiles

性能模块有两个职责：测量固定操作的发布配置性能，以及生成可随 APK 分发的编译资料。`benchmark` 是独立 instrumentation 模块，不进入正式安装包；应用包含 ProfileInstaller 1.4.1 和生成的 Baseline/Startup Profile。版本与插件统一在 Version Catalog 中维护，Benchmark/Profile 插件为 1.5.0。

## 构建与数据范围

| 构建 | 用途 | 配置 |
| --- | --- | --- |
| `nonMinifiedPerformance` | 采集常用路径 | 不可调试、未混淆、可分析、测试签名、独立 `.performance` 包 |
| `benchmarkPerformance` | 测量同一组操作 | 不可调试、R8 与资源压缩、可分析、测试签名、独立 `.performance` 包 |
| `release` | 正式分发 | 保留正式 Application、统计绑定、包名和签名流程，读取已生成的应用 Profile |

专用 performance 构建复用现有预览数据，在 MainActivity 中通过 `ui_preview` 进入受控账户状态，随后运行现有 ViewModel 与生产页面。校园账户、账单和房间使用样本；真实登录、支付、设备控制、业务网络及友盟初始化均不在这个基准中。该构建没有 release 的友盟依赖或生产预览入口实现；正式 release 继续拒绝预览参数。这里的耗时反映共同启动、界面绘制和交互路径，不能解释成线上登录、接口响应或设备识别总耗时。

UI Automator 使用现有页面语义、账单列表与行标识，不新增产品内的性能入口。主标签、详情和滚动共用一份操作流程，生成器与测量代码调用同一流程。

## 操作与编译模式

- 冷启动：进入可交互的付款主界面。只有这个流程设置 `includeInStartupProfile=true`。
- 常用交互：大厅、我的、账单切换，打开并返回账单详情，账单上下滚动。
- 测量：冷启动 10 次，交互 5 次；分别使用 `CompilationMode.None` 与要求成功安装 Baseline Profile 的 `CompilationMode.Partial(BaselineProfileMode.Require)`。

生成的资料合入 `app/src/main/generated/baselineProfiles/`，排除 `preview` 包中的样本生成代码。Startup Profile 用于 R8 的启动 DEX 布局优化；Baseline Profile 用于 ART 编译常用方法。生成后的文本可以提交，构建输出和设备诊断不提交。不在普通构建中自动启动设备或重新生成 Profile。

## 本地操作

在 Windows PowerShell 的项目根目录使用：

```powershell
# API 34+ 的连接设备可生成；模拟器可以验证生成流程。
.\gradlew.bat :app:generateBaselineProfile

# 检查与测量使用 R8 的发布配置；此任务需要真实设备。
.\gradlew.bat :benchmark:connectedBenchmarkPerformanceAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark'

# 检查最终 release 制品中的 Profile。
.\gradlew.bat :app:assembleRelease
python scripts/verify_profiles.py app/build/outputs/apk/release/app-release-unsigned.apk `
  --mapping app/build/outputs/mapping/release/mapping.txt
```

普通构建可以使用已提交的资料。页面、模型或依赖图发生明显变化后，重新运行相同生成流程并验证打包。应用资料只来自运行记录，不以手写全包通配规则代替采集。

## 手动 CI

在 GitHub Actions 选择 `Generate application profiles`，手动运行需要更新的分支。采集工作流只接受 `workflow_dispatch`，不会因提交、PR 或普通打包自动运行。先在 API 36 模拟器采集，再检查优化性能 APK 和正式 APK；生成结果与诊断文件上传为制品。

在 main 或 beta 上手动运行成功后，分别使用 `perf/baseline-profiles-main` 或 `perf/baseline-profiles-beta` 提出 Profile 更新 PR，只包含两份生成文件，不自动合入。自动提交只有一行 `perf(profile): 更新应用启动与常用操作编译资料`，无提交正文。其他分支仅提供制品。PR 描述说明采集与验证范围，不属于 Git 提交正文。

创建 PR 优先复用仓库现有的 `RELEASE_PLEASE_TOKEN`，未配置时使用 `GITHUB_TOKEN`；后者需要仓库允许 Actions 创建 PR，且它创建的 PR 不会自动触发其他工作流。此时可手动运行现有构建工作流检查。此流程不要求提供真实业务凭据，不发布采集时构建的 APK。

正式打包读取已保存的 Profile，并用对应 R8 mapping 检查 APK 内方法所属类、DEX 条目的 ZIP CRC、方法表及 Profile 格式。已发布 APK 的重试复用继续跳过构建与检查，不替换原制品。校验通过证明资料打包和匹配，不证明设备已采用或产生速度收益。

## 结果记录与设备验收

2026-11-13：性能模块的两种测试构建及两种应用 Hilt 图已编译通过。生成、APK 打包与后续真机结果在 [迁移进度](modernization-progress.md) 记录。

真机验收须保存设备型号、API、ABI、刷新率、电源与温度状态、应用提交、APK 摘要、Benchmark 版本和原始 JSON/Perfetto 输出。在同一设备、同一受控样本和同一 APK 上比较两种 ART 编译模式；需要比较 Startup DEX 布局时另构建关闭布局优化的同源 APK，记录差异。报告至少保留冷启动中位数/尾部值，以及交互的帧耗时与超时分布；查验界面行为一致，数据不足或退步时继续分析，不能预设加速比例。

按当前要求暂停 ADB 及设备操作，性能收益尚未验收。模拟器上的生成成功、UI 操作或任何计时都不作为真机收益证据。相机首帧与识别耗时采用单独的真实相机检查，不能用这里的界面帧指标替代。

本项目侧载分发 APK，Baseline Profile 的实际编译时间取决于 ProfileInstaller、ART 与系统 dexopt；首次侧载不承诺固定加速。Profile 的生成、APK 包含资料、设备成功使用资料与性能收益分别记录。

## 官方依据

- [生成 Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile)
- [Baseline Profile Gradle 插件](https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles)
- [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
