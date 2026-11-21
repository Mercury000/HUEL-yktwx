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
- 扫码采集：从付款页打开独立 `ScannerActivity`，等待 CameraX 预览进入 STREAMING，再保留一秒识别采样并返回。扫码只进入 Baseline Profile，不加入冷启动的 Startup Profile。
- 测量：冷启动 10 次，交互 5 次；分别使用 `CompilationMode.None` 与要求成功安装 Baseline Profile 的 `CompilationMode.Partial(BaselineProfileMode.Require)`。

生成的资料合入 `app/src/main/generated/baselineProfiles/`，排除 `preview` 包中的样本生成代码。Startup Profile 用于 R8 的启动 DEX 布局优化；Baseline Profile 用于 ART 编译常用方法。生成后的文本可以提交，构建输出和设备诊断不提交。不在普通构建中自动启动设备或重新生成 Profile。

## 本地操作

在 Windows PowerShell 的项目根目录使用：

```powershell
# 在 API 34+ 设备上生成编译资料。
.\gradlew.bat :app:generateBaselineProfile

# 使用 R8 发布配置测量启动与交互性能。
.\gradlew.bat :benchmark:connectedBenchmarkPerformanceAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark'

# 检查最终 release 制品中的 Profile。
.\gradlew.bat :app:assembleRelease
python .github/scripts/verify_profiles.py app/build/outputs/apk/release/app-release-unsigned.apk `
  --mapping app/build/outputs/mapping/release/mapping.txt
```

普通构建可以使用已提交的资料。页面、模型或依赖图发生明显变化后，重新运行相同生成流程并验证打包。应用资料只来自运行记录，不以手写全包通配规则代替采集。

## 手动 CI

在 GitHub Actions 选择 `Generate application profiles`，手动运行需要更新的分支。采集工作流只接受 `workflow_dispatch`，不会因提交、PR 或普通打包自动运行。先在 API 36 模拟器采集，再检查优化性能 APK 和正式 APK；生成结果与诊断文件上传为制品。

CI 启用后置模拟摄像头，扫码场景必须到达预览就绪状态；不以页面出现或固定等待替代预览成功。采集后检查 `ScannerActivity.onCreate`、Compose 扫码页面、CameraX 绑定及解码器 `decode/prepare` 的方法规则，缺少覆盖就失败。x86_64 模拟器依靠系统镜像的 ARM64 原生桥接运行正式解码库，ABI 和桥接配置输出到采集日志；原生代码本身不属于 ART Profile 的编译范围。此流程验证编译资料覆盖，不提供实机转场掉帧结论。

在 main 或 beta 上运行成功后，分别使用 `perf/baseline-profiles-main` 或 `perf/baseline-profiles-beta` 创建更新 PR，只包含两份生成文件。其他分支提供生成制品。

创建 PR 优先复用仓库现有的 `RELEASE_PLEASE_TOKEN`，未配置时使用 `GITHUB_TOKEN`；后者需要仓库允许 Actions 创建 PR，且它创建的 PR 不会自动触发其他工作流。此时可手动运行现有构建工作流检查。此流程不要求提供真实业务凭据，不发布采集时构建的 APK。

正式打包读取已保存的 Profile，使用对应 R8 mapping 校验 APK 内方法所属类、DEX 条目的 ZIP CRC、方法表及 Profile 格式。发布重试复用原 APK。


## 性能结果

性能结果记录设备型号、API、ABI、刷新率、应用版本与 Benchmark / Perfetto 输出。在相同设备和样本上比较编译模式，统计冷启动中位数、尾部耗时与交互帧耗时。

Baseline Profile 由 ProfileInstaller 和系统 ART 加载，Startup Profile 在构建时用于优化启动代码布局。

参考：[Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile)、[Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)。
