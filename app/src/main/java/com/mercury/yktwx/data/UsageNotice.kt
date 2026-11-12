package com.mercury.yktwx.data

import java.security.MessageDigest

/** One source for the notice and consent version; layout and emphasis do not change its hash. */
internal object UsageNotice {
    const val Title = "使用须知与隐私说明"
    val paragraphs = listOf(
        "本应用为非官方开源项目，仅供学习与研究，与河南财经政法大学、新中新一卡通、胖乖生活及慧生活798或相关服务方无隶属、合作或授权关系。请仅使用本人合法持有且有权访问的账户，遵守平台规定；各平台账户与业务分别管理。",
        "应用依赖各平台服务，业务规则与处理结果以官方为准。出现异常时请通过官方渠道核实，避免重复操作。请自行评估使用风险。相关风险由使用者自行承担，开发者不因此承担任何责任。项目可能暂停维护，不保证持续可用或提供技术支持。",
        "使用服务时，应用将所需的账号、登录凭据、设备信息及业务数据直接发送至对应平台，支付信息发送至相关支付服务。登录凭据和业务数据在本机加密保存，头像、设置及同意记录保存在应用私有目录；相机画面和头像仅在本机处理。应用联网时，接收服务可获得 IP 地址。",
        "同意本说明后，应用使用友盟+ U-App 统计 SDK（提供方：友盟同欣（北京）科技有限公司），由友盟处理应用启动、主要功能使用情况及分类操作结果，用于改进功能与稳定性。友盟还处理设备标识信息（如 Android ID、OAID、GUID）、设备型号、系统与应用版本、语言及网络信息，详见 https://www.umeng.com/page/policy。退出登录会撤销本机同意并停止新增统计；此前待发送的数据仍可能上传，历史数据不会自动删除。重新同意后，统计于下次重启应用恢复。",
        "应用从 GitHub 及 CDN 获取更新清单和安装包，安装前校验安装包完整性与应用身份，安装由 Android 系统授权并确认。可在设置中关闭普通更新提醒；关闭后仍会检查版本，手动检查及强制更新提示不受影响。",
    )
    val text = paragraphs.joinToString("")
    val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val emphasis = listOf(
        "非官方开源项目", "无隶属、合作或授权关系", "本人合法持有且有权访问的账户",
        "以官方为准", "相关风险由使用者自行承担，开发者不因此承担任何责任。",
        "直接发送至对应平台", "本机加密保存", "相机画面和头像仅在本机处理",
        "友盟+ U-App 统计 SDK", "设备标识信息", "停止新增统计",
        "关闭普通更新提醒", "安装由 Android 系统授权并确认",
    )
}
