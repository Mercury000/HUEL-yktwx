# 技术迁移与验证记录

目标：按 [技术选型评估](modernization-assessment.md) 逐项完成、逐项验证、逐项提交。各项实施完整切换职责，不长期保留双实现。兼容基线为正式版 1.4.1（`1bb98ea5bc87e9a217ce6b8af10c47046aa92c73`）；其后的未发布格式不保留兼容。网络层已由独立任务提交，提交为 `fafc872`。

| 项目 | 状态 | 验证与完成证据 |
| --- | --- | --- |
| Version Catalog | 完成 | root/app 统一引用 `gradle/libs.versions.toml`；迁移前后 debug、preview、release 和 debug 单元测试的编译依赖及根构建脚本依赖完全一致 |
| kotlinx.serialization | 完成 | 生产代码及测试依赖移除 org.json；18 组、224 项 JSON 相关定向测试通过；专门存储兼容集中到 `StoredRecordJson` |
| DataStore 与启动准备状态 | 待实施 | 尚未完成三类旧设置迁移、策略原子性及加载前阻断 |
| Hilt 全图接入 | 待实施 | 尚未完成应用依赖图和三种构建绑定验证 |
| Macrobenchmark 与 Profiles | 待实施 | 尚未生成/打包 profile，也没有发布配置测量数据 |
| Camera2 session API | 待实施 | 尚未更新重载及完成相机定向验证 |

## Version Catalog

集中声明 Maven 依赖与 root/app 插件版本，统一 Kotlin、Miuix、协程、AboutLibraries 和 OkHttp 的共享版本，保留 Compose BOM、release 专属友盟、debug 工具及本地厂商二进制范围。Gradle Wrapper 和 settings 阶段的 Foojay 引导插件继续使用各自的配置机制。

验证使用相同的依赖快照任务分别解析迁移前后的 `debugCompileClasspath`、`previewCompileClasspath`、`releaseCompileClasspath`、`debugUnitTestCompileClasspath` 和根 `buildscript` classpath，比较所有解析出的坐标与版本，差异为 0。Gradle 配置及解析成功；该项不改变应用行为，未运行应用测试、APK 构建或 Lint。

本地诊断记录位于未跟踪的 `tools/modernization/catalog-before.log` 与 `catalog-after.log`。后续迁移的新增依赖单独验证，不改变本项保持既有版本的结论。

## kotlinx.serialization

全部业务 JSON 统一为 kotlinx.serialization：动态接口使用不可变 JsonObject/JsonArray 和明确的混合类型转换，展示缓存改为具名白名单 DTO，凭据及生活平台订单使用生成的序列化器。JSON 运行时为显式依赖，移除 org.json 测试依赖。更新继续先验证原始 payload 字节再解析，清单摘要仍针对原始文件字节；支付金额、错误分类和请求签名的业务规则保持原口径。

展示缓存使用 `display-v2`，旧快照直接淘汰、重新查询。专门的历史存储规则集中到 `data/StoredRecordJson.kt`：接受 1.4.1 的无版本号记录、补基线已有单条订单的确定性 ID、在列表不存在时读取单条来源；有空列表时不能恢复旧记录。写入统一带版本 2，包括登录失效后的本地订单删除。范围和后续删除条件见 [JSON 存储格式与兼容清理](json-storage-compatibility.md)。

验证覆盖 18 个直接相关测试类，共 224 项，全部通过、无跳过：更新签名及严格数字类型，账单和身份编码，登录、个人资料、校园卡和缴费/充值协议，展示缓存，以及两家生活平台的接口和加密记录编解码。旧格式凭据和未知订单使用固定历史 JSON 样本验证，另覆盖账户隔离、删除后不恢复、保存失败和旧缓存淘汰。仅执行 `testDebugUnitTest` 的明确筛选，没有运行全量 UI 测试、发布 APK 构建或 Lint；生产及测试 Kotlin 编译、暂存前空白检查通过。

按发布边界收紧后，仅重新运行 `StoredRecordJsonTest`、`LifeDeviceAccountStoreTest`、`Hui798AccountStoreTest`，验证基线记录恢复和未发布显式版本拒绝；全部通过。

Android 启动验证发现系统 Ed25519 KeyFactory 无法导入发布公钥，更新验签改用 Tink Android 1.24.0 的可移植实现。公钥继续采用固定 Ed25519 SPKI 编码，先校验原始 payload 字节、再解析 JSON，不修改全局安全提供者。复用 Python 发布工具的真实签名样本，24 项主机更新接口测试及 4 项 Android 验签/应用入口测试通过、无跳过；Android 验证在暂停设备操作前完成。
