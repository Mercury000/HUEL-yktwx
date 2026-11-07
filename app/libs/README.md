# 本地依赖

本目录保存二维码识别与银联支付的固定版本依赖。依赖信息和许可证通过 `app/config/aboutlibraries/` 配置，在应用的开源项目页面展示。

## 二维码识别

使用 [wechat-qrcode-lite 0.1.0](https://github.com/Mercury000/wechat-qrcode-lite/releases/tag/v0.1.0)，依赖包含 ARM64 原生库、四个识别模型、Java/JNI 桥接与上游许可证声明。

| 文件 | SHA-256 |
| --- | --- |
| `wechat-qrcode-lite-0.1.0-arm64-v8a.aar` | `5a1bbdb66aa57e74996e680731b0dd41c5f7fa095cefc8435c25a2a1d5a99887` |

相机打开、关闭、采集控制和帧复制在相机线程执行，二维码识别在独立线程执行。识别器复用灰度缓冲区，每次处理一帧，在首个预览帧后初始化；关闭时等待当前识别结束再释放。

应用仅包含 `arm64-v8a`，JNI 库通过 `packaging.jniLibs.useLegacyPackaging` 压缩打包。ZXing 用于生成付款二维码和条形码。

许可证元数据包含完整 Apache 2.0 文本及上游声明链接，支持离线构建。

## 银联支付

使用 UnionPay SDK Mini 3.5.16，来源为[银联 SDK 下载页](https://open.unionpay.com/tjweb/acproduct/list?apiSvcId=450&index=5)提供的 `云闪付APP支付开发包（安卓）3.5.16（20250911）.zip`。

| 文件 | SHA-256 |
| --- | --- |
| `unionpay-sdk-mini-3.5.16.jar` | `b7ef8f882d8f1746bbdbfe9e4ed0ae2767af8f4f5205a61817e3fbb0445a0ab1` |
| `app/src/main/jniLibs/arm64-v8a/libentryexpro.so` | `de3223daa80a4b61c121071ca37141ccf82854ec2e945c7142ac7d61e0a5436e` |

SDK 类型为 `02`，版本为 `3.5.16`，属于厂商提供的专有依赖。

洗衣普通银联通道使用 `startPay(activity, null, null, tn, "00")`。商户签名由服务端处理，支付回调触发原订单查询，最终结果以订单状态为准。
