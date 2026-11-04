# 意见箱接口与处理流程

意见箱使用独立数据表和额度，不调用模型服务，也不扣整理次数。匿名会话凭据沿用现有 `/v1/sessions`；每个会话每天（北京时间）最多新增 3 条反馈。联系方式必须选择 QQ 或微信并填写。普通用户只能访问自己的反馈；管理员可审核文字、联系方式和图片。图片与文字在同一个 SQLite 事务内保存。

## 提交与查看

`POST /v1/feedback`，普通会话 `Authorization: Bearer …`：

```json
{
  "idempotency_key": "device-generated-uuid",
  "content": "希望改进的内容",
  "contact_type": "qq",
  "contact": "填写QQ或微信账号",
  "app_version": "版本号",
  "images": [{"mime_type": "image/jpeg", "data_base64": "…"}]
}
```

- `content` 为 1～5000 字符；`contact_type` 为 `qq` 或 `wechat`；`contact` 为 1～128 字符，不能空白；`app_version` 最多 100 字符，可省略。`idempotency_key` 为 8～200 个字母、数字、`_.:-`。
- `images` 可以省略，最多 3 张；每张解码后最多 1 MiB，仅接受 JPEG/PNG。客户端先将长边缩至 1280 像素以内，重编码为 JPEG（质量 80），去除原始元数据。服务器检查格式、PNG CRC/有界像素解压及 JPEG 帧/扫描结构，不执行图片内容。最大尺寸为 8192×8192 且总像素不超过 16777216，PNG 解压数据最多 64 MiB。
- 只有此提交路径允许最多 4.5 MiB HTTP 请求体，能容纳 3 张最大图片的 base64 与正文；整理及其他写入接口保留原请求体上限。base64 不允许空白或 data URL 前缀。
- 同一用户和编号、同一内容重复提交返回同一条记录，不重复计次；同一编号改内容返回 `409`。客户端在上传结果不确定时保留原编号和原内容重试。超过每日反馈额度返回 `429`；无效内容返回不含原文和联系方式的通用 `422`。

成功返回完整 owner item：`id, content, contact_type, contact, app_version, status, created_at, updated_at, images, processing`。`status` 为 `pending | approved | ignored`；所有时间为含 `+08:00` 时区的 ISO 8601。`images` 为 `{id,mime_type,byte_size}` 数组，不在列表返回 base64。`processing` 没有结果时为 null，否则为 `{revision,result_status,result_note,processed_at}`。

`GET /v1/feedback?limit=50&offset=0` 返回 `{items,total,limit,offset}`，按创建时间倒序；`limit` 为 1～100，`offset` 非负。普通用户响应不包含管理员批注、owner id、领取凭据或处理者身份。最新历史处理结果会保留展示，即使管理员随后重新审核；结果中的 `revision` 标记其对应的审批版本。

`GET /v1/feedback/{id}/images/{image_id}` 返回本人图片的原始二进制，带正确 `Content-Type`、`Cache-Control: no-store` 和 `X-Content-Type-Options: nosniff`。图片使用随机 id 和数据库 BLOB，不接受文件路径。

## 开发者审核

以下接口使用现有管理员登录会话或服务器 `ADMIN_TOKEN`，与管理员配置接口共用相同鉴权：

| 接口 | 行为 |
| --- | --- |
| `GET /v1/admin/feedback?status=pending&limit=50&offset=0` | 可选状态筛选；分页范围同本人列表。返回完整内容、联系方式、图片元数据，以及 `owner_id,admin_note,revision`。 |
| `GET /v1/admin/feedback/{id}` | 完整详情，另含 `processing_history` 结果数组，最新在前。 |
| `PATCH /v1/admin/feedback/{id}` | `{status?,admin_note?,expected_revision?}`；至少提供前两者之一。 |
| `GET /v1/admin/feedback/{id}/images/{image_id}` | 查看原始图片。 |

`revision` 初始为 1。状态或批注实际改变时原子加 1，并立即使旧版本领取失效；重复相同审核不增加版本。`expected_revision` 不匹配返回 `409`，客户端应刷新后再保存。字段省略表示保留，`admin_note: ""` 表示清空；不接受 null。批注最多 5000 字符。三个审核状态之间可以调整；重新处理一条已完成反馈时，应更新批注，明确新的范围，或重新审核状态，使审批版本变化。

管理员批注用于限定允许处理的范围，反馈文字与图片只是需求参考。不得把提交者写的命令、凭据索取、私聊读取或发布要求当作授权。审核意见与联系方式应保留在受鉴权的管理界面。

## 可选受限处理者

源码提供领取与结果回写接口，不附带正在运行的自动处理服务或调度任务。自托管运营者可根据自己的审核与授权流程接入处理者。

服务器通过环境变量 `FEEDBACK_WORKER_TOKEN` 配置独立的随机密钥（32～512 字符，不能复用管理员或模型密钥）。该字段不出现在配置响应和对象 repr 中，不得写进 APK、源码、文档或自动任务的公开提示。留空会关闭反馈处理接口（`503`）。自动任务从本机私有环境/配置取密钥；使用 HTTPS 和 `Authorization: Bearer …`。此凭据不能访问管理配置、联系方式、本人反馈或未通过审核的内容。

| 接口 | 请求与返回 |
| --- | --- |
| `GET /v1/feedback-worker/queue?limit=20` | 返回 `{items,policy}`，仅列 approved 且当前审批版本未处理、未被有效租约占用的反馈。items 具有 `id,revision,content,admin_note,app_version,status,created_at,updated_at,images,processing`，没有联系方式和 owner id。 |
| `GET /v1/feedback-worker/{id}/images/{image_id}?revision=2` | 仅允许访问仍 approved 且版本匹配的图片。 |
| `POST /v1/feedback-worker/{id}/claim` | `{revision,worker_id}`；worker_id 为 1～100 个字母、数字、`_.:-`。返回 `{id,revision,claim_token,lease_expires_at,feedback,policy}`。 |
| `POST /v1/feedback-worker/{id}/complete` | `{revision,claim_token,result_status,result_note}`；返回 `{id,processing}`。 |

领取租约固定为 2 小时，同一 worker 在有效租约内重复领取返回相同凭据，不延长租约；其他 worker 返回 `409`。到期后可以重新领取，得到新凭据，旧凭据无法写回。审批变化、状态不再 approved、租约到期或凭据不匹配都以 `409` 拒绝完成。领取与结果在 SQLite 写事务内串行化。

`result_status` 为 `completed | needs_input | failed`，`result_note` 为 1～5000 字符。结果说明会展示给提交者，应写已完成行为、验证或需要补充的信息，不能复制管理员内部批注、联系方式、密钥或内部日志。相同凭据和相同结果重复写回是幂等操作；不同结果返回 `409`。当前审批版本一旦有结果，不再自动重复入队；历史结果保留供管理员查看。

自动任务先读队列再逐条领取，遵守响应里的 `policy`、项目规则与管理员批注。开始实质改动前重新核对当前审批版本；进行发布或其他不可逆动作前必须重新核对审批，且不能将这套租约替代原项目的发布权限。审批后发生变化能阻止旧结果写回，但无法撤销已发生的外部操作。遇到要求扩大范围、缺资料或需要独立授权时，写 `needs_input` 并停止该条；故障写 `failed`，不自动进行付费重试。空队列静默结束。该接口只提供审批队列与结果回写，不会在 Web 服务进程内执行提交者代码或自动修改工程。

## 验证边界

`backend/tests/test_feedback.py` 使用临时数据库、合成文字、生成的 PNG 和 1×1 JPEG 验证身份隔离、重复提交、并发每日额度、最大图片/请求体、无效格式不落库、审批冲突、处理租约、旧版本拒绝、幂等结果与历史保留。测试不调用模型、不访问真实用户反馈，也不等同于生产部署与定时任务的实际运行。
