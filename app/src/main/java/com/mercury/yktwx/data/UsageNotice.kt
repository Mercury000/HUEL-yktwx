package com.mercury.yktwx.data

import java.security.MessageDigest

/** The approved wording is the source of truth; visual paragraph breaks and emphasis do not change its hash. */
internal object UsageNotice {
    const val Title = "使用须知与隐私说明"
    val paragraphs = listOf(
        "本应用是非官方开源项目，与河南财经政法大学及相关一卡通服务提供方不存在隶属、合作或授权关系。本项目出于学习与研究目的开发。",
        "请仅使用本人合法持有且有权访问的一卡通账户，并遵守学校及相关服务提供方的规定与服务协议。使用本应用不改变你与相关服务提供方之间的权利义务。",
        "本应用通过官方服务接口提供功能，不能替代官方服务渠道。使用过程中可能出现认证失败、账号异常或使用受限、数据显示不准确、支付操作失败等情况。请自行评估使用风险；余额、账单及支付结果以官方记录为准。出现异常时，请通过官方渠道核实，避免在支付结果未明确时重复操作。相关风险由使用者自行承担，开发者不因此承担任何责任。",
        "登录及一卡通业务数据直接发送至官方服务，开发者不通过自建服务器中转或收集这些数据。使用微信支付时，必要的支付信息会发送至相关支付服务。",
        "登录凭据与本地资料缓存采用 Android Keystore 管理的密钥加密保存；自定义头像和界面设置保存在应用私有目录，自定义头像不会上传。",
        "官方接口、认证方式及服务规则可能发生变化，导致部分或全部功能不可用。本项目不保证持续可用，也不承诺及时通知、更新、修复或提供技术支持；项目可能暂停或停止维护。",
    )
    val text = paragraphs.joinToString("")
    val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val emphasis = listOf(
        "非官方开源项目", "不存在隶属、合作或授权关系", "本人合法持有且有权访问",
        "不能替代官方服务渠道", "认证失败、账号异常或使用受限、数据显示不准确、支付操作失败",
        "余额、账单及支付结果以官方记录为准", "避免在支付结果未明确时重复操作",
        "相关风险由使用者自行承担，开发者不因此承担任何责任。",
        "直接发送至官方服务", "不通过自建服务器中转或收集这些数据", "相关支付服务",
        "Android Keystore", "应用私有目录", "自定义头像不会上传",
        "不保证持续可用", "不承诺及时通知、更新、修复或提供技术支持", "暂停或停止维护",
    )
}
