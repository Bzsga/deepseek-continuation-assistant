# Deepseek续聊助手

把旧聊天整理成可编辑、可追溯的续聊档案，再带回 DeepSeek 新会话。

作者：半盏丶时光  
由GPT-6制作

**Android 8.0+ · 1.0.2（versionCode 11）· MIT**

这是独立开发的辅助工具，**不是 DeepSeek 官方应用，也不隶属于 DeepSeek**。实际聊天仍在 DeepSeek 官方应用或网页版中进行。

## 下载与使用

在 [GitHub Releases](https://github.com/Bzsga/deepseek-continuation-assistant/releases) 获取安装包和版本说明。当前作为早期测试版提供；安装时可能需要在 Android 系统中允许安装未知应用。

1. 在 [DeepSeek 网页版](https://chat.deepseek.com/) 下载聊天导出 ZIP，从浏览器下载列表或文件管理器分享给本助手，无需解压。应用首页提供分步教程，也支持手动选择文件。
2. 选择一段聊天，点击“开始智能整理”。也可以选择“先带走原文”，在本机保存所选内容，不调用模型。
3. 检查并按需编辑结果，选择“复制并打开 DeepSeek”或“保存续聊文件”，在官方新会话中粘贴或添加 TXT 文件。

已有安装可在“设置 → 关于”检查原版更新。[原版更新清单](https://digimonlegend.com/continuation-updates/latest.json) 与 GitHub 发布页面是不同的分发入口。

## 能做什么

- 导入 DeepSeek ZIP/JSON、粘贴记录或使用合成示例，保留消息角色、分支和来源。
- 将所选消息整理为背景、人物、决定、近期话题和重要原话，区分用户陈述、建议与推测。
- 编辑或固定条目、查看来源、恢复历史版本，并保留已完成的分段结果。
- 导出完整续聊文本，备份和恢复本地资料；可使用配套服务或自行配置兼容 API。

## 整理服务与费用

原版配套服务地址为 [continuation-api](https://digimonlegend.com/continuation-api)。免费整理同时受全站和个人每日次数限制，**以应用实时显示为准，不承诺永久有额度或持续可用**。长聊天可能分成多次请求；已经发出的失败或不确定请求也会计次。北京时间零点重置，排队取消可释放尚未使用的预留。

也可在设置中填写自己的 HTTPS Chat Completions 兼容接口、模型和 API Key。手机直接请求该服务商，费用与数据处理规则由该服务商决定。“测试连通”只发送短测试文本，也可能产生少量费用；保存设置本身不会开始整理。

## 隐私与资料

导入、编辑和“先带走原文”在本机完成。智能整理才会发送所选消息：配套模式经过后端发送给上游，自己配置 API 时由手机直连。配套后端在任务终态 24 小时后清除任务正文与结果，仍保留计次和账务元数据；上游服务商有其独立保留政策。

密钥由 Android Keystore 保护，普通备份不包含服务密钥。反馈内容、联系方式、图片和受限技术诊断有独立的上传与保留规则，详见 [隐私说明](docs/privacy.md)。不要在公开 Issue 中附上未经脱敏的聊天、备份或密钥。

## 源码与构建

Android 使用 Kotlin、Jetpack Compose 和 Room；后端使用 FastAPI、SQLite。

- [构建、安装与测试](docs/building.md)：JDK 17、Gradle 8.9、Android SDK 35；仓库不带 Gradle wrapper。
- [自托管后端](docs/backend.md)：本地模拟、真实服务配置与管理。
- [协议](docs/contracts.md)、[意见箱](docs/feedback.md)、[诊断](docs/diagnostics.md)、[更新通道](docs/app-updates.md)。

Fork 发布前请配置自己的后端、更新地址、应用身份及签名。原版私钥、生产凭据和私有运维配置不包含在仓库中，自行签名的包不能覆盖原版安装。

## 验证状态与局限

已有验证包括后端 protocol/usage/service **191 项测试通过**，Android release 构建、`lintRelease`，以及导入、持久化、备份恢复和更新的定向测试。合成资料曾通过真实模型整理。上述是既有验证记录，**不是本次开源整理重新跑完全部测试的声明**，也不能证明所有手机、导出格式或模型均兼容。

- AI 摘要可能省略、误解或产生推断，请核对关键内容；来源校验不能保证语义完全正确。
- 上游格式错误、内容过滤、网关或网络故障仍可能中断整理；不会自动重发结果不确定的付费请求。
- 单次聊天导入文件上限为 32 MiB。大型会话和备份仍受设备内存限制，没有任意规模恒定内存保证。
- DeepSeek 官方导出界面或格式可能变化；官方 App 接收文件的效果与设备、账户状态有关。

1.0.2 的 Android 改动仅为公开发布前的教程隐私清理：去除图片元数据，裁掉演示视频中无关的下载列表，并重新打包。它沿用 1.0.1 的存储修复；后端包含后续 JSON 兼容和诊断修复。新包的实际验证与发布状态以 [v1.0.2 Release](https://github.com/Bzsga/deepseek-continuation-assistant/releases/tag/v1.0.2) 说明为准。

## 许可与反馈

项目原创代码采用 [MIT 许可证](LICENSE)。第三方依赖及教程中涉及的第三方界面、商标和素材不因此改用 MIT，见 [NOTICE](NOTICE.md)、[后端依赖声明](backend/THIRD_PARTY_NOTICES.txt) 及应用内开源致谢。

欢迎提交可复现的问题和改进建议。请说明应用版本、Android 版本、操作步骤与预期结果，优先使用合成样例。
