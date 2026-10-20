# 校园一卡通 Android 客户端

校园一卡通 Android 客户端，使用 Kotlin、Jetpack Compose 和 miuix 开发。

> 非官方项目，不隶属于学校或服务提供方。

## 功能

- 本人校园卡登录、二维码与条形码支付、账户余额查询。

## 安装与使用

支持 Android 14 / API 34 及以上。使用本人学号 / 工号与校园卡查询密码登录。

## 构建

使用 JDK 22、Android SDK Platform 37 和本版本的 Gradle Wrapper，在 `local.properties` 中配置 Android SDK 路径。

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux 使用 `./gradlew :app:assembleDebug`。依赖版本以本版本构建配置为准。

## 项目文档

- [开发说明](docs/development.md)
- [项目架构](docs/architecture.md)
- [一卡通接口](docs/api.md)

## 许可证

使用 GNU GPL v3，见 [LICENSE](LICENSE)。第三方来源与许可见 [NOTICE](NOTICE)。

