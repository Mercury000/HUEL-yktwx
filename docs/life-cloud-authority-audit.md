# 哈啰与胖乖：云端判断、官方提示核验

依据哈啰7.0.75与胖乖生活1.145.0官方客户端核对业务判断及提示。业务限制须有官方客户端对应判断，否则交由接口判断；已有官方提示完整保留。核验未执行登录、设备控制、下单或支付。

## 输入识别与官方提示

10位数字是统一手填入口识别哈啰车号的格式规则，进入骑行页不代表设备存在或可以开锁。`user.ride.pre.ride` 确实是官方预检接口，原生 `PreRideCreateApiModel` 和公开官方H5都有调用。预检通过条件为 `result=true OR causeType=1100`；拒绝原因读取原生cause、causeContent及H5的causeDesc。缺少原因不解释成“未通过校验”。

失败时优先展示原始官方原因；标题、正文、URL、设备／订单编号、换行和长文本不截断、不脱敏替换。确实没有原因时，显示“服务端未提供原因”及接口／返回码，不自行解释成车辆、地区、余额或登录异常。输入路由的10位格式规则保持为入口识别，设备许可由预检／创建接口决定。

哈啰和胖乖登录、设备、支付选择及付款查询错误在共用InlineError中启用原文模式，保留换行、完整换行排版。共用组件通过可选参数提供此行为；其他调用仍使用既有默认。界面验证直接检查完整TextLayoutResult，确保原字符串一致、多行且没有视觉省略。

## 可复核的证据链

完整归档：[life-cloud-authority-rea-evidence.json](life-cloud-authority-rea-evidence.json)。其中包含80条REA记录，保留原始引擎响应、完整方法体或smali、DEX描述符、Evidence ID、引擎事实、覆盖限制及输入哈希。`evidence_ids_by_task` 将下文短名称对应到完整ID；文末列出逐条索引。

| 输入APK | SHA-256 |
| --- | --- |
| `D:/mercu/Downloads/哈啰_7.0.75.apk` | `7e7362c0099069334be458347e567b87eb9baf0ab0b1f4ea8a410246d3e7c62b` |
| `D:/mercu/Downloads/胖乖生活_1.145.0.apk` | `b0de8fdc4fe0f778b7b9134347b4bb427b73a67fdf223607fec4d9ecdc8bef18` |

哈啰整包REA因Java堆不足未完成，改用原始classes18、classes8、classes7的APK分析切片。胖乖使用原始classes4、classes11、classes12的分析切片。独立重新打开两份原APK，将7份切片的每个ZIP条目与原APK逐字节比较，全部一致；记录原APK、切片及各DEX／manifest／resources的哈希。切片仅用于静态分析，不是修改后的可安装客户端。不同DEX之间的引用查询有边界；“未发现调用”不等于证明整个客户端绝不存在其他路径。

哈啰预检链条：原始DEX → `PreRideCreateApiModel.<init>`（`hello-pre-fields`，action=`user.ride.pre.ride`）→ `preRideCreateCheck$3.invokeSuspend`（`hello-pre-response`）→ `BikePreRideCreateAction.S/R/z0` → result模型cause/causeContent/popup/notices → 当前 `HellobikeApi.prepare` → `HellobikePrecheck.notice`／异常 → 设备页。核心S方法ID为 `ev_1a734e09efced7e11214ce3dde39b6686bf174b17672e040e1be9dd83c185f18`。

胖乖链条：原APK classes11.dex的Retrofit注解 → `ApiService` 返回类型／方法清单 → VM调用 → Activity／Fragment观察器 → 当前API／ViewModel。`original_panggui_api_annotations` 保存注解偏移、方法索引、原DEX哈希，不把抽象接口没有方法体的情况说成已反编译出业务实现。例如msgSync的 `machine/msg/sync` 返回MsgSyncBean，orderSync的 `order/sync` 返回OrderSync；结束接口 `goods/endWater`、`shower/goods/remoteEnd` 返回Boolean；健康检查 `trade/check_device_status` 返回HealthCheckBean。

官方公开H5只补充明确对应的协议消费者证据。归档保留URL、HTTP状态、原始／解压哈希、无凭据发送记录，以及UTF-8字节偏移和摘录。H5版本7.0.70与APK7.0.75不同，不把H5全部行为说成原生实现。P10使用 `chunk-common.56918c.js` 的枚举及isInP10StandardArea／handleSuccess分支；预检参数使用 `chunk-api.aba0c6.js`。原始获取记录已在[协议补查归档](hellobike-protocol-audit.json)中保存。

## 哈啰逐项处理

| 判断／提示 | 官方观察 | 修复后的处理及证据短名 |
| --- | --- | --- |
| 通用失败 | HiResponse.code=0成功，ApiObserver转发code/msg/data | 外层msg/resultMessage保留；缺码也保留已有消息，缺原因只报接口和码。`hello-envelope-success/failure` |
| 预检通过 | S普通路径：result为true或causeType为1100 | 改正AND；missBike和缺失bikeType不追加拒绝。`hello-pre-handle`、`hello-missbike-refs`、`hello-biketype-refs` |
| 原生预检提示 | cause/causeContent、popup.title/subTitle、notices.warnTitle/warnContent分别用于Toast／弹窗 | 完整组合显示，普通提示留在确认开锁之前；不只读causeDesc。`hello-pre-result/popup/notice`、`hello-pre-handle/notice-display` |
| 官方特殊交互 | S前置分支1130/1136、1133、1225、1226且nearBikeInfo存在；R的1215及人脸流程；推荐弹窗含1427 | 已确认的特殊流程保留官方文字并引导官方处理，表示未接入该交互，不伪装成云端拒绝；1226缺nearBikeInfo不额外拦截。`hello-pre-handle/pre-ready` |
| 通知分支 | z0对1225、1226且nearBikeInfo、1234进入专用处理；其他通知有明确继续／取消路径，1213/1128还有原生会话／配置条件 | 专用流程按已证实条件识别；普通可确认通知显示原文，不虚构原生会话／远程配置状态来强制拒绝。`hello-notice-display` |
| 蓝牙提示 | netBluetoothModel本身不足以强制蓝牙；类型1无强制、2可选、3强制，原生还依赖蓝牙权限／开关和会话配置 | 当前网络适配未接入BLE，明确netBluetoothModel且type3、或cause1421转官方；类型1/2不一律拒绝。不模拟原生本机超时配置。`hello-bluetooth-gate/types`、`hello-pre-handle` |
| 已有骑行 | 本次pre/create消费者没有本应用额外的checkRide前置拒绝 | 扫新车与明确创建不再按本机活动记录数或额外checkRide结果拦截；checkRide仍用于账户核验／原订单恢复。`hello-pre-response/pre-handle/create-handle` |
| 计费规则缺失 | 未证实priceRule必须非空或小于16KB才能开锁 | 缺失时仍可确认开锁；有内容显示原文。该字段不充当业务许可。官方依据不足，删除限制。 |
| 本机时距与精度 | 未取得本应用30/60秒、50/80米、误差80米及模拟位置一票否决的对应官方分支 | 移除业务门槛；请求系统当前位置、检查坐标及数据结构，将地区／位置发送云端。未把不存在的证据写成“官方没有任何定位规则”。 |
| 登录状态与凭据 | LoginCheckImpl.Ea先完成登录，再广播unavailableMobile；未找到needRebindMobile拒绝依据 | 不按这两个字段或自定token/guid最短长度拒绝成功登录。必需的非空身份字段保留结构检查。`hello-mobile-gate` |
| 验证码分支 | sendCodeV3先处理captchaType=1；checkSwipeCaptcha要求ok，失败转外层msg | 按官方优先级处理，失败提示原路保留。`hello-sms-handle/slider-handle` |
| 登录失效 | 原生仅103、-10003；不是HTTP状态或文字关键词 | 沿用官方码；账户标识不匹配只标未验证，不擅自清凭据。官方错误传到账号设置与登录页。另见[失效证据](life-login-expiry-evidence.md)。 |
| 创建失败与开锁等待 | BikeRideCreateAction.x读result.result及cause | 原始创建原因保留；原订单查询显示官方detail，不自写“锁环被卡”原因。创建后不重放，匹配订单的实际进度可以覆盖已被证实过时的拒绝。`hello-create-handle/create-error` |
| P10标准还车 | H5按1102/1103确认、1101/1108轮询、1100完成；不附加penaltyFree=1 | 不按penaltyDisplay、蓝牙key、费用字段出现追加拒绝；明确非网络type仍是控制方式未接入。定位等待及关锁提交失败保留causeDesc，提交失败不再被静默丢弃。H5补证及`hello-p10-model/p10-bluetooth` |
| 余额检查 | PayTypeBean.isBalanceEnough：显式balanceEnough优先，缺失则accountBalance（默认0）与amount比较 | 使用同一回退逻辑及官方balanceText，不因缺少balanceEnough直接拒绝。`hello-balance-gate/balance-channel/pay-gates/pay-prompt` |
| 绑卡／余额引导 | BalanceGuideAbility仅全余额013；绑卡引导还依赖原生PBBalanceService存在 | 不把这些标记套到普通001/003/1201。`hello-balance-guide-verified/binding-guide-verified/pay-enum` |
| 签约 | supported、signState、实际agreementChannelCode、button/action等字段组合；不同辅助方法含switchStates | 按对应字段组合识别真实未接入签约，缺失引导字段不臆断；完整保留官方说明。`hello-agreement-a/d/i` |
| 金额及业务 | 保留应付金额、付款标识、订单关联用于准确付款 | 取消零金额、缺失bizType和推测“额外费用”的拒绝；明确其他业务仍按实际未接入能力处理。完整性检查不宣称是官方业务限制。 |

## 胖乖逐项处理

| 判断／提示 | 官方观察 | 修复后的处理及证据短名 |
| --- | --- | --- |
| 响应成功 | ApiResponse.isSucces只接受code=0，getResponseMsg直接返回msg | 不接受自定code200成功；包括HTTP错误中的JSON msg也保留，缺原因报告path/code/HTTP。`pg-success/message` |
| 登录失效 | ResultStateKt.paresResult的10/126显示服务端msg并清登录 | 保留msg，不替换成“胖乖登录已失效”；账户手机号不匹配不标登录过期。`pg-errors`及[失效证据](life-login-expiry-evidence.md) |
| 设备故障 | LockingAct非空deviceErrorMsg先进入故障流程；4204蓝牙且非before为离线蓝牙例外 | 在加载程序／渠道前展示原文；不被后续本应用能力错误掩盖；保留官方离线例外。官方若干已有码会使用固定映射，本项目按用户要求优先保留实际deviceErrorMsg。`pg-device-error` |
| 洗衣健康检查 | PayAct lambda19仅health=false拒绝，显示faultDesc；null或空msgId直接就绪 | 优先faultDesc／原始字段／外层msg；无原因仅报告health=false；不自己推断“设备工作中”。`pg-health-error` |
| 洗衣程序 | WashSkuAct lambda38读取scanAvailable；原Sku模型没有stockState判断 | 删除stockState=1附加条件；scanAvailable=false保留官方“该洗涤程序暂时无法使用，请更换其他程序使用”。`pg-sku-check/model`及原DEX调用索引 |
| 水口可用性 | 此scanAvailable选择分支针对洗衣程序；未找到水口的同等限制 | 水／淋浴不套用洗衣字段，实际请求交接口判断；返回水口ID仍须匹配用户选择。没有把负面的静态搜索扩大成绝对不存在。 |
| 启动消息 | DeviceStartupAct lambda36、StartStatusAct lambda12：state忽略大小写等于OK，或者code="05"即成功；其他组合继续轮询 | 按官方OR条件处理成功；待同步继续轮询，不猜测数字码含义，不重发启动。`pg-msg-handle/msg-shower-handle` |
| 结束能力 | 设备详情supportAppUserFinish决定官方结束按钮 | 保留明确false限制，缺失不擅自判false；结束前读取最新详情。`pg-finish-support` |
| 结束Boolean | waterEnd/showerEnd → afterEndData → createObserver lambda40：null/false进入finishFail | 保留已证实的Boolean判断；若响应有原文先显示原文，没有才说明data值和未提供原因。官方固定“结束失败／在设备长按‘结束’…”作为有依据的官方界面行为归档，不自行写设备故障原因。`pg-water-end/shower-end/device-create-observer/device-end-observer40/end-failure` |
| 后付订单同步 | PayStateAct lambda14：内部code="0"确认；其他码递减轮询 | 非0保留原文或“订单尚未确认”及码，保持未确认，允许后续查单；不当成永久“订单处理失败”。`pg-order-handle/order-sync-model` |
| 洗衣支付渠道 | 原PayChannelRoute为canChecked、remark、needOpenFlag；PayAct.onItemClick对canChecked=false拒绝 | 用canChecked，忽略旧useStatus作为门槛；保留remark，缺失时使用已证实的官方“余额不足”。needsOpen表示未接入的开通流程，先显示remark。`pg-channel-model-correct/channel-predicate/pay-adapter/payment-adapter` |
| 水后付渠道 | 1.145.0的开通引导为method1501+thirdMethod1506+needOpenFlag；不等于当前已接入旧method15 | 不将该特殊规则泛化到method15，也不以canChecked自行拦水启动；现有selectAfterPay／unlock由云端判断。渠道原始remark仍展示。`pg-afterpay-gate/afterpay-open-predicate` |
| 淋浴status6 | DeviceStartupAct lambda38明确使用“设备异常，请更换设备使用”；StartStatusAct lambda13结束页面 | 此固定提示有官方来源，保留该故障判断，不从其他未知状态猜故障。status3/5及消费字段用于结束／后付处理。`pg-water-status/shower-status-candidate` |

创建、预支付、支付、取消和查询共用响应外层解析；现有订单状态、退款／关闭、SDK取消与未知结果的对应原生分支见[订单异常核对](life-order-errors.md)。不增加本机付款期限、活动订单数量、优惠字段或金额变化的业务拒绝。明确不匹配的设备／程序／订单标识、不可解析金额、重复提交互斥和SDK回调关联属于本应用数据完整性保护，继续保留。

## 覆盖与限制

审阅范围为两平台当前API、模型、设备ViewModel、登录核验及直接显示官方原因的设备／账号页面。已接入网络流程之外，BLE／短信控制、复杂官方认证、签约、其他设备／付款业务仍有实际接入缺口；提示应区分缺口与服务端许可。对原生会话状态、远程配置、其他APK版本及动态加载代码，不虚构同等运行环境或绝对的“无判断”结论。

REA的source.status=complete表示本次方法提取完整，仍可能有反编译告警；smali回退单独标明。原包分析失败、失败的类名／重载查找、成功的别名重试、引用范围都保留在归档中。没有安装或运行这两份APK，没有调用真实业务接口，不能据此宣称已完成真实骑行、洗衣或付款。

累计102项直接相关检查通过，无失败或跳过，覆盖最终API、设备流程、模型、账户核验及原文显示；各次结果按测试身份去重，已经修复的中间失败也记录其原因。定向验证结果及本次源码身份另见[验证记录](life-cloud-authority-validation.json)。不运行全量项目测试、单独APK构建或Lint。历史评估中的60秒、80米、code301、penaltyFree=1、stockState／useStatus建议已在现行文档更正，历史原始证据不篡改。

## REA原始记录索引

完整原文、DEX描述符、参数、source.status、表示形式、引擎及限制均在JSON归档中，以下索引可直接按Evidence ID定位。类清单和引用查询不具有方法体的complete含义。

| 任务 | 方法／查询 | 表示形式与覆盖 | Evidence ID |
| --- | --- | --- | --- |
| hello-agreement-a | `com.hellobike.allpay.base.ext.PayTypeBeanExtKt.a` | java/complete | `ev_1a182ed102e627b3d656ddf67c2d8c9eba9e22958f7107d740e8432b26881137` |
| hello-agreement-d | `com.hellobike.allpay.base.ext.PayTypeBeanExtKt.d` | java/complete | `ev_f4dd606ac27ddf03ec3c0a64ec45633b22f966fbd5670e78b87de1981dd9e9f2` |
| hello-agreement-i | `com.hellobike.allpay.base.ext.PayTypeBeanExtKt.i` | java/complete | `ev_fc16d06cfb1b087847841a382f26ef85495655754631867d7d554a2491fcf21e` |
| hello-balance-channel | `com.hellobike.allpay.paycomponent.model.entity.PayTypeBean.shouldCheckBalanceEnough` | java/complete | `ev_f1f002af6fe539b032a282310851e26e5d1ba8dd3650bee15ae889b12d013101` |
| hello-balance-gate | `com.hellobike.allpay.paycomponent.model.entity.PayTypeBean.isBalanceEnough` | java/complete | `ev_dc02cfe109b7528c85912435dec4edd6fe4e10f0d7959acd3d913faaedeeeae2` |
| hello-balance-guide-verified | `com.hellobike.allpay.base.ability.BalanceGuideAbility.DefaultImpls.d` | java/complete | `ev_516b176b51405d5cea87526a418bcca854a72416acd3fd86e94178cf35025b2f` |
| hello-biketype-refs | `com.hellobike.bike.business.openlock.ridecreate.model.entity.PreRideCreateResult` | 清单／引用/按记录coverage | `ev_fc321838b4ae6d54d3879644f4568135ba21297b3d0017fa9ff851478b4d1d47` |
| hello-binding-guide-verified | `com.hellobike.allpay.base.ability.BalanceGuideAbility.DefaultImpls.e` | java/complete | `ev_0de935430dd76c8019ec02e6ea3f4b36dd6268acaee16b7eafce55d12945246b` |
| hello-bluetooth-gate | `com.hellobike.bike.business.openlock.TipsOpenBluetoothHelper.a` | java/complete | `ev_0beb8e558091a0de936573e9279b7c4d154658fe6529197063ee980cd6ab85f4` |
| hello-bluetooth-types | `com.hellobike.bike.business.openlock.TipsOpenBluetoothHelper.TipsType.<clinit>` | smali/complete | `ev_6dea6274c35bbd8efa3b9ab50afd998d9e611ca9eee2f689e610763f2052acd3` |
| hello-create-action | `com.hellobike.bike.business.openlock.ridecreate.BikeRideCreateAction` | 清单／引用/按记录coverage | `ev_63effbd3b2d619cc57e6c4203d85915f3e72411c9a6f59ff4bd202ca11f0f264` |
| hello-create-error | `com.hellobike.bike.business.openlock.ridecreate.BikeRideCreateAction.y` | java/complete | `ev_de43daa803a146d027f0642b87bc2fe0fa4f86a11487c7c635921270181838ce` |
| hello-create-handle | `com.hellobike.bike.business.openlock.ridecreate.BikeRideCreateAction.x` | java/complete | `ev_da46929b67c3b7cfef4c283ffeaeca5fec4982ac9cc2bddedc819e6f7cdec99d` |
| hello-create-result | `com.hellobike.bike.business.openlock.ridecreate.model.entity.RideCreateResult` | 清单／引用/按记录coverage | `ev_0c9f2216eec4a85100c55376739f43a01744667d9d20a918d82ba9c903883ab9` |
| hello-envelope-failure | `com.hellobike.networking.http.core.callback.ApiObserver.innerOnApiFailed` | java/complete | `ev_f701b36b9bc9a2804902f0f16694e86fd95f5ed19940bf03551e7e76a7659769` |
| hello-envelope-success | `com.hellobike.networking.http.core.HiResponse.isSuccess` | java/complete | `ev_2bdccc0650261f84c18c809cb522413162c110c340f96bba88357987d6b25cc6` |
| hello-guide-inner-methods | `com.hellobike.allpay.base.ability.BalanceGuideAbility.DefaultImpls` | 清单／引用/按记录coverage | `ev_14218bb6cee3018df23c92b61f80d95831d33a3dd737141e19b760ee7f434d68` |
| hello-guide-names | `类名称查询` | 清单／引用/按记录coverage | `ev_a430a1e627517102a40894b3a76e5fd81019ad84be30a4529475441e6665b56c` |
| hello-guide-parent | `com.hellobike.allpay.base.ability.BalanceGuideAbility` | 清单／引用/按记录coverage | `ev_35f117673bbf38ae1f0dd2ce2f829ecf1ad90934d27d59bf699a3c1365d9c8f5` |
| hello-missbike-refs | `com.hellobike.bike.business.openlock.ridecreate.model.entity.PreRideCreateResult` | 清单／引用/按记录coverage | `ev_d04e8be27104ced96eb98e17e139e38e8f414ca9425426606fcf9039daa59640` |
| hello-mobile-gate | `com.hellobike.userbundle.business.login.check.LoginCheckImpl.Ea` | java/complete | `ev_4f85f813dbb5a79e30eb9702118615b16fc50b12c8ceeb9a386c24c16fb84045` |
| hello-notice | `com.hellobike.bike.business.openlock.ridecreate.model.entity.PreRideCreateNotice` | 清单／引用/按记录coverage | `ev_8bc7c776fa3e7af4a0418f4e9acd72f23825ca71cc269de5272ce794f8aabf75` |
| hello-notice-display | `com.hellobike.bike.business.openlock.ridecreate.BikePreRideCreateAction.z0` | java/complete | `ev_bc19b9a51ca809b02726550a270a70e0810b121f10fac2c4f988aaf9168f21f2` |
| hello-p10-bluetooth | `com.hellobike.bike.business.riding.rideinfo.model.entity.PreCloseForP10Entity.hasBluetoothCommand` | java/complete | `ev_d4ef39d5426ac718e162eac725e8a8be7db04ad361a850a9196694de4f7f13e7` |
| hello-p10-model | `com.hellobike.bike.business.riding.rideinfo.model.entity.PreCloseForP10Entity` | 清单／引用/按记录coverage | `ev_ab3c393e278edcb4960af52da574f585e1bb829d6d4385d08d3649a625e871db` |
| hello-pay-enum | `com.hellobike.allpay.paycomponent.model.entity.PayTypeEnum.<clinit>` | smali/complete | `ev_a43c2e92dcf2ba423846b7aa1fff94bd6836646739123e2645b2ff0534faa513` |
| hello-pay-gates | `com.hellobike.allpay.paycomponent.PayChannelDataTransfer.w` | java/complete | `ev_b465211e88c459d61c5bf704e33abfe7b2ee529d2660b2a3ef1e83feba8f1e8a` |
| hello-pay-prompt | `com.hellobike.allpay.paycomponent.adapter.HelloPayTypeAdapter.e` | smali/complete | `ev_063b116db243c95f9f1284241742aa779e477fd930ece77c8e35cea6346c5d17` |
| hello-popup | `com.hellobike.bike.business.openlock.ridecreate.model.entity.Popup` | 清单／引用/按记录coverage | `ev_f585e39b65cd817fc1d2104b19d4428c9ab5f55d37f952ea6751ebc368a28f61` |
| hello-pre-action | `com.hellobike.bike.business.openlock.ridecreate.BikePreRideCreateAction` | 清单／引用/按记录coverage | `ev_605028db3c00cb7f5b25aaaa7f5d861a6d492d29a1c26aafcf35f588c9b6d3db` |
| hello-pre-cause | `com.hellobike.bike.business.openlock.ridecreate.BikePreRideCreateAction.U` | java/complete | `ev_bf04b747165f3b5b47a20a7acaab336afe5e9249ca806dae0631d017f6d3bd31` |
| hello-pre-fields | `com.hellobike.bike.business.openlock.ridecreate.model.api.PreRideCreateApiModel.<init>` | java/complete | `ev_1a601cb63fa35dec4c54a2cd88eeecb84d4d3b54e2b4eedfda3bdf76167f9795` |
| hello-pre-handle | `com.hellobike.bike.business.openlock.ridecreate.BikePreRideCreateAction.S` | java/complete | `ev_1a734e09efced7e11214ce3dde39b6686bf174b17672e040e1be9dd83c185f18` |
| hello-pre-model | `com.hellobike.bike.business.openlock.ridecreate.model.api.PreRideCreateApiModel` | 清单／引用/按记录coverage | `ev_97b2fe5b2259442da2293f7a4053d06c598ef0b0726c41c8dbdeec91f242079b` |
| hello-pre-ready | `com.hellobike.bike.business.openlock.ridecreate.BikePreRideCreateAction.R` | java/complete | `ev_1b974254d550629c7741b0276e7be70727e7ee522daf0fca9f368e637f295e65` |
| hello-pre-response | `com.hellobike.bike.business.openlock.ridecreate.BikePreRideCreateAction$preRideCreateCheck$3.invokeSuspend` | java/complete | `ev_5533e35f9d2772c995fa3b52822dc5034669f1f10bf88906e730b874a48238ef` |
| hello-pre-result | `com.hellobike.bike.business.openlock.ridecreate.model.entity.PreRideCreateResult` | 清单／引用/按记录coverage | `ev_d73892aca67b8682c4c3fa04d381560f7b5153a0764d26801a68b109af6ece52` |
| hello-result-refs | `com.hellobike.bike.business.openlock.ridecreate.model.entity.PreRideCreateResult` | 清单／引用/按记录coverage | `ev_1efb483c96710092036d894a99ca8672ea93237319ee3f185a6ee2d6d06acabb` |
| hello-slider-handle | `com.hellobike.userbundle.business.login.swipecaptcha.SwipeCaptchaManager$checkSwipeCaptcha$1.invokeSuspend` | java/complete | `ev_429eebb859957174020e0ac5e6167fc530b6b02c080c20b78d82fb2be0230021` |
| hello-sms-handle | `com.hellobike.userbundle.business.login.swipecaptcha.SwipeCaptchaManager$sendCodeV3$1.invokeSuspend` | java/complete | `ev_b7243adae8cc74c993248833efa3a31971534673e61f7a117d0e73dd491164fc` |
| hello-sms-methods | `com.hellobike.userbundle.business.login.model.entity.SendCodeV3ResultInfo` | 清单／引用/按记录coverage | `ev_78147eb8a42ec55cb7a8596f1ee9d480c65c74095f0ee887aa5d46ec96753f20` |
| hello-sms-model | `com.hellobike.userbundle.business.login.model.entity.SendCodeV3ResultInfo` | 清单／引用/按记录coverage | `ev_58a209718af1ca26a50ed052f79b5a97a86f7287d37c9be9bbeb066f226c68a5` |
| hello-sms-refs | `com.hellobike.userbundle.business.login.model.entity.SendCodeV3ResultInfo` | 清单／引用/按记录coverage | `ev_91487d35fb5ef68db923fecd8781a9900081e9e8bff0b1a77a9efde83e2e2a09` |
| pg-afterpay-gate | `com.qiekj.user.ui.activity.scan.AfterPayUseAct.useBtnClick` | java/complete | `ev_2d2b147d58d0f9965dacaddab3c1b366bcb2c2a0da4c881100b1c5307dd9b3e1` |
| pg-afterpay-open-predicate | `com.qiekj.user.ui.activity.scan.AfterPayUseAct.initView$lambda$17` | java/complete | `ev_db11aa22afeee470b2726ea217408055c43e26baf609085e21f952d939bd1d42` |
| pg-api-methods | `com.qiekj.user.http.ApiService` | 清单／引用/按记录coverage | `ev_258cd450e64d880025dee0fdc41d8cdcb8be7e38f639ca7c4de553b051e2dca1` |
| pg-channel-model-correct | `com.qiekj.user.entity.PayChannelRoute` | 清单／引用/按记录coverage | `ev_ea6077e7c4a03cbe5d3907d7b81ff6970d77b13ecea710dd29cae98079758a40` |
| pg-channel-predicate | `com.qiekj.user.ui.activity.pay.PayAct.onItemClick` | java/complete | `ev_a3fd1707681a80e61e5b45c7fc1dd23963598e841f5b02a78ef5c6dfb2009ff0` |
| pg-device-create-observer | `com.qiekj.user.ui.activity.scan.DeviceStartupAct.createObserver` | java/complete | `ev_088471cacb8b3435fc341d81fdd3e2e0e31a2a8cc058bc260bc3c498c3be49c3` |
| pg-device-end-observer40 | `com.qiekj.user.ui.activity.scan.DeviceStartupAct.createObserver$lambda$40` | java/complete | `ev_c6d13b5b546f4df6bfe78c22f9aa6e04767b8b4f078ecf2a977174daadbe0b06` |
| pg-device-end-observer42 | `com.qiekj.user.ui.activity.scan.DeviceStartupAct.createObserver$lambda$42` | java/complete | `ev_98253ebf3f6c565c7101bcfbf876f1a7c26b9fb62a66dc2f58b1c4112dfc3cee` |
| pg-device-error | `com.qiekj.user.ui.activity.scan.LockingAct.createObserver$lambda$3` | java/complete | `ev_276512a8c7b9e85af350020fe2a0140fe2ca18d6a6085f3ac251781ea07ef931` |
| pg-device-observers | `com.qiekj.user.ui.activity.scan.DeviceStartupAct` | 清单／引用/按记录coverage | `ev_3d7fee15f26aacd607c0c570896e36cb951377560df2ec86781767f524f95b9a` |
| pg-end-failure | `com.qiekj.user.ui.activity.scan.DeviceStartupAct.finishFail` | java/complete | `ev_0c5f2aa5f1e7015cd9f3e48270aa04c7a8a3ee8bd06eefe5a58ddac65ea81cdc` |
| pg-errors | `me.qiekj.jetpackmvvm.state.ResultStateKt.paresResult` | java/complete | `ev_3a9c8d2aebc1a423334bde079307d6520913d433dc6116a9036cfa6e08fb7a4f` |
| pg-finish-support | `com.qiekj.user.ui.activity.scan.DeviceStartupAct.createObserver$lambda$32` | java/complete | `ev_1b55252ec889c4b442c8d4d6705dc25485b7879e3a98386317ed73b149a2fffd` |
| pg-health-error | `com.qiekj.user.ui.activity.pay.PayAct.createObserver$lambda$19` | java/complete | `ev_91d754ee64bbbc477df16b4cbfc36a901d6825ef61499b22352b90c589f1a235` |
| pg-message | `com.qiekj.user.base.ApiResponse.getResponseMsg` | java/complete | `ev_fd266dd082a8c60dc1ffb2de9fc60a42d20079fa53f0ff395846c63ff1d4b704` |
| pg-msg-code-refs | `com.qiekj.user.entity.MsgSyncBean` | 清单／引用/按记录coverage | `ev_511376d6789bb6407e327006ac639f8b3401efc01219003d4049e16d1940e614` |
| pg-msg-endpoint | `com.qiekj.user.http.ApiService.msgSync` | java/complete | `ev_eb140736491f0d2170696673dfc8c50fa79dbd7d7984a457311441734dd4dc63` |
| pg-msg-handle | `com.qiekj.user.ui.activity.scan.DeviceStartupAct.createObserver$lambda$36` | java/complete | `ev_a3be8918435633d7a9461461f1feb0a989a0eb6c625387d000dcbc5ed31efb08` |
| pg-msg-model | `com.qiekj.user.entity.MsgSyncBean` | 清单／引用/按记录coverage | `ev_0fc7a8a641e4818362b4322634b6b9a8b8d86d33151e80e772cbe7879eb518ef` |
| pg-msg-shower-handle | `com.qiekj.user.ui.activity.scan.StartStatusAct.createObserver$lambda$12` | java/complete | `ev_6c91403eca65c809246044a04c40d308999ad7cdfc9f8bf79029c9797b564ea7` |
| pg-order-code-refs | `com.qiekj.user.entity.scan.OrderSync` | 清单／引用/按记录coverage | `ev_c251758cf51e24afce489a4aacd2496beab129e38893492cb319cbd11dcbd9d8` |
| pg-order-endpoint | `com.qiekj.user.http.ApiService.orderSync` | java/complete | `ev_747e7704ec6b33b571a02cc59e1d5126c5dc58ddce0565098540c93db1defe4f` |
| pg-order-handle | `com.qiekj.user.ui.activity.scan.PayStateAct.createObserver$lambda$14` | java/complete | `ev_a382cb204420899db2cdd7b36faabf9aa3abc0d7448dd48a68e54a15a6484700` |
| pg-order-sync-model | `com.qiekj.user.entity.scan.OrderSync` | 清单／引用/按记录coverage | `ev_85f66da97677b08456c5be21f036ac5ddf2e760835ce525f4b2445c7ff822c58` |
| pg-pay-adapter | `com.qiekj.user.adapter.PayModeAdapter.convert` | smali/complete | `ev_0d8ec55b79bb7204a44ecdcc239167d35cd7315b4f8a52f0b757672e4a91d481` |
| pg-payment-adapter | `com.qiekj.user.adapter.PaymentAdapter.convert` | smali/complete | `ev_9d734a769e3e49ce74dabf2842599d333a394e88b330f7180f096b1c5f272a63` |
| pg-shower-end | `com.qiekj.user.viewmodel.DeviceStartupVm.showerEnd` | java/complete | `ev_4f9252e70ee04580994cfd2d30beee89102bcc46aee25562261ff1b9ca72fed3` |
| pg-shower-status-candidate | `com.qiekj.user.ui.activity.scan.StartStatusAct.createObserver$lambda$13` | java/complete | `ev_53c246fc32072370df16d2ee4158ce54f38c9f40687144f665a8efd8abad149f` |
| pg-sku-check | `com.qiekj.user.ui.activity.scan.wash.WashSkuAct.initAdapter$lambda$38` | java/complete | `ev_8ab30c4e83ad346f54ad14ce337610ca61e5831ff94060766aa67e485208f522` |
| pg-sku-list-correct | `com.qiekj.user.viewmodel.home.ScanCodeViewModel.canUseList` | java/complete | `ev_0759656aaee6a33bf2bacacf5af46a241cf9717055662f7a84149f2ba6cb3d08` |
| pg-sku-model | `com.qiekj.user.entity.scan.Sku` | 清单／引用/按记录coverage | `ev_ad02e17cefdcf25c13b30c88d0e0cee7a9e5d8cd1d6883e13358b289f7e95517` |
| pg-sku-response | `com.qiekj.user.viewmodel.home.ScanCodeViewModel.canUseList$lambda$1` | java/complete | `ev_a1b61b476c3a986523ec7f79de29a4cb5c13ea89b3bc34209606dc88fb82ce17` |
| pg-start-methods | `com.qiekj.user.viewmodel.DeviceStartupVm` | 清单／引用/按记录coverage | `ev_5dbc1f3a94cfa3b2eeae5da32e93a9b754f0e5ef8d72e1b8c8cd74d0e0139259` |
| pg-success | `com.qiekj.user.base.ApiResponse.isSucces` | java/complete | `ev_e6d5eb6067280913df131bad611ee1fc00f41f2ed2a00542e1f9a20d4e3b1758` |
| pg-water-end | `com.qiekj.user.viewmodel.DeviceStartupVm.waterEnd` | java/complete | `ev_765eb998b95e2de005f304a2f85ed5d592fcc883cb39a7ff1701cf69fcb13252` |
| pg-water-status | `com.qiekj.user.ui.activity.scan.DeviceStartupAct.createObserver$lambda$38` | java/complete | `ev_1ae90d6ebd350d440823f3302ba646347f5bc2d290eb8208951488e1b4daf5a5` |
