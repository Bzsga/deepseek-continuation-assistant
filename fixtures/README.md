# 导入验证样本

这些文件是原创合成测试数据，含演示人物与对话，没有真实用户聊天内容。

- `official-fragments-forks.json` 模拟 DeepSeek mapping/parent/message/fragments 格式，含 REQUEST、RESPONSE、THINK、FILE 与两个回答分支。源文件未标记当前分支，应用保留两条路径供选择。
- `mapping-current-branch.json` 覆盖显式 role、author.role、content.parts 和 current_node 标记。
- `long-content.json` 含超过 12000 字符的原文，用于验证分块后的完整拼接与来源 ID 保留。

官方导出结构依据来自 DeepShare 当前公开导入器及其测试样本。这里仅借助结构信息，业务实现为原创。未取得用户实际官方导出文件，因此真实账号导入仍需与合成样本验证区分。

https://raw.githubusercontent.com/Yorick-Ryu/deep-share/master/scripts/history/core.js

https://raw.githubusercontent.com/Yorick-Ryu/deep-share/master/tests/history/fixtures.cjs

导入支持 UTF-8 JSON、含 JSON 的 ZIP、mapping 树、显式 parent 的消息数组、嵌套 tree 和普通消息数组。REQUEST/RESPONSE 片段确定说话方，THINK 与独立 reasoning 字段排除。无法确定的角色保留为 unknown 并产生提示。附件保留可读元数据，附件正文不读取。

安全上限为输入 32 MiB、ZIP 解压总计 64 MiB、单文件 32 MiB、512 个 ZIP 项、每会话 100000 个节点、256 个分支、展开路径总计 500000 个节点、JSON 嵌套 128 层。不在文件系统解压，拒绝不安全路径、重复路径、重复 JSON 字段、冲突节点 ID、多父节点和循环引用。
