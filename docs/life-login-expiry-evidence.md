# 哈啰与胖乖登录不可用的明确判断条件

核验日期：2026-11-13。依据用户指定的两个官方 APK，经 REA 的 JADX 方法分析确认。本文记录官方条件、修复前差异与 beta 分支的修复结果。

## 已确认的条件

| 平台 | 官方代码中的明确条件 | 官方处理 |
| --- | --- | --- |
| 哈啰 7.0.75 | 响应体业务 `code == 103 || code == -10003` | `HiResponse.isTokenError()` 返回 true；`ApiObserver.onNext(HiResponse)` 分派到 `innerNotLoginOrTokenInvalid()` |
| 胖乖生活 1.145.0 | 响应体业务 `code == 126 || code == 10` | `ResultStateKt.paresResult(MutableLiveData, BaseResponse)` 显示服务器提示，调用服务 `clear()` 和 `openLogin(true)` |

这些是响应体中的业务码，不是 HTTP 状态码。上述分支不通过提示文案包含「过期」「失效」等文字来判断。胖乖分支共同要求清除登录并重登，不能仅凭这段客户端代码进一步区分两个码分别表示时间过期、未登录还是其他凭据失效原因。

## 哈啰原始方法

类：`com.hellobike.networking.http.core.HiResponse`

方法：`isTokenError()Z`

REA Evidence：`ev_3d52d1a73c881e1be1329a7c92eae2b3f4b5c38c6c8ea0d9b5a34881c0990107`

```java
public boolean isTokenError() {
    int i = this.code;
    return i == 103 || i == -10003;
}
```

对应调用链由 `com.hellobike.networking.http.core.callback.ApiObserver.onNext(HiResponse)` 的原始 smali 确认：先判断成功，再调用 `isTokenError()`；为 true 时调用 `innerNotLoginOrTokenInvalid()`，其余失败才调用 `innerOnApiFailed(code, msg, data)`。

调用链 Evidence：`ev_0583f83f7411bb1095a6620bef1a29a203e44ecac804c76819a7835df5ff29d3`，使用明确的重载索引 0 和原始 DEX 原型 `(Lcom/hellobike/networking/http/core/HiResponse;)V`；REA 返回 smali 回退，未将它描述为 Java 反编译成功。

另一个明确的本地条件是 `HellobikeInterceptor.intercept()` 中 `getNeedLogin()` 为 true，且 token 为 null 或长度为 0。此时官方不发送该业务请求，而是生成 HTTP 200、响应体 `HiResponse(-10003, "登录失效", null)`。因此「HTTP 200」本身不能代表登录仍有效。

本地条件 Evidence：`ev_dff97156eb5430622ece26d9d143b3c2024ea80297b4230c1fa34d5571eeca02`。

## 胖乖原始方法

类：`me.qiekj.jetpackmvvm.state.ResultStateKt`

方法原型：`paresResult(Landroidx/lifecycle/MutableLiveData;Lme/qiekj/jetpackmvvm/network/BaseResponse;)V`，重载索引 1。

REA Evidence：`ev_86cdf558a5bb434275c4e93e6ffe45a7627e9c1ed52a01319207791afebc0923`

```java
if (result.isSucces()) {
    resultStateOnAppError = ResultState.Companion.onAppSuccess(result.getResponseData());
} else if (result.getResponseCode() == 126 || result.getResponseCode() == 10) {
    ToastUtils.showShort(result.getResponseMsg(), new Object[0]);
    IModuleService service = ModuleServiceHolder.getService();
    if (service != null) {
        service.clear();
    }
    IModuleService service2 = ModuleServiceHolder.getService();
    if (service2 != null) {
        service2.openLogin(true);
    }
    resultStateOnAppError = ResultState.Companion.onAppError(
        new AppException(result.getResponseCode(), result.getResponseMsg(), null, 4, null));
}
```

`com.qiekj.user.base.ApiResponse.isSucces()` 的官方条件是 `this.code == 0`，这与响应体业务码判断一致。

成功条件 Evidence：`ev_e26ca8d204a05ce909a84a515eeec8f65af696b9fe33de1f11154ef9dd3e3aca`。

`ApiResponse.getResponseCode()` 直接返回 `this.code`，没有把 HTTP 状态或其他字段转换成业务码。Getter Evidence：`ev_242b3dc371a4bbe47c2eaa718351b43e043d8e382156ee90dc5a464a89d9b6b2`。

## 修复前为什么可以显示过期文案却仍显示检查失败

1. [HellobikeApi.kt](../app/src/main/java/com/mercury/yktwx/data/life/HellobikeApi.kt) 修复前只在 `code == -10003` 时设置 `HellobikeException.loginExpired = true`，漏掉官方 `103`。其余拒绝仍保留服务器 `msg`，但没有过期标记。
2. [LifeDeviceApi.kt](../app/src/main/java/com/mercury/yktwx/data/life/LifeDeviceApi.kt) 修复前使用业务 `401/403` 和文案关键词判断，未明确覆盖官方 `10/126`。某些文案可能碰巧匹配原有规则，但不能替代明确的业务码条件。
3. [LifeAccountConnection.kt](../app/src/main/java/com/mercury/yktwx/data/life/LifeAccountConnection.kt) 将没有触发过期处理的异常记为 `Unverified` 并保留错误文本；[LifeAccountsSettings.kt](../app/src/main/java/com/mercury/yktwx/ui/pages/LifeAccountsSettings.kt) 将这种状态显示为「检查失败」。因此服务器文案可以明确写着登录失效，而账号状态仍被错误归类。
4. [YktApp.kt](../app/src/main/java/com/mercury/yktwx/ui/YktApp.kt) 付款页的生活服务登录横幅要求账号恢复结束且 `connectedPhone` 为空。过期标记漏判会保留凭据和手机号，从而不触发该横幅。

这条显示路径可以从修复前代码直接确认。此次没有采集用户设备的实际响应，不能把本次具体响应码声称为已观测到的 `103`、`10` 或 `126`。更新提醒的关闭状态不能用于解释这里的状态误分类。

## beta 修复

哈啰补齐官方业务码 `103`，胖乖改为明确处理业务码 `10/126`，移除胖乖的文案关键词和 HTTP／业务401、403登录失效推断。HTTP 明确拒绝请求仍保留原有 `businessRejected` 标记，避免将明确被拒绝的创建请求归为未知结果。798判断条件保持原状。

首次修复复用现有失效处理：清除不可用凭据、设置账号状态为 `Expired`、清空连接手机号，使付款页满足普通登录提醒的账号条件。该修复没有覆盖普通提醒的持久隐藏标记，仍不能保证明确失效后显示横幅。

2026-11-13 定向验证：`:app:testDebugUnitTest` 使用明确方法过滤运行 9 项测试，全部通过，0 失败、0 错误、0 跳过。覆盖哈啰官方两种失效码及无骑行码边界、胖乖官方两种重登码和缺少身份信息、未知业务码／文案／HTTP 状态不误判失效、明确 HTTP 拒绝保留请求拒绝标记、798既有鉴权码、网络失败保留凭据、旧核验响应隔离，以及三平台真实请求解析→清除凭据→登录横幅→设置「登录失效」的原生界面用例。未运行全量测试、Lint 或独立打包。

## 横幅补充修复

付款页之前只接收账号是否连接，用 `loginRequired && !loginNoticeDismissed` 决定横幅是否出现；并未读取账号的 `Expired` 状态。`loginNoticeDismissed` 来自普通登录横幅右侧的关闭按钮，持久保存于偏好中，不是设置页面里的提醒开关。`git log -S dismissLifeLoginNotice` 和原文件确认胖乖／798关闭逻辑由历史提交 `d77ab22`（2026-11-05，`feat(life): 增加生活服务账户管理与状态核验`）引入。这是代码中存在的隐藏条件，没有采集用户设备偏好，不能断言用户曾关闭横幅。

现在 [YktApp.kt](../app/src/main/java/com/mercury/yktwx/ui/YktApp.kt) 将三个平台各自的 `connection.status == LifeAccountStatus.Expired` 传给 [PaymentPage.kt](../app/src/main/java/com/mercury/yktwx/ui/pages/PaymentPage.kt)。明确失效直接显示对应平台「登录失效，请重新登录」的横幅，不受普通提醒的隐藏标记影响。失效横幅不提供持久隐藏的关闭按钮，点击进入该平台登录；账号恢复后随 `Expired` 状态消失，再次失效时重新出现。普通未登录提醒保留原有关闭行为。

此处没有增加业务码、文案关键词、HTTP 状态或计时等失效判断条件；只使用上述已确认鉴权处理产生的 `Expired` 状态。

补充定向验证：`LifeAccountsUiTest` 的 3 项直接相关用例全部通过，0 失败、0 错误、0 跳过。官方失效响应的真实解析／ViewModel 用例现将三个普通提醒隐藏标记预先设为 true，并检查三条明确失效横幅及设置状态；新增付款页错误分支的状态转换用例覆盖横幅点击、失效解除后消失和再次失效后重新出现；原普通提醒关闭用例仍通过。没有扩大到全量测试、Lint 或独立打包。

验证时工作区已有的 `app/build.gradle.kts` 未提交改动将 `from(project(":benchmark"))` 改成 `from(dependencies.project(":benchmark"))`，导致 `ProjectDependency`／`Project` 类型不匹配，测试无法启动。因此通过临时构建文件和初始化脚本选用 HEAD 中已提交的构建配置运行上述 3 项测试，未覆盖或提交那处既有改动。验证结束已删除临时构建文件。

## 证据身份与范围

- 原哈啰 APK：`D:\mercu\Downloads\哈啰_7.0.75.apk`，SHA-256 `7e7362c0099069334be458347e567b87eb9baf0ab0b1f4ea8a410246d3e7c62b`。
- 原胖乖 APK：`D:\mercu\Downloads\胖乖生活_1.145.0.apk`，SHA-256 `b0de8fdc4fe0f778b7b9134347b4bb427b73a67fdf223607fec4d9ecdc8bef18`。
- 哈啰使用原 `classes7.dex`，SHA-256 `e6f9be8412ccb67020c8bbc0e1718cde94746365d46357b0989c618f9bd0797d`。
- 胖乖响应模型使用原 `classes11.dex`，SHA-256 `ba11a37b5fb4833eafa2fd70965310b9798def5adbb3e86307f56ac31d2cab90`；统一错误处理使用原 `classes4.dex`，SHA-256 `3836ce37067ba1a6afa835a739ce206f87c830acccc1be65aba15b7b9e127d6d`。

REA 整包分析内存失败后，改用上述原 DEX、原 manifest 和原资源的未修改副本分片。原 APK 与分片内各条目的哈希、6 条完整 REA Evidence 均保存在 [life-login-expiry-rea-evidence.json](life-login-expiry-rea-evidence.json)。分析未安装或执行 APK；分片存在跨 DEX 覆盖限制，结论限定为上述已检查方法，未宣称穷尽平台所有运行时或 H5 鉴权路径。
