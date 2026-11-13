# 哈啰与胖乖登录不可用的明确判断条件

依据用户指定的两个官方 APK，经 REA 的 JADX 方法分析确认。本文记录官方登录失效条件、账号状态及界面行为。

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

## 账号状态与登录横幅

哈啰按官方业务码 `103/-10003`、胖乖按 `10/126` 判断登录失效。失效时清除不可用凭据，清空连接手机号，并将账号状态设为 `Expired`。未知业务码、文案关键词及 HTTP 状态不用于推断这两个平台的登录失效；HTTP 明确拒绝仍保留 `businessRejected` 标记。网络失败保留凭据并显示实际检查失败，旧账号的迟到响应不写回新账号。

[YktApp.kt](../app/src/main/java/com/mercury/yktwx/ui/YktApp.kt) 将各平台的 `connection.status == LifeAccountStatus.Expired` 传给 [PaymentPage.kt](../app/src/main/java/com/mercury/yktwx/ui/pages/PaymentPage.kt)。明确失效直接显示对应平台「登录失效，请重新登录」横幅，不受普通提醒的隐藏标记影响。点击进入该平台登录；恢复后横幅随 `Expired` 状态消失，再次失效时重新出现。普通未登录提醒可关闭，失效横幅不提供持久隐藏按钮。

设置页将 `Expired` 显示为「登录失效」，实际查询失败显示为「检查失败」并保留完整原因。哈啰没有骑行中订单时仍显示账号信息和「已登录」，无需展示无订单消息，具体查询规则见[哈啰账号检查](hellobike-account-check-evidence.md)。

定向验证覆盖官方失效码、未知业务码及 HTTP 边界、网络失败保留凭据、旧响应隔离、原文显示，以及三平台失效横幅与设置状态。界面用例预先隐藏普通提醒，确认失效横幅仍出现，并覆盖点击登录、恢复后消失及再次失效后重新出现。验证使用模拟响应，未采集用户设备的实际返回码或偏好。

## 证据身份与范围

- 原哈啰 APK：`D:\mercu\Downloads\哈啰_7.0.75.apk`，SHA-256 `7e7362c0099069334be458347e567b87eb9baf0ab0b1f4ea8a410246d3e7c62b`。
- 原胖乖 APK：`D:\mercu\Downloads\胖乖生活_1.145.0.apk`，SHA-256 `b0de8fdc4fe0f778b7b9134347b4bb427b73a67fdf223607fec4d9ecdc8bef18`。
- 哈啰使用原 `classes7.dex`，SHA-256 `e6f9be8412ccb67020c8bbc0e1718cde94746365d46357b0989c618f9bd0797d`。
- 胖乖响应模型使用原 `classes11.dex`，SHA-256 `ba11a37b5fb4833eafa2fd70965310b9798def5adbb3e86307f56ac31d2cab90`；统一错误处理使用原 `classes4.dex`，SHA-256 `3836ce37067ba1a6afa835a739ce206f87c830acccc1be65aba15b7b9e127d6d`。

REA 整包分析内存失败后，改用上述原 DEX、原 manifest 和原资源的未修改副本分片。原 APK 与分片内各条目的哈希、6 条完整 REA Evidence 均保存在 [life-login-expiry-rea-evidence.json](life-login-expiry-rea-evidence.json)。分析未安装或执行 APK；分片存在跨 DEX 覆盖限制，结论限定为上述已检查方法，未宣称穷尽平台所有运行时或 H5 鉴权路径。
