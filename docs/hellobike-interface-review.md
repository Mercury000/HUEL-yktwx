# 哈啰已接入接口复核

本文件是本应用已接入接口的索引，不是「全链路核对完成」或线上可用证明。此前把局部静态检查和本地测试当作完整复核，遗漏了校园按钮、普通canBuy和包月分支，原结论应撤回。当前包含登录、定位、骑行、还车、费用与购卡的所选请求和状态分支。字段存在不等同于字段含义已确认。蓝牙锁具、调度费接受、独立免密开通/解绑、组合支付、认证材料提交及其他特殊签约不在当前接入范围；连续包月支付宝102路径已添加，但尚未实付验证。

## 接口清单

| 接口 | 依据及最终处理 |
| --- | --- |
| user.account.sendCodeV3 | APK短信调用：先判断captchaType，再判断ok；地区可空，保留官方原因。 |
| user.account.slideCaptcha | APK滑块模型：保留challenge、原图尺寸，处理刷新和账号变化。 |
| user.account.login | APK登录/签名/渠道/失效证据：7.0.75、真实签名摘要和渠道；token/guid创建账号，clientId未初始化用官方空值回退。 |
| hello.lbs.code.regeo | 官方H5反查及签名算法：按响应取地区，未伪造城市；位置转换和授权分别验证。 |
| user.ride.bikeCheck | APK模型及RidingOrderRepository、官方H5bikeTravelCheck：原生字段/bike主机；无订单不作为设置错误，本地待查记录继续查原单；已结束记录不能复活。 |
| user.ride.pre.ride | 官方预骑行调用：result/causeType成功分支，保留特殊交互要求；设备号格式不能替代服务端校验。 |
| tw.bike.unlock.page.basic.info | 官方开锁页调用：取得计费说明；缺少文字不额外否定成功预检。 |
| user.ride.create | 官方请求和结果：先保存Opening，单次提交，后续查询原单，防账号/取消竞态。 |
| user.ride.order.status | 官方H5orderStatusCheck、APK原单查询及RideOrderStatus：20/subStatus判断开锁，30结束，区别于bikeCheck状态空间；Closing优先查原单。 |
| ride.hub.pre.close | APK P10及官方请求：operateType0检查、2确认，单次提交；causeType1100成功先持久化Ended，处理中查询原单。 |
| user.artemis.pay.page.order.detail | 官方费用页H5：费用独立查询，保留付款标识；费用延迟不撤回已确认还车。 |
| student.audit.info | 校园HAR及认证H5：仅authState2且userType1允许校园购买；未认证与查询错误区分。 |
| tw.ridecard.user.list | 我的卡HAR及H5：双业务、空卡正常、页号每次加1、Unix秒日期、官方续费入口语义。 |
| student.card.get.v3 | 校园新版H5 Q/H/R：直接购买所有返回商品，不根据button.canClick或appLimit禁购，支持业务1/2；预下单决定资格。 |
| tw.ridecard.commodity | 我的卡HAR35及普通H5：单车普通商品懒加载，canBuy>1禁购，paymentLimit1走包月签约；无需学生认证。 |
| tw.standard.preOrder | 校园HAR19与普通wrapper：原促销、fromTwoWheel=true；paymentId映射付款ID，非orderNo。 |
| com.alphapay.getPayChannel | H5 SDK及APK请求/初选/手动点击分支：购卡原生SDK字段和安装检测，子渠道分别处理；f/m/p/v/w/d确认指定默认、enableSelect优先及首个余额合格渠道兜底，false不能直接禁付或断言无初选。 |
| com.alphapay.paymentOrder.pay | ConfirmPayHelper.b/c、PayCoreConfirmPayHelper.a及付款桥：购卡发送原金额与优惠标识；HBPayCore.b与PayAndSignTransfer.a确认包月102路径、周期规则和宿主回跳；SDK回调不直接结清。 |
| com.alphapay.facade.orderSingleQuery | APK原单查询及整数状态：按原ID和整数1确认，未套用businessId=paymentId或原金额=优惠后金额的未经证实映射，返回guid时防串单。 |

原有登录/骑行主机、公共字段、方法体及DEX原型见[协议核验](hellobike-protocol-audit.json)、[登录失效证据](life-login-expiry-evidence.md)。P10模型和原因空间见[还车证据索引](hellobike-return-evidence-index.json)。购卡HAR、H5来源及哈希见[账号购卡说明](hellobike-campus-cards.md)。

## 发现和修复

1. 还车明确成功曾被忽略；原订单结束后，又受tw.end.module.info的tradeRegionInfo.orderStatus==2附加门槛阻挡。未取得这个附加确认条件的官方调用方依据，已删除该请求和门槛。P10成功或原订单30先保存Ended，再查费用。
2. Closing曾受缓存骑行发现结果阻挡。现在优先查原单；恢复发现不能把同一已结束记录改回Riding。无骑行中订单本身仍不推断原单成功。
3. enableSelect=false曾被解释成付款不可用。初选w、点击t0/B0及付款A0/Q0证实这是错误解释，普通渠道可手动选择，真实余额/签约限制独立；子渠道不会整组过滤。
4. 原生购卡渠道漏传支付宝安装状态，沿用H5 SDK字段。现在版本、Android、安装检测、空amount和paymentId独立映射；普通购卡wrapper及组资格亦已核对。
5. 原生优惠后金额曾被当成请求原金额。ConfirmPayHelper.b/c保留原payMoney/payList，优惠另传；本机保存报价。共享查单也已删除businessId/金额的未经证实结清门槛，按原请求与官方整数状态判断。
6. 校园按钮曾套用button.canClick/appLimit门槛；普通canBuy只接受0，还把所有paymentLimit跳转官方。新版校园组件未使用前两个条件，普通仅canBuy>1禁购。按实际分支修正，并接入支付宝包月102。普通付款的可选签约提示不再强制禁付。
7. 本机恢复内容曾在账号页伪装成待付款记录。该订单行已删除。取消付款与云端取消订单分开；本机原标识仅用于查询恢复，SDK取消后仍查原单，查询异常不能丢失未知付款或重复支付。
8. 初选只检查w曾遗漏外层p→d兜底。全部enableSelect=false时，本应用未选中并把-1传入Miuix TabRowWithContour，延迟返回渠道的Compose测试复现scrollToItem(-1)异常。补查完整调用顺序，支持渠道按指定默认、enableSelect、可用首渠道初选；共用选择器同时支持确实无选择或下标失效的状态，不自动付款。

## 证据和验证范围

[REA摘要](hellobike-account-return-rea.json)保存18个所选方法的完整输出、精确原型、表示方式、fallback、引擎和来源哈希。可沿请求→初选→点击→付款传递链复核。原DEX指令确认PayTypeListRequestHelper.b把PayDataHolder字段b传给setCurrentSdkVersion；clinit中字段b为1.55.0。RideOrderStatus.getRIDE_STATUS_OVER明确返回30。

一轮75项定向测试通过：API31、卡控制器7、骑行ViewModel35、账号详情UI1、设置入口UI1。随后最终付款映射及布局6项、共享查单3项复测通过；未运行全量测试或Lint。回归覆盖成功还车/缓存发现/账单延迟/恢复不复活、无订单不误判、促销及ID映射、子渠道、手动选择、付款前保存、SDK回跳查询和账号隔离。

截图来自实际Compose/Robolectric渲染的测试账号/商品，包括build/hellobike-campus-card/account-compact-cards-light.png及空卡、未认证明暗截图。未用HAR凭据发送线上购卡、付款或实车请求。

官方原生支付chaos加密及设备风控初始化未完整迁入。字段映射和本地验证通过不能证明所有官方传输机制、在线资格、实车关锁、支付受理和发卡到账。这份清单记录已接入接口的静态与本地行为覆盖，不把尚未实测的链路标为线上通过。

补充判断核对表见[条件核对](hellobike-condition-review.md)，19个原生方法及校园源码见[购卡REA](hellobike-purchase-conditions-rea.json)。本轮验证结果另见条件核对表，不能以之前75项测试替代本轮修正的验证。
