# 应用更新与 Fork 发布

原版更新清单为 [latest.json](https://digimonlegend.com/continuation-updates/latest.json)，APK 使用同站点的独立版本目录。开源仓库的下载入口为 [GitHub Releases](https://github.com/Bzsga/deepseek-continuation-assistant/releases)。

这些是原版分发渠道。Fork 不应向原版托管服务发布，也不能使用自己的签名覆盖原版应用。

## 手机端行为

带更新模块的客户端在启动或返回前台时静默检查，自动检查最多每五分钟一次；“设置 → 关于”也可手动检查。提示可以稍后处理，只有点击下载才开始传输。

下载保存到应用私有缓存，可取消、重试并复验已完成文件。下载与安装前检查大小、SHA-256、包名、版本、最低 Android 要求和与当前应用一致的签名。来源限定为配置的 HTTPS 版本目录，拒绝非预期地址和跳转。

系统安装由用户确认，必要时需先允许安装未知应用。正在整理、保存或处理外部导入时暂缓安装。更新不携带聊天或整理服务凭据，不消耗模型次数，不触发模型重试。

早期 preview.6 及以前版本没有更新模块，需要一次手动同签名升级；之后可以使用应用内检查。

## 清单协议

清单是严格 UTF-8 JSON，最多 32 KiB。

| 字段 | 要求 |
| --- | --- |
| `schemaVersion` | 1 |
| `packageName` | 与实际 APK 包名一致 |
| `versionCode`、`versionName` | 来自 APK 的真实版本 |
| `apkUrl` | 配置允许的同 origin、版本化 HTTPS APK 地址 |
| `apkSize` | 实际字节数，最多 64 MiB |
| `sha256` | 小写 64 位 SHA-256 |
| `minSdk` | APK 最低 API，当前 Android 源码为 26 |
| `releasedAt` | UTC ISO 8601 时间 |
| `changelog` | 最多 8 条、每条最多 200 字符的非空说明 |

清单使用 `Cache-Control: no-store`；版本化 APK 应不可覆盖，使用正确 APK Content-Type 和长期 immutable 缓存。先放置完整 APK，再原子替换清单，避免清单指向尚未上传完成的文件。

## 版本与签名

当前 Android 为 1.0.4 / versionCode 13。自 1.0.1 起使用递增数字 `major.minor.patch`，每次发布递增 versionCode，至少推进 patch。设置页显示实际 BuildConfig 版本。桥接测试属性 `updateTestVersionCode` / `updateTestVersionName` 只用于本地升级验证，不作为正式发布版本。

`tools/verify_release.py` 调用 Android SDK 的 `apksigner` 与 `aapt2`，生成和 APK 哈希绑定的验证元数据。现有工具固定了原版包名与公开证书摘要；Fork 必须替换为自己的身份与证书，不能仅改清单跳过验签。私钥和密码不属于源码。

## 发布工具

`tools/publish_release.py` 检查 APK/元数据哈希、ZIP 结构、版本及签名连续性，支持先进行本地预览：

```text
python tools/publish_release.py publish --apk <APK路径> --verified-metadata <验证JSON路径> --changelog-file <说明JSON路径> --dry-run
```

说明文件是 UTF-8 JSON 字符串数组。`--dry-run` 不上传，远端冲突仍需实际发布时检查。发布器的远程能力依赖运营者提供的可信 `--ssh-helper`；该 helper、服务器凭据和原版托管权限不随仓库提供。

Fork 使用前必须先检查并配置发布工具中的域名、静态目录、站点配置、包名和证书约束，再配置 Android 的更新清单与下载来源限制。构建签名见 [building.md](building.md)。不要直接把为原版编写的托管准备流程运行到其他现有网站。

发布流程按上传暂存、核对哈希、取得发布锁、落盘不可变版本目录、原子更新清单的顺序进行。相同版本和相同内容可以幂等重试；同版不同内容、降版或签名变化会拒绝。更新发布与整理后端配置、数据库和账务分离。

合成发布器回归位于 `tools/tests/test_publish_release.py`；既有发行验证范围见 [README](../README.md#验证状态与局限)。这份文档不包含原版部署记录或私有运维路径。
