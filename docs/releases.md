# 自动构建与发版

## 日常操作

### 正式版

1. 正常开发，将代码合并到 `main`。普通提交只构建 Debug 产物，不直接发布正式版。
2. Release Please 会在 Pull requests 页面创建或更新版本发布 PR，包含 `version.txt` 和 `CHANGELOG.md` 的修改。
3. 审核版本和日志后合并这个 PR。它会先创建 Draft Release，再由同一次工作流构建、签名、上传 APK，确认 APK 上传后公开 Release。
4. 清单任务验证 APK、签名证书和至少五个 CDN，签名发布 stable 清单。成功后才将该正式版设置为 GitHub Latest。

main 和 beta 都使用 Release Please 的版本 PR 流程；机器人生成的版本 PR 不需要人工修改版本、上传 APK 或手动创建 tag。两个渠道先创建带 tag 的 Draft Release，APK 上传后才公开。发布工作流显式调用清单任务，不依赖默认 `GITHUB_TOKEN` 再次触发 Actions。

提交信息影响自动版本：`fix:` 通常提升补丁版本，`feat:` 通常提升次版本，`feat!:` 或 `BREAKING CHANGE:` 声明不兼容变更。工具根据提交信息计算，不分析代码语义。合并时的 squash 提交标题也应保留准确的 type 和描述；不能为了发版改变实际改动的类型。

### 测试版

1. 将代码推送到 `beta`。普通提交只构建 Debug 产物，Release Please 自动创建或更新面向 beta 的版本 PR。
2. 审核版本和日志后合并版本 PR。创建 Draft Pre-release，构建签名并上传 APK，随后公开 Pre-release、发布 beta 清单。不会抢占正式版的 GitHub Latest。
3. 后续继续开发并合并新的 beta 版本 PR。版本名称由 Release Please 的原生 `prerelease` 策略生成，例如 `1.5.0-beta`、`1.5.0-beta.1`、`1.5.0-beta.2`；首版没有数字后缀。
4. 测试通过后将 beta 合入 main，审核并合并 main 的正式版本 PR，例如发布 `1.5.0`。正式发布的版本 PR 合并后同步 main 回 beta，再开始下一轮开发。

无需手动设定 beta 基础版本或维护 `versionCode`。两份固定配置通过同一个工作流按目标分支选择，合并不会把 beta 的预发布开关带给 main。正式/测试各有一份由机器人维护的版本 manifest：beta 版本 PR 不修改正式版 manifest，因此 main 即使合入了 `version.txt` 的预发布名称，仍以正式发布记录计算下一版并汇总完整正式日志，不会把 `1.5.0-beta` 当成已发布的 `1.5.0` 再升到 `1.6.0`。

正式版本 PR 使用 Release Please 原生 `extra-files` 同步 beta manifest 的正式基线。把该 PR 同步回 beta 后，下一轮基于新正式版本计算，例如 `1.5.0` 后的修复产生 `1.5.1-beta`，不会继续发布旧周期的 `1.5.0-beta.3`。普通合并保留原提交即可；使用 squash 合并时，合并标题应准确保留 `feat`/`fix`/不兼容变更含义，版本工具不能从任意标题推断被压缩的提交内容。

纯文档与生成的清单提交不会触发 APK 构建。策略和候选源修改由独立清单任务处理。其他分支不自动发布；PR 验证不注入学校或生活平台 Secrets。

## 版本文件与编号

| 文件/字段 | 谁维护 | 含义 |
| --- | --- | --- |
| `version.txt` | 两个分支的 Release Please 版本 PR | 完整产品版本，如 `1.5.0`、`1.5.0-beta.1` |
| `.release-please-manifest.json` | main 的 Release Please 版本 PR | 正式版本记录；beta PR 不修改它 |
| `.release-please-manifest-beta.json` | beta 的版本 PR；main 的版本 PR 自动同步正式基线 | 测试版本记录及下一测试周期基线 |
| `ci-version.properties:localVersionCode` | 开发者 | 本地开发默认编号，初始仍为 9 |
| `ci-version.properties:versionCodeOffset` | 首次配置/迁移 | CI 编号偏移，初始 100000 |
| `build-version.properties` | Actions 自动生成，不提交 | 当前 APK 的 versionName、versionCode、channel 和 commit |
| `build-metadata.json` | Actions 自动生成并保存为发布资产 | 构建追踪信息 |

Gradle 仅在明确设置 `APP_BUILD_VERSION_FILE` 时读取构建生成文件，本地普通构建不会误用残留 CI 配置。本地版本从 `version.txt` 读取，渠道默认 stable；测试构建可设置 `UPDATE_CHANNEL=beta`。普通 Debug/PR/手动构建沿用分支上的版本名称，不擅自推算下一版，也不会更新公开清单。

发布编号为 `versionCodeOffset + github.run_number`。main、beta 共用 `.github/workflows/build.yml`，因此共用编号序列；Debug/PR/手动任务也消耗运行编号，允许跳号。APK 的 `versionName` 原样使用 Release Please 版本，beta 后缀数字是预发布序号，与安装编号无关。About 页面显示 versionCode，CI 包额外显示实际构建 commit 短摘要。合并后的正式发布使用后续工作流编号，可以覆盖此前的测试包；应用内更新仍只比较 versionCode。

迁移工作流、修改偏移或存在更大历史编号时，必须先保证未来的编号高于两个渠道的所有已发布 APK。不要使用 `run_attempt`，不要为 beta 新建独立编号工作流。编号不按日重置，也不要求相邻。


## 仓库配置

允许 Actions 创建版本 PR。Release Please 默认使用 `GITHUB_TOKEN`，需要生成的 PR 触发普通检查时可配置 `RELEASE_PLEASE_TOKEN`，授予本仓库 Contents 和 Pull requests 读写权限。

APK 签名需要 `SIGNING_KEY`、`ALIAS`、`KEYSTORE_PASSWORD`，接口配置见[开发说明](development.md)。更新私钥使用 `UPDATE_SIGNING_KEY`，见[清单配置](../updates/README.md)。清单任务需要受控提交 main 和更新 Latest 的权限；分支保护限制默认 Token 时，按已有授权配置 `UPDATE_PUBLISH_TOKEN`，不能强推覆盖保护。

## 故障恢复

- 构建或签名失败：修正后重跑，缺少签名材料时不发布未签名产物。
- Draft Release 未上传 APK：修复后重试原任务，避免重复版本。
- APK 已公开但清单失败：检查 CDN、签名与策略，手动运行 `Publish app update metadata` 并填写原 tag。重试复用唯一原 APK，不重建覆盖。
- 策略或 latest 并发变化：同步当前配置再重试，旧结果不覆盖新策略。
- 续期失败：检查每日维护，已确认的强制边界不因错误或过期解除。

工作流、配套脚本与测试统一维护在 `.github/`。
