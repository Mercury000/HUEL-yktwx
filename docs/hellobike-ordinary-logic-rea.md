# 普通单车商品查询与列表逻辑的REA核对

本次仅核对中国大陆Android官方App的普通单车购卡入口。校园限定卡和学生中心商品不作为普通单车缺卡的依据；不以某次抓包的商品数量或名称判定商品范围。

REA检查原APK及逐字节保留原DEX的分析切片，追到以下调用链：

1. `BikeBuyCardHandler.f`处理`/bike/card/jump`，设置`channelCode=101000`、`adSource=app_wallet`，调用`H5Route.t`。
2. `H5Route.t`从`H5RoutePath.c`取购卡页面，追加`platform=0`；只有102000才改为助力车tab。
3. `H5RoutePath`的原DEX初始化指令构造`https://m.hellobike.com/AppBikeRideCardH5/latest/pr_index_cards.html#/cards`。
4. 获取此页面当前声明的脚本，并用REA静态分析相关方法。当前主脚本是`app.a238c291.js`，列表脚本是`chunk-7615ee2a.48bf080d.js`，不再使用此前保存的旧列表脚本推断当前App规则。

| 规则 | 官方当前代码 | 本项目 |
| --- | --- | --- |
| Android普通单车渠道 | ed08模块按平台/tab选择；platform=0、bike取8fc7模块的101000 | 101000 |
| 商品接口 | 3091模块commodity调用`tw.ridecard.commodity` | 同接口 |
| 请求筛选 | `getCommodity`传`needOptPromo=true`、`filterParams.filterCoBranded=true` | 同筛选；不是根据抓包名单新增的过滤 |
| 商品来源 | `handlePackageGroups`读取全部`packageGroups[].packages[]`，拼接成完整商品列表 | 读取所有单车组的所有packages；业务类型2按用户只需单车的范围排除 |
| 固定名单/数量 | 不按固定卡名或固定数量构造列表 | 没有卡名白名单，没有15款上限 |
| 展开 | `renderPage/getMoreCards`保留allCardList，exposureNum控制初次显示和展开 | 普通区域展开后显示全部返回商品；折叠位置不同不改变完整商品范围 |
| 禁购 | canBuy大于1用于禁购，保留卡片 | 保留卡片并禁购，不据此删除商品 |
| 连续包月 | 中国大陆列表保留paymentLimit=1；HK分支单独过滤 | 保留并进入已有签约路径 |
| 价格 | `formatCard`在缺少actualPrice时以purchasePrice构造finalPrice；actualPrice对象缺少finalPrice时默认0 | 本轮对齐该回退，并为后续预下单保留有效actualPrice对象及原优惠字段 |

以上确认的是普通单车的商品来源和列表筛选规则。没有把历史响应复制为运行时商品列表；未来服务器返回不同名称、不同数量的普通单车商品仍按同一路径处理。

价格回退是本轮找到并修正的兼容性差异。此前缺少actualPrice会使整次普通商品查询报错，不能把该错误解释成只展示抓包中的商品。

## 来源与覆盖

- 原APK SHA-256：`7e7362c0099069334be458347e567b87eb9baf0ab0b1f4ea8a410246d3e7c62b`。
- 原DEX切片身份及逐项哈希保存在`build/hellobike-card-list-review/provenance.json`和`build/hellobike-payment-slice-provenance.json`；未安装或运行APK。
- `BikeBuyCardHandler.f`的REA证据：`ev_76c8bd22e4db48ca5546aa9c0d72f46b6cc979dc0a7ba31f6c6ff54f1e9372d9`。
- `H5Route.t`：`ev_5df6a1425b08508b26f4c7ffce67305715de7cbbcc1334853e57c30fb934dbce`；`H5RoutePath.c`：`ev_c0b0d3c92f494de2d3a1cf60cdbeaf7d1d968aad062c12d979680802b39d310e`。
- `H5RoutePath.<clinit>`：`ev_9588532ee2098fb0f26a9cf0cc7b4b462ed9b84d2c6f1a6bf5685d47a8655341`，使用完整smali回退，保存于`build/hellobike-card-list-review/logic-rea/route_path_init.json`。
- 当前列表脚本SHA-256：`ef33d0047b2f6261d497eea46fd60e74f115e7fca3f7c5409195306e69d36421`；主脚本SHA-256：`f12d1c897a4f3a1b19b264a2c3b39a2c57871589679ac33f57fa554ea91fdb93`。URL、全部脚本哈希、原文件和精确方法范围保存在`build/hellobike-card-list-review/current-official/`及`current-source-ranges.json`。
- REA所选当前方法与模块分析：`ev_1e4da67cec5c9e77233579e48a209e6ca05c357870f4250cbcbe7f03f6d0d221`。只选当前页面script标签加载的模块，不混用预取的其他页面同名模块；原方法和模块范围分别保存在`current-source-ranges.json`、`module-source-ranges.json`。原范围逐字复制到静态检查模块，外部调用仍有未知关系；通用语义图不是运行证明或全App等价证明。本表的规则依据相应方法完整分支核对，不从图中的未知或缺失节点推断不存在其他行为。
- 核对范围不包含官方全部设备风控初始化和支付分支，也不证明特定账户某时刻的实时商品数量。

## 定向验证

新增场景返回23款任意名称普通单车商品、额外停售商品和助力车组，验证所有单车商品均保留，exposureNum=4不截断列表，助力车按范围排除，价格回退和优惠信息保留正确。另复核现有普通商品促销、预下单和签约路径测试。两个直接相关方法均通过；未追加全量测试、构建或Lint。日志保存在`build/hellobike-card-list-review/ordinary-logic-tests.log`。

## 缺少定位时只显示少量普通卡

2026-11-15，连接手机检查发现本应用的精确和粗略定位权限均未授予，用户随后确认缺少定位。此前账户页吞掉定位失败，仍然以空坐标和已有地区（如有）查询商品，也没有在其他卡区域提供定位授权入口。本次没有取得手机当时的实时商品响应，不能据此确定服务端实际返回的商品数量。

另修正账户页的请求时序：展开普通卡会等待当前定位和地区解析结束；刷新会取消上一轮普通卡请求并使其结果失效，较晚返回的旧结果不能覆盖新位置下的列表。定位失败时继续保留官方允许的空位置查询，通过项目已有的内联错误组件显示原因，在提示条右侧用项目已有的`DashboardLink`提供“开启定位”或“重新定位”；长文案在左侧换行，操作包含在同一提示容器内。账户页通过既有系统权限流程授权，授权成功后刷新定位与普通商品；定位恢复后提示移除。

四个控制器测试和三个ViewModel测试通过，覆盖定位等待、旧响应丢弃、缺少权限时的查询与授权入口、授权后重查、拒绝授权及已有扫码授权路径。日志保存在`build/hellobike-card-list-review/ordinary-location-tests.log`。本轮未更改手机权限，未安装新APK。

另通过两个直接相关的UI测试，检查定位提示与商品卡同时显示、定位中操作禁用、权限提示操作切换、提示恢复后移除，以及既有其他卡展开和加载状态。提示使用项目统一错误色、图标、圆角和间距；重试为提示条右侧的12sp文字操作，使用项目既有按压反馈和至少32dp的交互高度。截图为测试数据；最终浅色和深色截图为`build/hellobike-card-list-review/ordinary-location-inline-light.png`及`ordinary-location-inline-dark.png`。仅对同一UI测试追加文案与操作水平布局的检查。未追加其他测试组、APK构建或Lint。

后续将定位恢复接入共享`InlineRetryFeedback`，检查期间在同一提示条内显示进度；普通拒绝允许再次申请，系统不再弹授权框时前往设置，返回后继续刷新当前卡列表。共享提示自身负责单行／多行的高度动画，并在动画期间使`ReadLayout`跳过外层尺寸动画。此规则也用于各扫码设备页，具体行为和定向验证见[扫码设备反馈记录](device-scan-feedback.md)。
