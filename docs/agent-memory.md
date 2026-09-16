# Agent Conversation Memory

Checkpoint 14 为 NexusMind Agent 增加了基于 MySQL 的、Session 范围内的多轮对话记忆。它只解决跨 HTTP Request 的指代理解与上下文延续，不是长期用户画像、语义记忆或 Agent 执行轨迹存储。

## Agent Run 与 Conversation Session

一个 Conversation Session 可以包含多个 Agent Run：

```text
Session
├── Run 1
├── Run 2
└── Run 3
```

`sessionId` 是服务端生成的 UUID，在多个请求之间保持不变；`runId` 每次请求重新生成。每个 Run 都会重新创建 `AgentRunContext`、`AgentSourceRegistry`、tool-call 计数器、model-turn 计数器和 30 秒绝对 deadline。当前假设同一个 Session 同时最多有一个活跃请求，本阶段没有实现锁、版本控制或请求队列。

## Current Run Tool History 与 Cross-request Memory

当前 Run 内的 `AssistantMessage` tool calls 和 `ToolResponseMessage` 继续由 Spring AI `ToolExecutionResult.conversationHistory()` 维护，使模型可以完成 Search → Context → Final Answer 的多轮工具调用。

跨 Request 只持久化两类对话结果：

```text
USER
ASSISTANT (final answer only)
```

不会持久化 System Prompt、tool call、tool response、tool SSE event、run ID、invocation ID、工具定义或 SourceRegistry。新的问题如果仍需知识，模型应在当前 Run 再次调用工具。这样数据库保存的是 Conversation Outcome，而不是 Execution Trace。

## Persistence Schema

Flyway `V3__add_agent_conversation_memory.sql` 创建：

- `agent_session(session_id, knowledge_base_id, created_at, updated_at)`
- `agent_message(id, session_id, role, content, created_at)`

`role` 只允许 `USER` 和 `ASSISTANT`，`agent_message(session_id, id)` 支持按 Session 倒序读取最近消息。与 Knowledge 数据模型一致，两张表不使用数据库 Foreign Key，生命周期和 KnowledgeBase 所有权由应用层校验。

## Memory Window

`nexusmind.agent.memory.max-messages` 默认是 12，表示最多向当前 Agent 初始 Prompt 注入最近 12 条 User/Assistant 消息。SQL 使用：

```text
WHERE session_id = ?
ORDER BY id DESC
LIMIT ?
```

应用层随后反转为 oldest → newest，并以真正的 `UserMessage` / `AssistantMessage` 放在当前 `UserMessage` 之前。窗口只限制 Prompt；MySQL 中的旧消息不会被删除。历史只在第一个 Model Turn 注入一次，后续工具轮次由当前 conversation history 自然携带，避免重复拼接。

## Source ID 仍是 Run Scoped

`S1`、`S2` 等引用 ID 只在一次 Agent Run 内有效。即使两个请求属于同一个 Session，第二个 Run 也有全新的 `AgentSourceRegistry`，因此第二次请求的 `S1` 可以指向不同 Chunk。Source ID 与 Chunk ID 的映射不会写入 Session 数据库。

数据库保存用户实际看到的原始最终回答，包括当时的 `[S1]`。但是历史 Assistant 消息重新进入后续 Prompt 前，`HistoricalCitationSanitizer` 会确定性删除 `[S<number>]` 标记；数据库原文和当前 Run 输出均不修改。System Prompt 同时明确：只有当前 Run 工具返回的 Source ID 才能在本轮答案中引用，若用户提到旧 Source ID，应重新搜索。

## Successful Turn Transaction

一次 Run 获得最终自然语言回答后，服务只收集最终 Model Turn 的 Assistant 内容，不保存早期工具轮次可能出现的过渡文本。随后在一个 MySQL 事务中：

```text
insert USER
insert ASSISTANT final
touch agent_session.updated_at
```

事务成功后才发送 SSE `done`。工具失败、模型失败、超时、tool-call limit 超限或持久化失败均不会写入本轮 User/Assistant；持久化失败时客户端可能已经看到 `assistant_delta`，但会收到 `error` 而不会收到 `done`。因此 `done` 表示 Agent Run 和 Memory commit 都已完成。

## Isolation 与安全

- 带 `sessionId` 的请求必须通过 UUID 格式校验，且 Session 必须存在。
- Session 创建时绑定 KnowledgeBase；通过其他 KnowledgeBase 的 URL 使用它会被拒绝。
- Conversation history 是对话上下文，不是 System Policy；历史中的指令不能覆盖当前系统规则、工具安全边界或应用策略。
- Session 之间不共享消息，也不形成永久用户画像。
- 当前不支持同一 Session 的并发请求治理；这是后续 Production Engineering 范围。

## Request and SSE

原请求仍兼容：

```json
{"message":"你好"}
```

服务会创建新 Session。后续请求传回同一个 ID：

```json
{"sessionId":"<UUID>","message":"继续解释刚才的第二种情况"}
```

所有 Agent SSE event 同时携带 `runId` 和 `sessionId`。同一个请求中两者固定；同一 Session 的下一个请求保留 `sessionId`、生成新的 `runId`。

