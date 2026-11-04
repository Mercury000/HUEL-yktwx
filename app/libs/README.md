# 本地依赖

本目录保存二维码识别的固定版本依赖。依赖信息和许可证通过 `app/config/aboutlibraries/` 配置，在应用的开源项目页面展示。

## 二维码识别

使用 [wechat-qrcode-lite 0.1.0](https://github.com/Mercury000/wechat-qrcode-lite/releases/tag/v0.1.0)，依赖包含 ARM64 原生库、四个识别模型、Java/JNI 桥接与上游许可证声明。

| 文件 | SHA-256 |
| --- | --- |
| `wechat-qrcode-lite-0.1.0-arm64-v8a.aar` | `5a1bbdb66aa57e74996e680731b0dd41c5f7fa095cefc8435c25a2a1d5a99887` |

相机打开、关闭、采集控制和帧复制在相机线程执行，二维码识别在独立线程执行。识别器复用灰度缓冲区，每次处理一帧，在首个预览帧后初始化；关闭时等待当前识别结束再释放。

应用仅包含 `arm64-v8a`，JNI 库通过 `packaging.jniLibs.useLegacyPackaging` 压缩打包。ZXing 用于生成付款二维码和条形码。

许可证元数据包含完整 Apache 2.0 文本及上游声明链接，支持离线构建。
