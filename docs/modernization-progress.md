# 技术迁移与验证记录

目标：按 [技术选型评估](modernization-assessment.md) 逐项完成、逐项验证、逐项提交。各项实施完整切换职责，不长期保留双实现。网络层已由独立任务提交，提交为 `fafc872`。

| 项目 | 状态 | 验证与完成证据 |
| --- | --- | --- |
| Version Catalog | 完成 | root/app 统一引用 `gradle/libs.versions.toml`；迁移前后 debug、preview、release 和 debug 单元测试的编译依赖及根构建脚本依赖完全一致 |
| kotlinx.serialization | 待实施 | 尚未完成全部生产 JSON 边界及旧数据兼容验证 |
| DataStore 与启动准备状态 | 待实施 | 尚未完成三类旧设置迁移、策略原子性及加载前阻断 |
| Hilt 全图接入 | 待实施 | 尚未完成应用依赖图和三种构建绑定验证 |
| Macrobenchmark 与 Profiles | 待实施 | 尚未生成/打包 profile，也没有发布配置测量数据 |
| Camera2 session API | 待实施 | 尚未更新重载及完成相机定向验证 |

## Version Catalog

集中声明 Maven 依赖与 root/app 插件版本，统一 Kotlin、Miuix、协程、AboutLibraries 和 OkHttp 的共享版本，保留 Compose BOM、release 专属友盟、debug 工具及本地厂商二进制范围。Gradle Wrapper 和 settings 阶段的 Foojay 引导插件继续使用各自的配置机制。

验证使用相同的依赖快照任务分别解析迁移前后的 `debugCompileClasspath`、`previewCompileClasspath`、`releaseCompileClasspath`、`debugUnitTestCompileClasspath` 和根 `buildscript` classpath，比较所有解析出的坐标与版本，差异为 0。Gradle 配置及解析成功；该项不改变应用行为，未运行应用测试、APK 构建或 Lint。

本地诊断记录位于未跟踪的 `tools/modernization/catalog-before.log` 与 `catalog-after.log`。后续迁移的新增依赖单独验证，不改变本项保持既有版本的结论。
