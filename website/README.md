# 财小通官网

官网源文件在此目录，GitHub Pages 发布于主项目仓库。首页和下载页共用主题、动效与当前应用截图。

## 本地预览

在项目根目录运行：

```powershell
python -m pip install -r website/requirements.txt
python website/tools/build_pages.py
python website/tools/preview_server.py --directory website/_site
```

打开 `http://127.0.0.1:4173/`。构建自动读取主项目 GitHub Releases，在 `website/_site/releases.json` 生成版本数据，源码不维护版本快照。完整构建过一次后，可用 `--offline` 复用上次生成的数据进行本地样式预览。发布目录 `website/_site/` 不提交。

## 自动部署

`.github/workflows/pages.yml` 在 `main` 的网站、项目文档、许可证或更新配置变化后部署。Release 变化，以及 APK 构建或更新清单发布成功后也会刷新网站；后者覆盖由默认 GitHub Token 发布而不会再次触发 Release 事件的情况。所有生产部署使用 `main`，beta 发布不会部署 beta 分支的网站文件。PR 仅构建校验，不部署。

仓库 Settings → Pages 的 Build and deployment Source 使用 **GitHub Actions**。工作流只在部署任务申请 `pages: write` 与 `id-token: write`，不需要额外部署密钥。可手动运行 **Deploy website**。只改网站不会触发 APK 构建。

## 复用主项目

- 生活服务、使用统计等页面直接读取 `docs/`，不复制一套 Markdown。相关说明、开发文档与许可证之间的链接会转为站内链接。
- `LICENSE`、`NOTICE` 与 `app/libs/README.md` 生成站内许可页面。
- 下载来源读取 `updates/sources.json`；更新公钥读取 `updates/public-key.txt`；已签名的 stable 清单来自 `updates/stable/`，通过验签和有效期检查后补充 APK 信息。
- Action 自动生成版本、发布时间、原始更新说明、APK 地址、大小与 SHA-256；各版本的系统要求和包名读取对应 Release 标签的 `app/build.gradle.kts`，不是把当前配置填到历史版本中。缺少必须的包信息会令构建失败，保留上次成功部署。
- 更新说明在下载页内展示；其中引用到已生成的项目文档时也使用站内入口。GitHub 图标与问题反馈保留原入口。
- 下载按钮直接使用项目下载地址；备用来源在安装包详情中手动选择，不自动探测或切换来源。

构建后检查所有静态页面的本地链接及章节锚点。使用统计文档是说明页面，官网本身没有加入统计 SDK。

## 文件范围

`assets/screens/` 仅包含当前应用的 48 张 WebP，`assets/fonts/` 包含本地字库及许可证，`vendor/` 包含更新说明的 Markdown 解析器、清理器与许可证。不包含 Android 截图工具、原始 PNG、临时构建、旧版脚本或设计审查输出。
