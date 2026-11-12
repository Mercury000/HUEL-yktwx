# 自动构建与发版

## 日常操作

### 正式版

1. 正常开发，将代码合并到 `main`。普通提交只构建 Debug 产物，不直接发布正式版。
2. Release Please 会在 Pull requests 页面创建或更新版本发布 PR，包含 `version.txt` 和 `CHANGELOG.md` 的修改。
3. 审核版本和日志后合并这个 PR。它会先创建 Draft Release，再由同一次工作流构建、签名、上传 APK，确认 APK 上传后公开 Release。
4. 清单任务验证 APK、签名证书和至少五个 CDN，签名发布 stable 清单。成功后才将该正式版设置为 GitHub Latest。

main 和 beta 都使用 Release Please 的版本 PR 流程；机器人生成的版本 PR 不需要人工修改版本、上传 APK 或手动创建 tag。两个渠道先创建带 tag 的 Draft Release，APK 上传后才公开。发布工作流显式调用清单任务，不依赖默认 `GITHUB_TOKEN` 再次触发 Actions。

提交信息影响自动版本：`fix:` 通常提升补丁版本，`feat:` 通常提升次版本，`feat!:` 或 `BREAKING CHANGE:` 声明不兼容变更。工具根据提交信息计算，不分析代码语义。合并时的 squash 提交标题也应保留准确的 type 和描述；不能为了发版改变实际改动的类型。

现有产品基线为 `1.4.1`。两份 Release Please manifest 与配置中的 bootstrap commit 标记接入前基线；已有数字 tag 保留，新正式版使用 `v1.5.0` 这样的 tag。

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

## 首次启用

1. 提交本次接入文件。将同一套工作流、脚本和更新公钥同步到 beta 分支；若尚无该分支，按仓库自己的分支管理流程建立。
2. 仓库 Settings → Actions → General → Workflow permissions，允许 GitHub Actions 创建 Pull requests（`Allow GitHub Actions to create and approve pull requests`）。审批与 main 分支保护仍按仓库规则执行；机器人不自动合并版本 PR。
3. 保留原有 APK 构建/签名 Secrets：`SIGNING_KEY`、`ALIAS`、`KEYSTORE_PASSWORD` 和应用接口配置。
4. 配置 `UPDATE_SIGNING_KEY`，见 `updates/README.md`。第一次自动发版会从签名 keystore 导出公有证书指纹，传给清单任务；之后两个渠道的签名清单保存该指纹，自动维护无需重复读取 APK 私钥。
5. 确认 main 的分支规则允许受控更新清单提交。发布任务仅推送生成文件，不强推；默认 `GITHUB_TOKEN` 必须遵守分支规则。若 main 要求 PR 且当前账号已在规则的绕过名单中，可为该账号生成仅限本仓库、Contents 读写权限的 fine-grained Token，配置为 `UPDATE_PUBLISH_TOKEN`。清单任务使用它完成 checkout、推送和 Release Latest 更新；其他构建任务不使用它。Token 只提供账号已有的权限，不会自行修改分支规则。若账号没有绕过权限，配置 Token 也不能直接推送受保护分支。

Release Please 默认使用仓库的 `GITHUB_TOKEN`，不要求额外 Token。不过该 Token 创建的 PR 不会自动触发普通 PR Actions。如果 main 或 beta 要求 PR 检查通过后才能合并，建议另外配置一次 `RELEASE_PLEASE_TOKEN`：使用 GitHub App token 或仅限本仓库的 Token，授予 Contents 和 Pull requests 读写权限。工作流会优先使用它，版本 PR 可以正常触发验证；不要把 Token 写到代码里。

### 本仓库配置进展（2026-11-12）

已通过 `gh` 开启 Actions 创建 PR 的权限，默认工作流权限仍为只读。原有 APK 签名和接口 Secrets 已确认存在；`UPDATE_SIGNING_KEY` 已配置，上传前已确认其私钥与 `updates/public-key.txt` 匹配。

main 当前由 ruleset 要求 PR 和一次审核，当前账号已有绕过权限，分支规则保持原样。2026-11-12 重新查询仓库 Secrets，已确认 `UPDATE_PUBLISH_TOKEN` 存在；Secrets 列表只能证明已配置，实际写入权限仍由首次发布任务验证。后续需要轮换时，使用 GitHub Settings → Developer settings → Personal access tokens → Fine-grained tokens，仅选择本仓库，将 Contents 设为 Read and write，并选择合适的有效期。`gh` 不能代替 GitHub 页面创建这个 Token，也不要将本机 `gh` 登录用的广泛授权凭据复制到 Actions。

轮换后在本机 PowerShell 运行以下命令，按提示粘贴 Token；不要把 Token 写进命令参数、聊天或代码：

```powershell
& 'C:/Program Files/GitHub CLI/gh.exe' secret set UPDATE_PUBLISH_TOKEN --repo 'Mercury000/HUEL-yktwx'
```

当前 main 没有要求 PR 状态检查，因此 `RELEASE_PLEASE_TOKEN` 暂不必配置。beta 分支尚不存在；接入代码仍在本地，合并到 main 后再从含接入代码的 main 建立 beta，保证两个渠道共用同一工作流与公钥。尚未触发线上构建或发版。

## 故障恢复

- 首选 Actions 的 **Re-run failed jobs**，保留原工作流编号及版本身份。
- 完整重跑同一次任务时会检查既有 Release；已存在唯一 APK 则下载并复用，验证签名后只补做后续任务，不覆盖制品。
- 两个渠道的版本 PR 都可能在构建/上传失败时保留 Draft Release，未公开且未切换更新指针；修复后重跑失败任务。首个工作流若没有获得 Release Please 的输出，恢复只接受当前实际构建 commit 上、版本名称与渠道一致的 tag/草稿，不会把旧版本 tag 当作一次新发布。
- 已有 Release APK，但清单发布失败：等待定时维护自动补发，或运行 **Publish app update metadata**，填写已有 tag。不要重新构建并替换原 APK。
- 每天北京时间 08:30 执行维护，实际运行时间受 GitHub 排队影响；剩余有效期不超过 14 天时验证 APK/CDN 并重新签发 90 天清单。策略或候选池提交到 main 时也触发维护。
- 如果 stable 指针已提交、最后设置 GitHub Latest 的步骤失败，定时维护会补设 Latest；无需重签尚未到续期阈值的清单。
- 网络与 CDN 预热失败最多自动尝试三次。签名错误、同版本不同 APK、策略回退或发布期间策略改变不会用重试掩盖；任务失败且保持原 latest。
- 两个渠道的维护独立执行，一个失败仍会处理另一个，最后汇总失败使 Actions 显示异常。
- 维护按产品版本选择候选，beta 在同一产品版本内比较预发布序号（`beta < beta.1 < beta.2`），再读取 APK 的真实 versionCode。旧的 `beta.100123` 名称仍可识别，但不会因为数字大而压过下一产品版本。较旧任务即使后完成，也不会覆盖较新清单。

人工发布首次清单且尚无可信证书记录时，可以配置公有指纹 Secret `APK_SIGNER_SHA256`。正常自动构建不需要此额外配置；现有签名清单中的证书与显式提供的证书不一致时拒绝发布。签名轮换需要独立迁移方案。

## 验证边界

本地定向验证覆盖渠道选择/重启恢复、双渠道缺失回退、无效清单拒绝、强制边界保留、编号生成、签名证书比对、续期阈值、重试分类，以及真实临时 Git 仓库的并发推送。

本次 Release Please 接入另有原生工具演练：使用固定 Action 实际依赖的 `release-please@17.3.0`，仅替换 GitHub 边界，验证 beta 版本 PR、预发布草稿、合入 main 后的正式版本与完整日志、正式基线同步后的下一周期、squash 合并和旧数字 tag 接入。版本计算与版本文件更新均运行工具本身。可在隔离临时目录安装该依赖，通过 `RELEASE_PLEASE_MODULE` 指向其模块目录，执行 `node --test scripts/test_release_please.cjs`。

GitHub 上的 Release Please PR、仓库写权限、Actions 签名 Secret、真实 CDN 和系统安装仍需启用后验收。本地验证不推送代码、不创建线上 Release，也不改变强制更新边界。
