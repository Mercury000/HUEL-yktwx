# 更新发布器测试数据

本目录包含发布器生成的签名更新清单，用于 Python 发布脚本与 Android 客户端的互操作测试。

| 文件 | 内容 |
| --- | --- |
| `latest.json` | 签名版本指针 |
| `manifest.json` | 签名版本清单 |
| `public-key.txt` | 对应的 Ed25519 公钥 |

数据仅包含公开字段，仓库与 CDN 地址由测试环境拦截。测试时钟固定为清单发布时间，确保有效期检查不受当前日期影响。

`AppUpdateApiTest.pythonPublisherOutputResolvesInAndroidAndSurvivesVerifiedCacheRestoration` 覆盖序列化、指针摘要、签名、渠道回退、版本字段与缓存恢复。发布脚本位于 `.github/scripts/publish_app_update.py`。
