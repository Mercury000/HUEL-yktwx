# 深色配色与相邻层级

深色模式沿用浅色的照明蓝色、空调青色、填色候选和余额渐变，基础底板与文字参考 Miuix Demo。浅色和莫奈浅色保留原来的颜色、透明度和布局；普通深色仍使用 Demo 的蓝白主按钮，莫奈使用库生成的动态配色。

## 配色基准

参考工程的 `example/android` 是入口，实际配色来自 `example/shared/src/commonMain/kotlin/ui/Theme.kt` 的默认 `ThemeController`。应用继续使用同一控制器，没有覆盖全局颜色角色。参考工程的 `Colors.kt`、`Button.kt`、`TextField.kt`、`TabRow.kt`、`Card.kt` 与应用使用的 `0.9.4-rc01` 缓存发布源码已逐行核对，相关文件一致。

业务组件在局部适配深色：

- 大厅照明和空调面板从库卡片灰底少量融入蓝青色，内层按钮从次要按钮灰底融入同一色调；数值用 `onSurface`，图标和操作文字保留蓝青倾向。
- 余额卡保留渐变和环形装饰，深色渐变向页面暗色轻微过渡；标题、账户说明不再额外降低前景透明度，限制状态使用暖色暗底。
- 候选保留整卡填色，深色未选与禁用使用库对应角色；紧凑操作、可移除筛选条件使用独立灰底，辅助文字使用对应前景。
- 主按钮、输入框、开关、复选框、滑块沿用库默认配对；深色读取进度使用默认前景和轨道。

## 同色与图标问题

不能只凭颜色角色名称判断层级不同。普通深色的 `background`、`surfaceContainer` 和 `surfaceContainerHigh` 均为 `#242424`，使用时必须核对真实父底板；透明颜色需要检查合成结果。

| 位置 | 问题与修正 |
| --- | --- |
| 账单“明细 / 统计”完整底板 | 默认轨道与页面均取 `surface`，整体轨道消失。深色改为黑色页面 → `surfaceContainer` 轨道 → `secondaryVariant` 选中项，普通深色依次为 `#000000`、`#242424`、`#434343`。 |
| 账单列表、详情、排行图标底座 | 支出底座与卡片同色。深色支出改用 `secondaryVariant/onSecondaryVariant`，收入保留 `primaryContainer/onPrimaryContainer`；扫码支付等图案共用此路径。 |
| 透明头像裁剪区 | 裁剪底板与弹层同色，透明图片让轮廓消失。深色改用 `secondaryVariant`，图片像素不变。 |
| 悬浮导航覆盖卡片 | 普通和 iOS 悬浮底板与卡片同取 `surfaceContainer`。深色统一改用 `surfaceContainerHighest`；普通悬浮模糊及其降级背景也使用此角色。 |
| 默认头像与“我的”服务图标 | 图案偏暗、服务透明底座融入卡片。深色图案将主色少量混入 `onSurface`，服务底座从次要按钮灰底少量融入主色。 |
| 内联错误、读取失败、支付失败图案 | 莫奈深色红色图案与红底对比弱。深色改用 `errorContainer/onErrorContainer` 成对角色，保留图案形状和尺寸。 |

以上修正没有新增白色描边。图标的大小、留白和圆角，以及页签的选择行为、尺寸和动画均保留。

## 相邻层级检查范围

| 相邻层级 / 页面范围 | 核对结果 |
| --- | --- |
| 付款、大厅、账单、我的、个人信息、账户详情、设置、主题、关于、引用页 → 首层卡片 | 页面和卡片配对分开；没有额外嵌套同色底板。 |
| 大厅卡片 → 照明 / 空调 / 水费面板 → 充值按钮 | 面板与外卡、按钮与面板分别有独立填色。 |
| 大厅 / 校园网卡片 → 状态标签、紧凑按钮 | 标签与按钮底板与卡片分开。 |
| 余额卡 → 状态标签、充值按钮、装饰和汇总分隔线 | 标签暗底、按钮前景填色、渐变和环形装饰可辨，汇总分隔线使用深色 divider 角色。 |
| 账单卡片 → 列表 / 明细 / 排行图标 | 收入和支出底座与卡片分开，覆盖 34dp、40dp、56dp 三种尺寸。 |
| 我的服务卡片 → 服务图标；个人信息 / 头像弹层 → 默认头像 | 服务底座有独立灰蓝填色，头像底板与卡片和弹层分开。 |
| 充值、代人充值、房间管理、缴费弹层 → 选中 / 未选 / 禁用候选 | 候选填色与弹层分开，辅助文字不额外降低深色前景透明度。 |
| 登录和业务表单 → 输入框；房间选择 → 位置下拉字段 | 输入和位置字段与父底板分开；字段内部透明行属于字段自身。 |
| 各表单弹层 → 金额预设、卡片候选、账号候选、取消 / 提交按钮 | 核对未选、选中和禁用默认填色，与父底板分开。 |
| 账单筛选弹层 → 筛选胶囊；页面 → 已应用条件 | 胶囊和条件底板独立于页面或弹层。 |
| 统计卡片 → 趋势提示、节点、分类圆环、图例 | 趋势提示使用页面底色叠加在卡片上，节点内圈、图例和圆环与卡片分开。 |
| 页面 / 卡片 / 弹层 → 错误提示、支付结果图案、骨架和读取进度 | 检查实际透明度合成，图案与底座成对取色。 |
| 付款卡片 / 放大弹层 → 二维码、条形码白色底板 | 与深色父底板分开，保留扫码白底。 |
| 弹层、确认对话框、下拉浮层 → 被覆盖页面或卡片 | 库遮罩参与最终颜色，核对绑定、解绑、退出及日期和主题下拉。 |

统计“支出 / 收入”是库的分散页签，未选区与页面同层，通过边界和选中填色区分。大厅带轮廓页签的选中项与弹层同色，但中间有独立黑色轨道隔开。非悬浮导航和顶栏共用页面底色，由分隔线、布局或模糊区分。这些不属于独立底板消失，不做机械改色。

## 验证

全部验证采用离线 Android 35 渲染与虚构数据。按共享路径运行定向用例，没有运行全量测试、Lint 或发布构建。

| 验证 | 覆盖 |
| --- | --- |
| `MiuixDemoThemeTest` | 全局 53 个角色在系统浅深色、普通 / 莫奈、指定 / 跟随系统的 12 种配置下与库控制器一致，检查浅色基础颜色保留。 |
| `ThemeBehaviorTest` | 同样 12 种配置下核对主题、局部夜间配置、输入内容和滚动位置一致。 |
| `DarkModeAuditTest.dashboardPagesAcrossThemes`、`controlStatesAndBoundaries` | 四种主题下大厅、余额卡、多房间、候选、筛选、输入、焦点、禁用和指示控件。 |
| `IconBoundaryAuditTest` | 账单六种图案 × 三种尺寸 × 收入 / 支出 × 四种主题，共 144 个组合；另核对服务和失败图案。 |
| `DarkModeAuditTest.billPagesAndProfileIcons` | 真实账单列表、扫码详情、统计排行和“我的”。 |
| `DarkModeAuditTest.billModeTabsKeepWholeContainerAcrossThemesAndSelection` | 四种主题 × 两种选择状态，验证完整轨道、选中填色及稳定边界。 |
| `DarkModeAuditTest.transparentAvatarCropAcrossThemes` | 四种主题，验证透明区底板与真实弹层不同、图片实色像素不变、浅色底板不变。 |
| `DarkModeAuditTest.floatingBarsKeepSeparateSurfaceOverCards` | 四种主题 × 两种悬浮样式，核对模糊关 / 开 / 恢复、底板像素和几何稳定。 |
| `DarkModeAuditTest.allNavigationSurfaceLayers` | 普通和莫奈深色的页面、业务弹层与下拉浮层导航截图，检查真实使用背景。 |
| `IosBottomBarBlurTest.appBlurSwitchChangesIosSurfaceAndRestoresItInBothThemes` | 已有 iOS 模糊开关行为保持，关闭后恢复不透明底板。 |

以上定向用例均通过。最后一组层级与模糊验证共 5 项通过，0 失败、0 错误。截图检查包含 86 张页面与弹层排查原图、20 张透明头像和导航修正后截图，以及账单页签的 16 张完整页和裁剪图。全页面排查原图是在提升导航底色前生成，修正后的导航另用叠卡用例验证。

针对相邻层级的复核可单独运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.mercury.yktwx.DarkModeAuditTest.billModeTabsKeepWholeContainerAcrossThemesAndSelection' --tests 'com.mercury.yktwx.DarkModeAuditTest.transparentAvatarCropAcrossThemes' --tests 'com.mercury.yktwx.DarkModeAuditTest.floatingBarsKeepSeparateSurfaceOverCards' --console=plain
```

渲染用例会将截图写入本地 `tools/dark-mode-adapted/`、`tools/icon-boundary-audit/`、`tools/bill-tabs-review/` 或 `tools/surface-hierarchy-audit/`。`tools/` 按仓库规则忽略，截图、日志和历史调色报告不纳入版本控制。验证不包含真机 GPU、任意壁纸种子或用户任意图片内容的穷举。
