# 哈啰账户与校园骑行卡

设置中的哈啰账号行进入详情页。上方展示「我的卡」，下方展示校园商品；底部按需展开普通单车商品。使用Miuix白底紧凑卡片，名称和使用说明直接显示在左，价格、购买或续费在右。认证状态位于校园小标题右侧，未认证说明与前往哈啰入口同行。小标题复用设置页SmallTitle及边距。

购卡、骑行付款、洗衣付款、卡片充值、生活缴费共用PaymentSheetPages抽屉容器：统一标题宿主、页面动画、退出期间禁用交互、滚动、安全区以及表单/结果边距。三个固定金额场景共用PaymentFixedAmount居中金额区域，付款方式和操作共用PaymentMethodActions，取消在左、付款在右。购卡名称作为次要文字，不再额外增加大标题。购卡取消、查询、成功均使用PaymentResultContent，取消回调结果没有独立布局。

「我的卡」分别查询单车与电动车的全部骑行卡，不按名称识别校园卡，也不接入主题卡商城。空列表为正常结果。学生认证、卡列表、商品查询的错误分别展示，保留官方返回原因。

首次读取我的卡、学生认证及校园商品时，分别显示共享骨架组件，不提前展示「暂无骑行卡」或「未认证」。各响应到达后通过共享ReadContent淡入淡出和高度过渡替换对应区域，包含空卡、无商品及查询错误；刷新已有数据时保留原卡片。我的卡、校园区、其他卡区及退出入口保持固定列表标识，避免内容变化后复用原来的位置。普通商品使用「展开其他骑行卡」Miuix按钮，点击后展开并首次查询，加载时显示商品骨架，再次点击收起；收起和展开采用统一150ms淡入淡出、200ms高度过渡，退出入口随区域高度连续移动。续费入口亦展开此区。

退出操作等待期间保留最后一次云端内容的画面，控制器清空不提前闪成空卡。账号退出完成后，账号入口和未登录提示过渡显示，同时重置列表及顶部标题的位置；不先保留过期的列表下标再等待内容缩短后回顶。过渡中已经离开的卡片和操作不暴露交互。

该界面调整通过5个直接相关场景验证：骨架独立替换与缓存刷新、账号卡片/认证/共享付款展示、延迟渠道返回、新增空卡过渡中间帧、长列表底部收起与退出。新增2项复测通过，检查空卡文字的中间亮度、区域中间高度、退出入口随收起移动及保留锚点、退出后列表和标题同步归零。日志分别为build/hellobike-campus-card/account-motion-validation.log和account-motion-recheck.log；前一次长列表测试因定位尚未组成的底部节点而失败，改为滚动列表定位后复测通过。未扩大至全量测试或Lint；这些是Compose本地行为验证，不是实机帧率测量。

## 接口与映射

| 功能 | 官方接口 | 处理 |
| --- | --- | --- |
| 学生认证判断 | `student.audit.info` | `authState=2 && userType=1` 才展示校园卡商品。0未认证、1认证中、2已认证、3认证失败；`userType=2` 为教职工。其他情况提示在哈啰处理，不提交认证材料。 |
| 我的卡 | `tw.ridecard.user.list` | `businessType=1/2`，`originTag=1`，`pageSize=2`；`pageStart` 从0开始，每次加1，按 `hasNext` 加载更多。`expireSecond` 为Unix秒。 |
| 校园商品 | `student.card.get.v3` | `needOptPromo=true`，读取 `cardInfoList[].packages[]`。名称、价格、说明、商品标识和促销对象均来自响应。 |
| 普通商品 | `tw.ridecard.commodity` | 展开后查询单车channelCodes=[101000]、filterCoBranded=true，读取packageGroups[].packages[]，无需学生认证。 |
| 预下单 | `tw.standard.preOrder` | 原样传递 `actualPrice`、`pendingInfos`；`packageGuid`、`channelCode`、`businessType` 取选中商品。单车校园卡资格最终由官方接口决定。 |
| 付款方式 | `com.alphapay.getPayChannel` | 收银台业务类型取预下单 `businessType`，场景取 `businessScene`；`waitPayGuid=payNo`、`orderId=paymentId`。渠道实际金额取 `realAmount`。 |
| 发起付款 | `com.alphapay.paymentOrder.pay` | 复用支付宝、哈啰余额、银联收银台。返回渠道决定支付桥；付款前保存实际金额与原付款标识。 |
| 确认付款 | `com.alphapay.facade.orderSingleQuery` | 查询原付款订单；整数 `payStatus=1` 才确认成功，随后刷新我的卡。SDK回调不能直接确认。 |

我的卡官方状态：0生效中、10待启用、40冻结中、41待激活、50/60/70失效。未知状态保留数值，不推断过期。日期显示由 `expireSecond * 1000` 转换。

商品列表保留各组返回的商品，不按名称筛选。校园新版H5的Q/H/R分支没有button.canClick或appLimit禁购条件，单车与助力车商品均可点击，资格交给官方预下单接口判断。普通商品只按官方canBuy>1禁购：2停止购卡、3卡数量上限，不能把1当成禁购。paymentLimit不是通用禁购字段。普通预下单传fromTwoWheel=true并保留原促销对象。说明中的marketingWords读取wordsValue列表，cardText数值不冒充使用说明。

paymentLimit=1使用官方连续包月路径：预下单后取paySignType、periodType、periodNum、maxDeductAmount构成periodRuleParams，signChannel=alipay经原生映射为102；设置creditModel=normal、actionOrigin=RidecardBuying，使用原订单金额及支付宝payV2。付款页明确显示自动续费签约，不以单次购买替代。其他paymentLimit不再整体拦截；它们的专属补贴、抖音或银联签约分支并未全部接入，不能因商品按钮可点击就宣称这些分支已经在线可用。

我的卡续费按钮按官方条件显示：status=0、hasBikePackage/hasEvBikePackage=true、canOperateType!=0。官方buyCard只通知业务类型并进入购卡页，不拿旧cardGuid直接续期。本应用单车续费展开普通商品并滚动至购买区，电动车续费进入哈啰。

## 原生收银台

购卡使用独立原生字段组：Android、原生SDK1.55.0、7.0.75及当前账号字段。getPayChannel的amount、clientStyleType为空字符串；支付宝安装状态通过alipays://platformapi/startApp检测，Manifest声明对应查询可见性。没有地区时按官方回退传空值。

enableSelect是默认选择的一个优先条件，不是禁付条件，也不是完整初选规则。此前仅检查w和手动点击，遗漏其调用方p的兜底；「全部false就必须手选」结论撤回。REA补查PayChannelDataTransfer.f/m/p/v/d：f从extraInfos.defaultChannelSetting取指定默认渠道；p依次尝试当前选择u、指定默认v、enableSelect优先w，最后d按列表顺序选第一个符合余额条件的渠道，包含子渠道处理。因此全部enableSelect=false且存在普通可用渠道时仍正常初选。本应用支持的单次付款渠道按指定默认→enableSelect→第一个可用渠道初选；初选不发起付款。分期、组合渠道等未接入路径不在此映射范围。

StandardCashierDialogHelper.t0/B0/A0/Q0表明普通手动选择不因enableSelect=false拦截，可选免密提示不强制阻止单次付款。余额不足独立处理；子渠道不会整组丢弃。如果所有已接入渠道都不可用，保持未选中，可选择查看原因但不能付款。共用HallNeutralTabs在未选中或选择失效时渲染无选中态，避免把-1传进Miuix的scrollToItem。这是本地延迟渠道复现场景的闪退原因，不是支付SDK报错。

原生付款保留StandardCashierRequest.payMoney/payList原金额，并另传marketingActivityId、marketingType、promoType等本次优惠信息。预下单6.90、渠道展示4.90时，界面及本机尝试记录为4.90，请求原订单金额仍为6.90。ConfirmPayHelper.b/c与PayCoreConfirmPayHelper.a提供此映射依据。原生查询按原付款标识及整数payStatus=1确认，不把businessId当paymentId，也不以原金额与优惠后显示金额不同否定成功；返回guid时核对它。

校园与学生接口使用HAR中的H5 7.0.65字段；我的卡使用HAR中的7.0.75、Android、platform=4字段。它们独立于骑行页的AppHelloMiniBrand字段组。位置有授权时取当前定位和官方行政区反查，否则使用已有地区或空字段。没有复制HAR中的token、ticket、riskControlData、个人认证资料；原生风控设备初始化尚未完整接入，服务端拒绝时展示原文。

## 订单恢复

账号详情页不展示根据本机恢复数据生成的订单卡片，不显示本机推断的「待付款」「已取消」云端状态。我的卡、到期时间和卡状态取官方卡列表；付款成功仅取原付款查询的整数payStatus=1。预下单返回的金额和付款标识用于当前收银台。

为防止进程退出后重复付费，仍在账户隔离的加密记录中保存原付款标识与请求尝试，仅供恢复时查单。成功/SDK取消标志不作为持久化结果；历史记录中的这两个标志恢复时也清除，重新读云端。点击购买遇到尚未确认的已发起付款会打开原订单查询，不另建订单。云端查询失败不能据此清除未知付款或再次发送付款。

「取消支付」在尚未发送付款时结束收银台并移除本机恢复内容，不请求注销云端预下单订单。支付宝6001/银联cancel只代表本次SDK付款取消；随后查询原单，成功结果优先于取消回调。查询未确认成功后可继续支付同一订单，或者取消本次付款操作。普通关闭手势仅关闭界面，未知付款保留查询。已核对的官方CashierDialogManager.b也只关闭收银台，没有远程取消订单接口；不能把关闭收银台标成「云端订单已取消」。

签约returnUrl/requestFromUrl使用本应用yktwx://hellobike-pay/paysign/回跳地址。回跳Activity只退出，不接受URI提供的成功状态；付款桥回到应用后查询原订单。官方SignConfigHelper也从当前宿主配置拼接/paysign/success与/paysign/fail。

预下单响应不完整或传输失败时，提示先在哈啰核对订单；没有可靠付款标识时不推断是否已创建订单。付款成功不等同于已验证发卡到账，页面通过我的卡重新查询结果展示。

## 静态依据与验证范围

HAR索引为从0开始：

- `骑行卡.har`：25校园商品、61学生认证、19预下单、18渠道、14付款。SHA-256：`614bf29695230d7425242293f22acfaa3d5325a00869e5ccf4a09061fcb01f43`。
- `我的卡.har`：7单车卡列表、6电动车卡列表、35普通商品。SHA-256：`f1a52a114e036a12b948cf110420fbc07cdfc18d2f184838791bed270da0f872`。
- [官方校园H5包](https://h5offline-cdn.hellobike.com/__offlinepkg/AppOhoOperationH5/pro/group_20261105213810.zip)：SHA-256 `7693948b587dfd7e8b468479a816b0f942dc6c324610b71cb451199c30e740b8`。`index-364a8384.js` 对应校园商品，`index-1fb7c591.js` 对应认证状态；`index-7248d423.js` 将 `payNo` 映射至guid，将 `paymentId` 映射至paymentId，`url-68f9ee97.js` 的收银台桥再将paymentId映射至payList.orderId，并将businessScene作为渠道查询scene。
- [官方我的卡H5包](https://h5offline-cdn.hellobike.com/__offlinepkg/AppHelloBikeMainH5/pro/group_20260918105050.zip)：SHA-256 `52e1f9efdf99c83bf4e1422941d3cbcfdde716932724710430089489665a9993`。`pro/7.0.45/26.9421bcab02.js` 确认双业务卡列表、状态、日期转换，以及 `bikePageStart+=1` / `evbikePageStart+=1` 的分页。此包内部版本与HAR线上的7.0.75不同，接口字段以HAR为依据。

- [官方普通购卡H5包](https://h5offline-cdn.hellobike.com/__offlinepkg/AppBikeRideCardH5/pro/group_20261102113721.zip)：SHA-256 a9b4bfdad2a57aa20fdb98a053481b6e467d80502e8b8f496e3d6187cc23386a。chunk-7615ee2a.c3745dc1.js的formatOrderParams及签约分支，chunk-2275a270.2beec6dd.js的预下单wrapper。
- [本次REA方法摘要](hellobike-account-return-rea.json)：完整方法体、精确原型、表示形式、fallback、证据ID。原APK SHA-256 7e7362c0099069334be458347e567b87eb9baf0ab0b1f4ea8a410246d3e7c62b；支付切片0a324bde7bfd7ec83955948edacc53f79287f80cd04f9c785de4cbacb91fc307，DEX逐字节保留，未执行APK。

完整接口清单、还车修复和验证范围见[接口复核记录](hellobike-interface-review.md)。静态与本地测试不能证明真实付款、发卡或实车结果。HAR原生支付报文为chaos加密，本应用尚未完整接入官方chaos和设备风控初始化；参数映射已核对不等同于复制了官方全部传输机制。未使用抓包凭据发送线上购卡或付款请求。

商品判断、连续包月、支付宝组件、关闭收银台及完整默认选择调用顺序的19个原生方法与校园H5完整源码见[购卡条件REA证据](hellobike-purchase-conditions-rea.json)。判断依据及仍未覆盖的范围见[条件核对表](hellobike-condition-review.md)。
