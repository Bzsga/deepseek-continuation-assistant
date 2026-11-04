# 私有失败诊断与离线重放

配套后端可为模型调用结果为 `failed` 或 `uncertain` 的请求保留排障副本；成功请求和模拟请求不额外留存。它独立于手机技术日志和常规监控元数据，没有公网读取接口，也不覆盖手机直连服务商的 BYOK 请求。

## 留存范围与期限

- `FAILURE_ARTIFACTS_ENABLED=true` 默认开启；`FAILURE_ARTIFACTS_TTL_HOURS=24` 默认保留 24 小时，可配置为 1～168 的整数。期限从副本保存时开始；同一任务重复保存不会延长期限，修改配置不追改已有副本的到期时间。
- 私有 SQLite 文件名为 `failure-artifacts.sqlite3`，与 `DB_PATH` 指向的任务库同目录。POSIX 下父目录须为 `0700`、文件为 `0600`；工具拒绝不安全权限或符号链接，不自动修改共享父目录权限。数据库不随源码、APK 或公开报告发布。
- 保存所选分段的消息、来源编号映射、必要模型请求体与配置快照、固定错误分类、HTTP 状态和有界原始响应。响应可能包含上游返回的推理字段；这是含私人正文的排障资料，并非脱敏统计日志。
- 不保存 HTTP headers、鉴权头或服务配置中的密钥字段；请求字段受白名单限制，已知服务凭据及其常见 JSON 转义形式会被替换为 `[REDACTED]`。这不等于匿名化全部聊天内容或识别所有未知秘密。
- 单份序列化副本上限为 **2 MiB**，包含请求、来源和 Base64 编码的响应；超限或存储失败时不保存该副本。响应本身还受 `MAX_RESPONSE_BYTES` 限制（默认 64 KiB，可配 4～256 KiB），超出时只保留已读取的有界前缀并标记截断，不能把 2 MiB 当作完整响应保证。
- 超时、断网、进程强杀或采集失败可能缺少请求、响应或整个副本。`failure_capture_state` 区分 `saved`、`disabled`、`unavailable`；已保存副本另有 `request_present`、`body_read_complete`、`truncated`、`redacted`、`complete_original` 等标记。缺失不代表模型没有执行，也不能补造启用前的历史回复。

启动、周期清理和本地读取都会清理到期正文；到期后拒绝读取。关闭新采集仍清理已有副本，停机期间到期的资料在下次启动或读取时补清理。清理保留不透明编号、任务标识的哈希与到期时间，以阻止同一过期任务重新取得留存窗口。此机制不控制上游服务商或运营者另行制作的备份。

## 离线重放的含义

`replay` 用当前解析器读取已保存的响应，检查响应包、JSON、条目结构和来源编号等协议约束。不请求模型、不增加次数、不修改原任务或账务，也不把重放通过的结果写回手机。它不核实摘要是否忠于原文、是否遗漏事实或语义是否正确；来源编号有效不等于事实有依据。

截断或未读完的响应不能按完整结果重放。脱敏副本可被解析，但不能宣称是逐字原件；即使 `complete_original=true`，也只说明请求与响应采集完整且未脱敏，不代表模型内容正确。重放输出只含状态、计数和受限诊断，不输出摘要或来源正文。

## 服务器本地命令

在 `backend` 目录使用服务对应的 Python 环境，以数据库所属服务账号执行。以下路径为服务器部署示例，`<artifact-id>` 替换为 `list` 返回的编号或诊断中的 `failure_capture_id`。

```sh
python -m app.failure_artifacts --path /var/lib/deepseek-continuation/failure-artifacts.sqlite3 list --limit 20
python -m app.failure_artifacts --path /var/lib/deepseek-continuation/failure-artifacts.sqlite3 replay "<artifact-id>"
python -m app.failure_artifacts --path /var/lib/deepseek-continuation/failure-artifacts.sqlite3 inspect "<artifact-id>"
```

`list` 只列未过期副本的元数据，默认 20 条，上限 100 条；`inspect` 默认返回少量元数据及离线重放结果。只有显式加上 `--include-content` 才输出所选副本的私有内容，响应字节位于 `response.body_base64`：

```sh
python -m app.failure_artifacts --path /var/lib/deepseek-continuation/failure-artifacts.sqlite3 inspect "<artifact-id>" --include-content
```

正文检查须在有权限的私有环境执行，输出不得粘贴到公开 Issue 或常规监控报告。以上命令可能清理诊断库中的到期副本并创建 SQLite 临时日志，须保留服务账号的文件归属；它们不会修改原整理任务。离线重放未通过时退出码为 `2`，不意味着已重发请求。

## 受限远端入口

在项目根目录使用 [远端工具](../tools/failure_artifact_remote.py)，显式传入自己维护的可信 Python SSH helper（须提供 `connect()`）；不要从失败正文或反馈中取得或执行 helper。

```sh
python tools/failure_artifact_remote.py --ssh-helper "/private/trusted_ssh_helper.py" list --limit 20
python tools/failure_artifact_remote.py --ssh-helper "/private/trusted_ssh_helper.py" replay "<artifact-id>"
python tools/failure_artifact_remote.py --ssh-helper "/private/trusted_ssh_helper.py" inspect "<artifact-id>"
python tools/failure_artifact_remote.py --ssh-helper "/private/trusted_ssh_helper.py" inspect "<artifact-id>" --include-content
```

工具经 SSH 在服务器运行同一离线命令，不下载整库、不调用模型。当前部署约定为 `/opt/deepseek-continuation`、该目录下的 `venv/bin/python`、`/var/lib/deepseek-continuation/failure-artifacts.sqlite3`，并通过 `runuser` 以 `deepseek-continuation` 服务账号执行；自托管环境不同时须先适配工具中的固定路径和账号。

实现入口：[留存与重放](../backend/app/failure_artifacts.py)、[任务接线与清理](../backend/app/runner.py)、[配置](../backend/app/config.py)。手机技术日志的独立范围见 [诊断说明](diagnostics.md)。
