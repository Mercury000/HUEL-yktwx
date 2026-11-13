# 哈啰判断条件与证据范围

此前「完整核对」结论不成立。2026-11-16按整个已接入实现修改本地额外业务门槛，当前差异及源码来源见[业务对齐记录](hellobike-business-alignment-rea.md)。下面记录当前判断；历史原生备用链不能替代默认H5消费者。

## 服务端业务判断

| 判断 | 官方依据 | 当前处理及边界 |
| --- | --- | --- |
| 手输10位设备号 | 手输解析与QR解析分别核对，见协议核验 | 仅是输入格式，不代表真实设备或已允许开锁；预骑行接口决定结果。 |
| 登录过期 | HiResponse.isTokenError，见登录失效证据 | 103/-10003表示过期，不按任意错误文案推断过期。 |
| 短信滑块 | 原生短信调用 | 先captchaType再ok；失败保留返回文字。 |
| 预骑行成功 | 默认H5预处理、onAutoConfirmUnlock与onConfirmUnlock | 消费canSkip/executor与完整官方交互分支；普通result=false不能套用原生备用链的causeType1100 OR条件。 |
| 预骑行官方交互 | S/z0/R分派，见协议/预骑行证据 | 人脸、就近车辆、特殊告知等仍需各自流程。当前未实现的交互明确说明，不能用通用成功绕过。 |
| 无骑行中订单 | RidingOrderRepository.checkOrder | 无活动订单是正常发现结果，设置不显示异常；不能单凭它确认某个待查原单已经结束。 |
| 开锁/结束状态 | user.ride.order.status与RideOrderStatus | rideStatus20、subStatus22/23表示开锁中；30结束。它与bikeCheck的状态空间不同。 |
| 还车确认 | P10及原订单查询 | causeType1100或原单30即先保存结束；费用延迟不撤回还车成功，不请求endInfo加附加门槛。 |
| 还车特殊分支 | P10BikeLock、BikeLockStatus与getFirstOperate | 1101/1108按相同operateType/poll继续P10；1102/1103按skipConfirm选择直接提交或用户确认，firstOperate跟随官方操作顺序。位置及关锁阶段各40秒；蓝牙与特殊调度费流程仍未接入。 |
| 费用结清 | 官方费用页H5 | SUCCESS/CLOSED独立处理；未知/费用未生成保留云端状态。删除本地费用合计一致门槛，其他bizType和复杂渠道仍属于未接入范围。 |
| 学生认证 | 认证H5与HAR | authState2且userType1显示校园购买；状态未返回和查询异常分别显示，认证材料由官方处理。 |
| 我的卡状态与续费 | 我的卡H5 | 卡状态、到期时间来自列表；续费按status0、hasPackage、canOperateType显示，并进入相应商品页，不直接延长旧卡。 |
| 校园商品按钮 | 新版校园H5 Q/H/R/W完整源码 | 不使用button.canClick或appLimit禁购；业务1/2均支持预下单；所有商品资格交给预下单响应。 |
| 普通商品canBuy | 普通H5 isShowDisableText/getDisableText | 仅>1禁购，1允许；2停止购卡、3卡数量上限。只读取返回值，不自创资格。 |
| paymentLimit | 普通H5 handlePreOrder | 1走连续包月；4/5有特定渠道补贴/签约分支，不能当成统一禁购，也不能宣称这些专属分支全部可用。 |
| 付款初选/手选 | PayChannelDataTransfer.f/m/p/v/w/d与t0/B0 | 指定默认、enableSelect优先、余额合格首渠道兜底；不能只看w就判定无初选。普通手选不强制可选免密签约，余额不足单独处理。 |
| 付款金额/订单ID | ConfirmPayHelper.b/c与PayCoreConfirmPayHelper.a | 原订单金额和优惠标识分别发送；paymentId不等同orderNo/businessId。 |
| 连续包月 | formatSignParams、HBPayCore.b、PayAndSignTransfer.a、模块工厂和HBAliIPayModule | 支付宝102，periodRuleParams来自预下单，原生payV2；宿主回跳仅返回应用，不授予支付成功。已完成静态映射及本地验证，尚未实付。 |
| 付款成功 | BasePayModule.i/w与CheckOrderStatusHelper.j/k/l | 指定订单或返回orders[0]，按DTO解析后的payStatus==1确认；不附加原始JSON token类型、返回guid/金额/业务字段相等或全单成功门槛。SDK和回跳不直接确认。自动首次立即、最多3次重查、间隔3秒；手动单次。 |
| 取消支付 | CashierDialogManager.b及共享支付桥实际回调 | 关闭收银台不注销云端订单；6001/cancel只取消本次SDK操作。云端成功优先；查询失败保留待查标识。没有已确认的云端购卡取消接口。 |

## 本应用恢复与完整性条件

本应用保留请求账户、页面选择与取消关联，提交前持久化原请求，丢失响应只查原操作，以及位置格式与单调时钟检查。这些属于宿主恢复与权限适配。已撤销金额合计、单选订单、返回业务标识必须相等及缺云端ID必须为Opening的门槛；不再用“本地完整性”包装这些业务判断。账号详情不以本机恢复内容生成云端订单列表，历史付款标记恢复时重新查询云端。

## 默认渠道的漏查与补查

此前只看到PayChannelDataTransfer.w检查enableSelect，就把所有false解释成不初选。这遗漏了调用方p。原生7.0.75支付DEX切片的REA结果显示：f解析响应extraInfos，经m提取defaultChannelSetting；p先u恢复当前选择，再v匹配指定默认，随后w尝试enableSelect，最后仍有d兜底。

p中的关键语句为：

```java
PayTypeBean payTypeBeanW = payChannelDataTransfer.w(arrayList, payMoney);
payTypeBeanU = payTypeBeanW == null ? payChannelDataTransfer.d(arrayList, payMoney) : payTypeBeanW;
```

d遍历列表，选择首个不需要余额检查或余额足够的渠道，并调用setCheck(true)。有子渠道时展开并选择第一个子渠道。因此普通支付宝enableSelect=false仍可被兜底初选。普通点击t0→B0也不读取enableSelect。此前的禁付解释和「必须手选」解释都不能保留。

完整方法体及精确DEX原型见[REA证据](hellobike-purchase-conditions-rea.json)，p证据ID为ev_d47ed6af462f9945235dd9c5a3c5da7c89caf3994516312f36599aeb314b41a9，d为ev_f00a737d51cd523179b3bfa7ffc858351c0ede5fc2ab3958cd04ec10b636bfb4。切片SHA-256为0a324bde7bfd7ec83955948edacc53f79287f80cd04f9c785de4cbacb91fc307。这里证明的是该版本原生代码中的选择顺序；没有运行官方App或进行实付。

## 公共界面复用

购卡、骑行付款、洗衣付款、卡片充值、生活缴费共用PaymentSheetPages，合并抽屉宿主、动画、滚动、底部安全区、表单及结果间距。购卡/骑行/洗衣固定金额共用PaymentFixedAmount，付款动作共用PaymentMethodActions，结果共用PaymentResultContent。业务调用仍由各控制器处理，页面不自创资格或订单状态。测试覆盖页面加载/切换/退出、原付款查询、重新选择以及最新充值配置。

## 证据与验证

- [协议及既有原生分支](hellobike-protocol-audit.json)、[返回证据](hellobike-return-evidence-index.json)、[共享查单](hellobike-payment-query-evidence.json)。
- [购卡条件REA](hellobike-purchase-conditions-rea.json)：19个原生方法完整结果、原型、fallback、来源哈希；校园组件完整源码；普通H5片段附有截断限制。新增默认选择f/m/p/v/d/s，记录漏看p→d兜底的修正。
- [上轮付款和还车方法](hellobike-account-return-rea.json)。HAR与官方H5包来源、哈希见[购卡说明](hellobike-campus-cards.md)。

此前付款/取消定向验证46项通过，共享支付抽屉调整后14项复测通过。最新默认选择及骨架/展开按钮验证21项通过：API3、卡控制器11、共享选择器UI5、账号UI2。覆盖指定默认优先、enableSelect=false兜底、余额不足不初选、无选择及失效下标、原生指示器动画、延迟渠道返回不崩溃且不自动付款、分区骨架、刷新保留卡片、普通商品首次展开才读取及收起后缓存。日志为build/hellobike-campus-card/skeleton-default-selection-validation.log；浅色/深色实际Compose截图分别保存为account-skeleton-*和account-other-cards-expanded-*。未追加全量测试或Lint。

没有真实付款、发卡到账或实车验证；chaos传输、设备风控以及4/5等特殊渠道分支仍未完整接入。静态来源有版本差异，不能把一个H5版本的观察推广到所有官方版本和线上响应。
