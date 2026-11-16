# Miuix 快照与玻璃风格

项目固定使用 `0.9.4-3a980f7c-SNAPSHOT`，对应官方提交 `3a980f7c509c62c6d315f71ac28b40ecc8d31f3b`。所有 Miuix 模块使用同一版本，包含 `miuix-glass-android`、`miuix-glass-icons-android` 和直接使用其类型的 `miuix-material-android`。

主题设置只新增一个“玻璃风格”开关，统一控制顶栏样式、返回及操作按钮、对话框、底部弹层、下拉菜单和适用的短标签组，默认关闭。原“顶栏模糊”选项始终保留：普通顶栏下控制原背景模糊；玻璃顶栏下对应官方 `Glass Top Bar Progressive Blur`，打开渐进模糊，关闭后仍保留原生渐变背景和玻璃按钮。玻璃风格不改写模糊偏好。

导航设置统一使用“悬浮导航”“导航样式”和“导航模糊”，依次提供默认、iOS、OS4；这些选项始终显示。已有 iOS 偏好仍为 `1`，OS4 使用值 `2`。选择 iOS 或 OS4 会启用对应导航，关闭悬浮导航则使用普通底栏或侧栏，保留原样式偏好供再次启用。玻璃风格不会改写导航选择；导航模糊控制底栏、玻璃侧栏及服务进度小岛的背景采样，关闭时使用各自的不透明回退，官方普通侧栏始终使用实色。

宽屏默认使用官方 `NavigationRail`，OS4 使用新版本 demo 的 `GlassNavigationRail`，iOS 保留原悬浮底栏。玻璃侧栏收起时显示顶部文字胶囊，展开时显示左侧图标与文字面板，采用原生尺寸、材质、颜色、拖动和展开动画。页面为展开面板让出 `Margin + ExpandedWidth`，顶栏标题透明度随展开进度变化，背景采样层铺满页面并排除导航本身。窗口宽度达到 1200dp 时默认展开，较窄宽屏默认收起，并保留手动切换。当前页面、滚动状态和导航栈在切换样式及窗口尺寸时保持原位。

## 与固定版本 demo 的设置映射

官方 `SettingsPage` 的 `Enable Blur Effect`、`TopAppBar Style` 和 `Glass Top Bar Progressive Blur` 是三个不同的状态。`AdaptiveTopAppBar` 中的 `backgroundBlur` 来自 `BlurredBar` 的背景启用条件，不能拿渐进模糊偏好代替。官方全局模糊关闭时会同时撤销顶底栏采样；本项目已有独立顶底栏选项，因此不新增全局模糊开关，而按下表简化映射。

| 本项目设置 / 路径 | 官方状态或组件 | 本项目的具体关系 |
| --- | --- | --- |
| 玻璃风格 | `topAppBarStyle` 的 Original / Glass；`GlassPage` 的玻璃弹层与标签示例 | 切换共用顶栏为 `GlassTopAppBar`，并统一适用弹层材质；导航样式独立选择 |
| 顶栏模糊，玻璃关闭 | `enableBlur` 在普通顶栏的背景作用 | 保留项目原有普通顶栏渐进背景模糊；半径 16、底色透明度 0.66 是原有视觉参数，与官方普通顶栏的 25 / 0.8 不同 |
| 顶栏模糊，玻璃开启 | `glassTopBarProgressiveBlur`；`GlassPage.topAppBarProgressiveBlur` | 只传给 `GlassTopAppBar.progressiveBlur`；按钮仍采样，`bandBrush`、`bandOverhang`、材质、描边和阴影采用原生默认值 |
| 导航模糊 | `enableBlur` 在 `AppContent.NavigationBar` 和玻璃侧栏的背景作用 | 控制普通、默认悬浮、iOS、OS4 底栏、玻璃侧栏及配套扫码/服务小岛的 backdrop；关闭不改变布局、选中项与样式，普通侧栏保持实色 |
| 悬浮导航 | `useFloatingNavigationBar`，以及应用保留的宽屏 iOS 布局 | 关闭后使用普通底栏或侧栏；开启后默认按窗口选择原生悬浮底栏/普通侧栏，iOS 使用原悬浮底栏，OS4 使用原生玻璃底栏/侧栏 |
| 导航样式：默认 / iOS / OS4 | 官方 `floatingNavigationBarStyle` 和 `navigationRailStyle` | 项目存储值为 0 / 1 / 2，手机官方为 0 / 2 / 1；宽屏默认/OS4 映射 Original/Glass 侧栏，iOS 保留原底栏，不新增独立侧栏样式选项 |
| 扫码显隐 | `GlassNavigationBar.trailingAction` 与 `GlassPage.navigationBarSearchButton` | 仅付款 tab 提供独立操作；OS4 使用原生宽度、间距、缩放与透明度动画；默认/iOS 用相同布局动画连接各自材质 |
| 关于页背景显隐 | `AboutPage.collapsed`、`blurActive` | hero 未完全收起时背景透明；完全收起后按普通/玻璃顶栏各自路径绘制背景。按钮浮出仍由实际列表滚动驱动，独立于背景条件 |
| 返回与操作按钮浮出 | `isContentScrolled`、`visualContentOffset`、`overscrollOffset` | 真实滚动与弹性位移驱动原生动画；主题短列表具有真实滚动空间；主题及关于页同时提供实际列表重叠状态和视觉位移，程序滚动也能恢复背景和阴影 |
| 弹层、菜单与内嵌控件 | `GlassDialog`、`GlassBottomSheet`、`GlassDropdownPreference`、`GlassSegmentedTabRow` | 玻璃风格统一控制；不受顶底栏模糊选项影响；保留宿主、关闭限制、展开恢复、业务草稿和选中状态 |

玻璃模式下，相当于保留顶栏的玻璃采样能力，再用“顶栏模糊”切换渐进背景。它并不等同于把官方 `enableBlur` 直接赋值为顶栏模糊；否则关闭渐进模糊会连按钮材质和渐变背景一起撤销。顶底栏共用的记录层在任一栏需要采样或玻璃风格开启时存在，各栏仍分别判断自己的选项。弹层另用排除自身及遮罩的页面记录层。

本轮审查纠正了映射与遗漏：把顶栏模糊当作全局模糊而隐藏玻璃渐变；关于页普通顶栏未接模糊；关于页只报告列表重叠而未报告视觉位移，程序滚动时背景与阴影可能不同步；用纯白标题判断相机例外；顶栏玻璃偏好意外切换导航侧栏图标。相机现在明确选择透明普通顶栏，不靠文字颜色推断。

## 组件与交互

| 界面 | 接入方式 |
| --- | --- |
| 大标题顶栏 | 官方 `GlassTopAppBar`，按钮使用官方 44dp 尺寸与默认材质；在页面顶部只显示图标，内容滚动后浮出玻璃底板，标题和背景带继续跟随滚动，渐进模糊由顶栏模糊开关控制 |
| 关于页顶栏 | 按官方关于页实践，玻璃模式使用 `GlassTopAppBar` / `GlassIconButton`，44dp 按钮、28dp 返回图标和 12dp 边距；通过 `listState.canScrollBackward` 控制玻璃底板，使用原生材质、阴影、描边与动画；顶部只显示返回图标，滚动后出现玻璃，回到顶部后收起；背景待 hero 完全收起后显示，普通模式也接入共用模糊路径 |
| OS4 底栏 | 官方 `GlassNavigationBar`，不覆盖选中图标、标签和指示器的默认颜色；普通主题为黑白图标与中性选中底板，Monet 按官方默认使用动态主题色；拖动预览，松手提交一次选择，保留标签及无障碍描述 |
| 独立扫码操作 | 仅在付款标签出现。手机 OS4 使用 `trailingAction` 及其原生出现/收起动画，按示例应用采用 54dp 按钮、28dp 图标和 `GlassShadows.Float`；默认和 iOS 使用按官方布局实现的宽度、间距、缩放和透明度联动动画，底栏一直保持挂载；默认扫码共用底栏底色、模糊和阴影，iOS 共用玻璃、描边和阴影；忙碌时保留位置并禁用。普通底栏与平板普通/OS4 侧栏布局保留付款页右上角入口，OS4 顶部标签及展开面板的位置一致；iOS 在手机和平板都使用底栏右侧入口，顶栏不重复显示 |
| 服务进度小岛 | OS4 使用与底栏相同的材质和描边，iOS 继续使用原折射材质；业务状态和动作保留 |
| 对话框及抽屉 | 原 `OverlayDialog` / `OverlayBottomSheet` 的 `surface` 插槽，使用 `glassDialogSurface` / `glassBottomSheetSurface`；转发尺寸、安全区、关闭限制和完成回调 |
| 弹层内按钮与表单 | 官方 `GlassDialogButton` 的半透明普通按钮、主色主要按钮及按压反馈；输入框、选择卡和内嵌中性卡片用配套的半透明底色，保留焦点、选中与禁用状态，不增加文字折射或重复模糊 |
| 设置及缴费位置菜单 | 按官方 `GlassDropdownPreference` 的布局与宿主方式组合 `GlassDropdownPopup`、值锚点和 `GlassPopupItem`；保留原 `rememberSaveable` 展开状态，选中值和禁用状态由调用方控制 |
| 短标签组 | 明细/统计使用官方 `GlassSegmentedTabRow`；收入/支出保留原 `TabRow` 的按钮宽度和排布；支付方式与房间切换保留原 `TabRowWithContour` 的宽度、高度和滑块，仅适配弹层中的半透明底色；长列表和无有效选中项沿用原标签实现 |
| OS4 导航图标 | 付款和账单使用 miuix 风格的自绘二维码与票据，与普通底栏、默认悬浮、iOS 悬浮和侧栏共用；大厅和联系人使用 Glass 图标集，独立扫一扫使用官方 Scan |
| 导航侧栏 | 默认使用官方 `NavigationRail`，OS4 使用官方 `GlassNavigationRail`；玻璃侧栏的标题、留白与展开动画联动，iOS 保留底部悬浮；顶栏玻璃偏好独立 |

主标签页使用库的 `springAnimateToPage` 和 `PagerNavigationSpringSpec`。水平手势按 `pagerGestureOverride` 官方示例配置：Pager 关闭自己的触控识别器，由 CrossAxis 工具处理接管；非触控输入及 fling 使用同一行为，`PagerGestureNestedScrollConnection` 保留子列表垂直滚动。

服务进度小岛没有直接对应的官方业务组件：只复用相应底栏的采样开关和主体材质，保留自身的尺寸、边框、轻阴影及进度图标。iOS 同样保留项目既有的液态玻璃实现。这些路径不能表述为整体画面与官方 demo 完全相同。默认悬浮底栏的 `label` 用于无障碍描述，不绘制文字；普通底栏和 OS4 绘制标签，默认四项胶囊按原生尺寸限制为 252dp。

此前顶栏接入使用七项定向渲染与交互测试验证映射：玻璃大/小顶栏在浅/深色及渐进模糊开/关八种组合下与直接调用官方组件比较，另验证主题页滚动、关于页 hero、相机例外及普通侧栏稳定性。平板导航新增定向验证覆盖顶部标签与左侧面板切换、页面留白、iOS 宽屏保留、窗口缩放和二级页面返回；这类 Robolectric 渲染和交互检查不代表真机验证。

## 背景采样与迁移

玻璃弹层的筛选胶囊、选择卡、默认小按钮与充值预设金额共用 `GlassDialogButtonDefaults` 配色。输入提示、操作值、默认摘要和说明文字在弹层内部统一采用配套文字色；普通页面及调用方明确指定的语义色保留。全部调用位置和验证范围见[玻璃材质对比度检查](glass-contrast-audit.md)。

`AppAppearanceHost` 只记录最外层 Scaffold 的内容槽，记录包含页面及其栏，根弹窗宿主与遮罩位于记录范围外。顶底栏继续使用 `BarBlurHost` / `BarBackdropContent` 的独立记录，内容与栏是兄弟槽，栏不采样自己。页面内嵌玻璃标签只记录下面的底板，在兄弟层绘制玻璃；弹层中的玻璃标签使用排除弹层的页面采样。支付方式保留原标签底板，通过配套中性半透明填充避免突兀的整块白底。

无 backdrop 或着色器不可用时使用不透明回退。应用 `minSdk=34` 满足模糊与玻璃的 API 33 要求。对话框和抽屉只改变背景材质，不折射文字、付款码和输入内容。

玻璃顶栏渐进模糊关闭时使用原生渐变，不属于“不透明回退”，也不意味着关闭按钮的玻璃采样。原生组件仍按滚动条件决定何时绘制渐变和按钮底板。

### 顶栏返回顶部检查

全部业务顶栏按固定版本 `GlassPage` 接入当前滚动容器的实际重叠状态。之前付款等页面依赖顶栏累计的 `contentOffset`，列表回到顶部或程序滚动后累计值可能仍为负，导致按钮玻璃不收起。列表现在用 `canScrollBackward` 驱动官方 `isContentScrolled`，保留原生材质、动画和弹性回弹。背景带和阴影也使用列表位置；第一个条目离开后，视觉位移按原生 32/48dp 渐变范围饱和，不把无法取得的离屏累计高度当成准确距离。

| 检查范围 | 当前滚动依据 |
| --- | --- |
| 付款、大厅、我的 | 各页的实际列表；首屏失败与内容使用独立状态，退出中的列表不影响当前顶栏 |
| 账单主页面、带返回按钮的账单页，明细与统计的收入/支出 | 外层筛选区、统计类型区和当前显示的内层列表一起判断；停用手势不会把已有滚动误判为顶部 |
| 账单详情 | 详情列表 |
| 个人信息、账户详情 | 共用详情容器的实际列表，包括带刷新与不带刷新两条路径 |
| 应用设置、主题、引用项目、哈啰账户 | 页面容器持有同一个列表状态，同时传给顶栏与页面内容；主题页离开首条目后的视觉位移也已补齐 |
| 洗衣、接水、淋浴、慧生活798用水、哈啰骑行 | 当前设备阶段的实际列表；每个阶段保留自己的列表状态，退出动画不会替换当前顶栏的依据 |
| 关于 | 已按官方接入实际列表与 hero 位移，保留现有实现并回归验证 |
| 全屏相机扫一扫 | 官方透明普通小顶栏的明确例外，无可滚动内容，保持 `glassStyle = false` |

核对了所有业务 `AppTopAppBar`、`AppSmallTopAppBar`、`DashboardTopBar` 调用及设备共用容器的所有调用方，没有业务滚动顶栏继续使用累计位移作为玻璃显隐依据。定向回归覆盖付款按钮的浅/深色与模糊开/关、手势及程序返回、下拉刷新、设置/主题/引用项目的真实导航、详情保存恢复、设备阶段切换、账单两层滚动和关于页。渲染验证采用本机 Android 原生图形测试；当前未连接真机。

iOS 保留原胶囊布局、拖动选中、按压折射、重力高光和关闭模糊时的纯色回退。着色器与倾斜类型迁至 `miuix-material`；`Backdrop.drawBackdrop` 的降采样参数迁为 `Float`；高光迁为独立 `EdgeStroke` 绘制，重力状态仍在绘制阶段读取。

## 快照下载

仓库为 `https://maven.pkg.github.com/compose-miuix-ui/miuix`，仅处理 `top.yukonga.miuix.kmp` 组。经典 PAT 需要 `read:packages`。本机在已忽略的 `local.properties` 配置 `github.actor`、`github.token`；环境变量也支持 `MIUIX_PACKAGES_TOKEN`、`GITHUB_TOKEN` 和 `GH_TOKEN`。

APK 和编译资料工作流使用 `packages: read`，优先读取仓库的 `MIUIX_PACKAGES_TOKEN` secret，否则使用工作流令牌。若跨仓库包访问受到 GitHub 授权规则限制，应配置该 secret。版本固定到提交，升级需显式修改版本目录。

## 官方依据

- [设置状态与默认值](https://github.com/compose-miuix-ui/miuix/blob/3a980f7c/example/shared/src/commonMain/kotlin/AppState.kt)、[设置控件](https://github.com/compose-miuix-ui/miuix/blob/3a980f7c/example/shared/src/commonMain/kotlin/SettingsPage.kt)和[完整顶底栏及自适应侧栏路径](https://github.com/compose-miuix-ui/miuix/blob/3a980f7c/example/shared/src/commonMain/kotlin/AppContent.kt)
- [当前固定版本的完整示例应用 GlassPage](https://github.com/compose-miuix-ui/miuix/blob/3a980f7c/example/shared/src/commonMain/kotlin/GlassPage.kt)
- [顶栏渐进背景、渐变回退与按钮材质上下文](https://github.com/compose-miuix-ui/miuix/blob/3a980f7c/miuix-glass/src/commonMain/kotlin/top/yukonga/miuix/kmp/glass/GlassTopAppBar.kt)
- [关于页的完整示例](https://github.com/compose-miuix-ui/miuix/blob/3a980f7c/example/shared/src/commonMain/kotlin/AboutPage.kt)及[自适应顶栏实现](https://github.com/compose-miuix-ui/miuix/blob/3a980f7c/example/shared/src/commonMain/kotlin/utils/PageUtils.kt)
- [底栏默认颜色、尺寸与间距](https://github.com/compose-miuix-ui/miuix/blob/3a980f7c/miuix-glass/src/commonMain/kotlin/top/yukonga/miuix/kmp/glass/GlassNavigationBar.kt)
- [玻璃表面与背景采样](https://github.com/compose-miuix-ui/miuix/blob/3a980f7c/docs/zh_CN/guide/glass.md)
- [材质、描边与运行时能力](https://github.com/compose-miuix-ui/miuix/blob/3a980f7c/docs/zh_CN/guide/material.md)
- [玻璃侧栏与完整布局](https://github.com/compose-miuix-ui/miuix/blob/3a980f7c/docs/zh_CN/components/glass/glassnavigationrail.md)
- [玻璃底栏和独立操作](https://github.com/compose-miuix-ui/miuix/blob/3a980f7c/docs/zh_CN/components/glass/glassnavigationbar.md)
- [下拉菜单的显隐、值锚点与退出动画](https://github.com/compose-miuix-ui/miuix/blob/3a980f7c/docs/zh_CN/components/glass/glassdropdownpopup.md)
- [在原抽屉上应用玻璃表面](https://github.com/compose-miuix-ui/miuix/blob/3a980f7c/docs/zh_CN/components/glass/glassbottomsheet.md)
- [Pager 完整接管示例](https://github.com/compose-miuix-ui/miuix/blob/3a980f7c/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/utils/PagerGestureUtils.kt)
