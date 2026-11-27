# Changelog

## [1.5.5](https://github.com/Mercury000/HUEL-yktwx/compare/v1.5.4...v1.5.5) (2026-11-27)


### Features

* **oobe:** 接入首次启动引导 ([a5430ba](https://github.com/Mercury000/HUEL-yktwx/commit/a5430baeda8b92e3cdb1c47f0a1e31dfc329387b))
* **ui:** 接入 Miuix RC01 抽屉渐进模糊 ([9264017](https://github.com/Mercury000/HUEL-yktwx/commit/92640170f22689fff8745bde35b241e70abe1e44))


### Bug Fixes

* **life:** 修复哈啰开锁后误判骑行结束 ([6843084](https://github.com/Mercury000/HUEL-yktwx/commit/68430848e17734301c8aca902b290e91a77c562a))
* **life:** 允许关闭生活服务登录失效横幅 ([696bbe6](https://github.com/Mercury000/HUEL-yktwx/commit/696bbe613dc6e82cda0743e8e625ad2c83c4e450))
* **life:** 恢复哈啰骑行费查单与续付流程 ([9e284ed](https://github.com/Mercury000/HUEL-yktwx/commit/9e284edd3614d9b2132144687e8adb029bd0ed50))
* **nav:** 防止生活会话首帧沿用旧状态 ([ba1e524](https://github.com/Mercury000/HUEL-yktwx/commit/ba1e5243cb454fe1d4f21091f4a2fc606d924dfe))
* **ui:** 将默认导航样式更名为原版 ([abecf71](https://github.com/Mercury000/HUEL-yktwx/commit/abecf71c358ce9080abf9837355501144cefbe00))
* **ui:** 恢复玻璃输入框默认标签色 ([e6eefd7](https://github.com/Mercury000/HUEL-yktwx/commit/e6eefd7687d87e20c3bc0be2a611692c2ac0691c))
* **ui:** 移除哈啰页面和抽屉的外跳链接 ([de7a6bb](https://github.com/Mercury000/HUEL-yktwx/commit/de7a6bb5a9f96cfa654ee9630fbdbf1fbbbc9525))
* **ui:** 移除生活服务登录抽屉的多余说明 ([c855ee5](https://github.com/Mercury000/HUEL-yktwx/commit/c855ee597e2d1f675530682d6120313167624d7c))


### Miscellaneous Chores

* release 1.5.5 ([3e7b672](https://github.com/Mercury000/HUEL-yktwx/commit/3e7b672ff7212b08951c8a6996105074c7f2c848))

## [1.5.4](https://github.com/Mercury000/HUEL-yktwx/compare/v1.5.3...v1.5.4) (2026-11-25)


### Features

* **nav:** 接入组件展开转场 ([b6e3068](https://github.com/Mercury000/HUEL-yktwx/commit/b6e30683bbc863c8ae6a6bf47aff993c6d4795b1))
* **ui:** 对齐玻璃控件材质 ([373decb](https://github.com/Mercury000/HUEL-yktwx/commit/373decbab47b067945459ba4b7f624e61951b312))
* **ui:** 接入 miuix beta04 动效 ([0e48b7d](https://github.com/Mercury000/HUEL-yktwx/commit/0e48b7d154ca5a39afe53e2e6abd0133ab9622b1))


### Bug Fixes

* **bill:** 覆盖排行末行按动反馈留白 ([dcfc216](https://github.com/Mercury000/HUEL-yktwx/commit/dcfc21633a032a05d775fc47ed43f66ae478f6ce))
* **fee:** 使用入口电表类型显示充值标题 ([5ec8767](https://github.com/Mercury000/HUEL-yktwx/commit/5ec87679a45c9435ac632f20ffe480af36d6f50a))
* **pangguai:** 按官方状态机处理接水与淋浴轮询 ([34555fa](https://github.com/Mercury000/HUEL-yktwx/commit/34555fa14d1f05d0f574b75a3e451669c6b05826))
* **pangguai:** 按官方默认值解析支付渠道状态 ([bba057f](https://github.com/Mercury000/HUEL-yktwx/commit/bba057fc118b1ee85daecd4bde49958aa8c34406))


### Performance Improvements

* **profile:** 更新应用启动与常用操作编译资料 ([#18](https://github.com/Mercury000/HUEL-yktwx/issues/18)) ([b1cb2ef](https://github.com/Mercury000/HUEL-yktwx/commit/b1cb2ef43a955c75914d1cc8b656404ff3dd06c1))
* **profile:** 补采玻璃弹层动效 ([758f9af](https://github.com/Mercury000/HUEL-yktwx/commit/758f9afb0c907966943f8197ffe563981b617ae7))


### Miscellaneous Chores

* release 1.5.4 ([7468be9](https://github.com/Mercury000/HUEL-yktwx/commit/7468be97fd03885c80931a0e1fd068839af4ccc5))

## [1.5.3](https://github.com/Mercury000/HUEL-yktwx/compare/v1.5.2...v1.5.3) (2026-11-22)


### Features

* **startup:** 接入系统启动遮罩覆盖应用初始化阶段 ([1c9fde3](https://github.com/Mercury000/HUEL-yktwx/commit/1c9fde33df7c2a174763ebbe8c54117394931b10))


### Bug Fixes

* **life:** 修正扫码页抽屉的背景采样 ([2f7de01](https://github.com/Mercury000/HUEL-yktwx/commit/2f7de01f5c3b7cb28aa683f19b247b96e0306453))
* **nav:** 按入口来源选择生活设备页转场 ([9c18f53](https://github.com/Mercury000/HUEL-yktwx/commit/9c18f537307f4d5681fad4dd5ec2da91b40db858))
* **scanner:** 让独立扫码页跟随应用外观设置 ([6f8e46f](https://github.com/Mercury000/HUEL-yktwx/commit/6f8e46ffe546c051dc1a2b72224376742053ead5))
* **update:** 关闭接收测试版更新时自动检查更新 ([019ad84](https://github.com/Mercury000/HUEL-yktwx/commit/019ad8451efab9fb8000e27eb392748e0d58ab99))


### Miscellaneous Chores

* release 1.5.3 ([0ac2dd5](https://github.com/Mercury000/HUEL-yktwx/commit/0ac2dd5e63e0850373e5c4ebd800771a8d02ac88))

## [1.5.2](https://github.com/Mercury000/HUEL-yktwx/compare/v1.5.1...v1.5.2) (2026-11-21)


### Performance Improvements

* **camera:** 使用独立扫码窗口优化启动与进入体验 ([62845c4](https://github.com/Mercury000/HUEL-yktwx/commit/62845c4c6d58e7263722da5a27160fbde740bb99))
* **profile:** 更新应用启动与常用操作编译资料 ([#14](https://github.com/Mercury000/HUEL-yktwx/issues/14)) ([c9eadf4](https://github.com/Mercury000/HUEL-yktwx/commit/c9eadf4360bd0ab8b4d411d0a9008547293b5ccf))

## [1.5.1](https://github.com/Mercury000/HUEL-yktwx/compare/v1.5.0...v1.5.1) (2026-11-19)


### Features

* **payment:** 将付款码禁止截图改为截图提醒 ([690e347](https://github.com/Mercury000/HUEL-yktwx/commit/690e34744bd95709fab7b5993a71fee8c31748f8))
* **settings:** 默认启用 OS4 玻璃导航与模糊效果 ([cb74f3a](https://github.com/Mercury000/HUEL-yktwx/commit/cb74f3aa72ef059578858800cde9a498775c1f55))


### Bug Fixes

* **life:** 接水选项采用紧凑玻璃分段选择器 ([ff2726e](https://github.com/Mercury000/HUEL-yktwx/commit/ff2726e3b2c13490bbdc959f98ba7241ebd50a74))


### Miscellaneous Chores

* release 1.5.1 ([e5f7977](https://github.com/Mercury000/HUEL-yktwx/commit/e5f79779f341479b0f8fa0544df26e75fe65ea7a))

## [1.5.0](https://github.com/Mercury000/HUEL-yktwx/compare/1efc687c78bce3d077f6ecca687f5756d2f5dd8d...v1.5.0) (2026-11-17)


### Features

* **analytics:** 接入友盟统计与核心业务埋点 ([a08d71b](https://github.com/Mercury000/HUEL-yktwx/commit/a08d71bc1bd4db0220a80c0d61a78e5613dff042))
* **life:** 接入哈啰骑行与骑行卡并复用登录付款界面 ([aecc48c](https://github.com/Mercury000/HUEL-yktwx/commit/aecc48c86f24b5a80c2977c8ffc947603081e797))
* **ui:** 升级 miuix 快照并接入玻璃风格 ([8ade801](https://github.com/Mercury000/HUEL-yktwx/commit/8ade8018a561113c665a38b7520888cd5272dc06))
* **ui:** 统一付款与业务图标风格 ([5a83da4](https://github.com/Mercury000/HUEL-yktwx/commit/5a83da47258f4110b1615edacffae04d362b2a8a))
* **ui:** 重绘应用图标 ([97e1ec6](https://github.com/Mercury000/HUEL-yktwx/commit/97e1ec6e9ac02e8490b0133a662ba55c02383196))
* **update:** 完善应用内更新并接入双渠道自动发版 ([b2883a2](https://github.com/Mercury000/HUEL-yktwx/commit/b2883a29b171238707f8f4fd334ea484f3edea0f))


### Bug Fixes

* **hui798:** 使用账号状态接口核验登录状态 ([788a143](https://github.com/Mercury000/HUEL-yktwx/commit/788a143e51471eb6f6e9e0858ae50e5d584bcf78))
* **life:** 统一扫码设备检查反馈与内联提示动画 ([9095508](https://github.com/Mercury000/HUEL-yktwx/commit/9095508a873970cc2195bcf7283a5bfc7b33cb53))
* **life:** 统一设备编号输入并支持798编号分流 ([884f87f](https://github.com/Mercury000/HUEL-yktwx/commit/884f87f4cc87edd80e2805f1a8e8e4112c9a3214))
* **life:** 统一退出清理与异常订单移除 ([dfb445a](https://github.com/Mercury000/HUEL-yktwx/commit/dfb445a7b391d1bb7716b03f8c4464953bdbdb6d))
* **life:** 胖乖与798登录失效时始终显示横幅 ([aa09927](https://github.com/Mercury000/HUEL-yktwx/commit/aa09927a9b316952ae7117cab42c12ec7e1057f2))
* **nav:** 对齐 miuix demo 的平板侧栏导航行为 ([ee3b456](https://github.com/Mercury000/HUEL-yktwx/commit/ee3b4562e720461a2e92381932d8cd55bf70a084))
* **panggui:** 对齐官方校验并保留完整服务端提示 ([faf1a84](https://github.com/Mercury000/HUEL-yktwx/commit/faf1a84087fdc4435a8237e40bb4b4a479d65ea5))
* **panggui:** 按官方业务码判断登录失效 ([dc8c62d](https://github.com/Mercury000/HUEL-yktwx/commit/dc8c62d0add3234dc2bb7252b34ce8291a074cd7))
* **ui:** 统一入场动画状态并避免切页重复播放 ([7528da9](https://github.com/Mercury000/HUEL-yktwx/commit/7528da9b88467769e8c0f6295eb8c17a1df38925))


### Performance Improvements

* **profile:** 接入应用编译资料与手动采集流程 ([698943c](https://github.com/Mercury000/HUEL-yktwx/commit/698943c827b4271758313ce4dbb880dddf67b259))
* **profile:** 更新应用启动与常用操作编译资料 ([#10](https://github.com/Mercury000/HUEL-yktwx/issues/10)) ([72dd35f](https://github.com/Mercury000/HUEL-yktwx/commit/72dd35fc02962f217ad3d724e3bfd2d565f216bf))

## [1.5.0-beta.1](https://github.com/Mercury000/HUEL-yktwx/compare/v1.5.0-beta...v1.5.0-beta.1) (2026-11-14)


### Bug Fixes

* **update:** 修复双渠道检查等待与更新结果反馈 ([1b1e213](https://github.com/Mercury000/HUEL-yktwx/commit/1b1e213847f1c8ecb1dce8cc961762d67d25f65f))

## [1.5.0-beta](https://github.com/Mercury000/HUEL-yktwx/compare/1efc687c78bce3d077f6ecca687f5756d2f5dd8d...v1.5.0-beta) (2026-11-14)


### Features

* **analytics:** 接入友盟统计与核心业务埋点 ([d1c0856](https://github.com/Mercury000/HUEL-yktwx/commit/d1c08568277107bb762637a803d98a5c981e65df))
* **hellobike:** 接入骑行卡账户页并修正付款与还车状态 ([2bbcc7e](https://github.com/Mercury000/HUEL-yktwx/commit/2bbcc7e0a97b9e62af94801af0288f7d131ce306))
* **life:** 接入哈啰骑行并复用登录与付款界面 ([aa48949](https://github.com/Mercury000/HUEL-yktwx/commit/aa489490e53baaf2cc031d16dad47544dd2d09ce))
* **update:** 完善应用内更新并接入双渠道自动发版 ([0b0f8c5](https://github.com/Mercury000/HUEL-yktwx/commit/0b0f8c5a99b7425e43fe09291a6e54a9bcc6a99e))


### Bug Fixes

* **hui798:** 使用账号状态接口核验登录状态 ([877536e](https://github.com/Mercury000/HUEL-yktwx/commit/877536ec0f66d345fc40fd361d9718c49cbba129))
* **life:** 统一设备编号输入并支持798编号分流 ([28bb455](https://github.com/Mercury000/HUEL-yktwx/commit/28bb455eeaa316a766a16fbea94b4928cc9839f9))
* **life:** 统一退出清理与异常订单移除 ([aafff4f](https://github.com/Mercury000/HUEL-yktwx/commit/aafff4fb4a07ca4c8a23c60bd3bf48aff47116cd))
* **life:** 胖乖与798登录失效时始终显示横幅 ([e5f65ad](https://github.com/Mercury000/HUEL-yktwx/commit/e5f65ad61546898619aa12be8cbe98fc96bf0244))
* **nav:** 对齐 miuix demo 的平板侧栏导航行为 ([23821e9](https://github.com/Mercury000/HUEL-yktwx/commit/23821e9959089c8c4daf2937a1c7abd820b17d84))
* **panggui:** 对齐官方校验并保留完整服务端提示 ([2549619](https://github.com/Mercury000/HUEL-yktwx/commit/25496197716e8ba58d5b69ca75c79928f36ef7b7))
* **panggui:** 按官方业务码判断登录失效 ([b0327f7](https://github.com/Mercury000/HUEL-yktwx/commit/b0327f75a51f0ab113c54dc3efb15bca00f14b01))
* **ui:** 统一入场动画状态并避免切页重复播放 ([7528da9](https://github.com/Mercury000/HUEL-yktwx/commit/7528da9b88467769e8c0f6295eb8c17a1df38925))


### Performance Improvements

* **profile:** 接入应用编译资料与手动采集流程 ([31f9881](https://github.com/Mercury000/HUEL-yktwx/commit/31f9881105d92dc9bb7b4b2367e8af8bc99890c4))
