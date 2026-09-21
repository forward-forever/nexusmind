# Controlled MCP Client Integration（受控的 MCP 客户端集成）

Checkpoint 19 adds controlled consumption of remote MCP tools to NexusMind Agent. It does not turn NexusMind into an MCP server or a user-managed MCP marketplace.

Checkpoint 19 为 NexusMind Agent 增加了对远程 MCP 工具的受控消费。它不会把 NexusMind 变成一个 MCP 服务器，也不会变成一个用户自管理的 MCP 市场。

## Roles and data flow（角色与数据流）

NexusMind is the MCP host and MCP client. The LLM never opens a connection to an MCP server. It only receives tool definitions and returns tool calls; NexusMind executes those calls through Spring AI.

NexusMind 是 MCP 主机和 MCP 客户端。LLM 永远不会与 MCP 服务器建立连接。它只接收工具定义并返回工具调用；NexusMind 通过 Spring AI 执行这些调用。

```text
                         ┌─ search_knowledge_base
                         │
User → Agent → LLM ──────┼─ get_document_context
                         │
                         └─ mcp_xxx_tool
                                  │
                                  ▼
                             MCP Client
                                  │
                           Streamable HTTP
                                  │
                                  ▼
                           External MCP Server
```

The result returns through the existing user-controlled `ToolCallingManager` loop and is subject to the same tool-call limit, absolute deadline, final prompt validation, and run-scoped tool-result budget as native Java tools.

结果通过现有的、由用户控制的 `ToolCallingManager` 循环返回，并与原生 Java 工具一样，受相同的工具调用上限、绝对截止时间、最终提示词校验和 run 级工具结果预算约束。

## Transport scope（传输范围）

CP19 supports only remote Streamable HTTP with Spring AI's standard JDK HttpClient-based `spring-ai-starter-mcp-client`. It deliberately exposes no STDIO `command` or `args` configuration because allowing an application user to select `npx`, Python, or shell commands would grant process-launch authority.

CP19 只支持使用 Spring AI 标准的、基于 JDK HttpClient 的 `spring-ai-starter-mcp-client` 来访问远程 Streamable HTTP。它刻意不暴露 STDIO 的 `command` 或 `args` 配置，因为允许应用使用者选择 `npx`、Python 或 shell 命令就等于授予了启动进程的权限。

Only MCP Tools are consumed. Resources, Prompts, Sampling, Elicitation, server mode, and user-managed server registration are out of scope.

只消费 MCP Tools。Resources、Prompts、Sampling、Elicitation、server 模式以及用户自管理的服务器注册都不在范围内。

## Startup discovery snapshot（启动期发现快照）

When MCP is explicitly enabled, Spring AI performs MCP initialization and `tools/list`. NexusMind takes one immutable startup snapshot from `SyncMcpToolCallbackProvider` and builds the Agent catalog. Initialization or discovery failure fails startup instead of silently exposing zero tools.

当 MCP 被显式启用时，Spring AI 会执行 MCP 初始化和 `tools/list`。NexusMind 从 `SyncMcpToolCallbackProvider` 取得一份不可变的启动快照并构建 Agent 目录。初始化或发现失败会导致启动失败，而不是静默地暴露零个工具。

Tool-list change notifications and runtime refresh are not used. Changes to the server or allowlist require an application restart.

不使用工具列表变更通知和运行时刷新。对服务器或白名单的修改需要重启应用。

MCP is disabled by default, so normal startup and `./mvnw test` do not contact an MCP server.

MCP 默认禁用，因此正常启动和 `./mvnw test` 都不会连接 MCP 服务器。

## Names and allowlist（名称与白名单）

Every external name is transformed to:

每个外部名称都会被转换为：

```text
mcp_<sanitized-server-name>_<sanitized-tool-name>
```

Only ASCII letters, digits, and `_` remain. Names longer than 64 characters are deterministically shortened with a stable SHA-256-derived suffix. Startup rejects duplicate names and any collision with a native Agent tool.

只保留 ASCII 字母、数字和 `_`。超过 64 个字符的名称会用基于 SHA-256 的稳定后缀做确定性缩短。启动时会拒绝重名，以及与原生 Agent 工具的任何冲突。

Discovery does not imply authorization. `nexusmind.mcp.allowed-tools` defaults to an empty list. Only final prefixed names in the allowlist reach the LLM. Unknown allowlist entries fail startup, making configuration mistakes visible.

发现并不等于授权。`nexusmind.mcp.allowed-tools` 默认为空列表。只有白名单中的最终带前缀名称才会传给 LLM。白名单中的未知条目会导致启动失败，从而让配置错误可见。

The read-only endpoint below exposes only final names and allowed flags. It never returns connection URLs, credentials, raw schemas, descriptions, or tool results.

下面这个只读端点只暴露最终名称和 allowed 标志。它永远不会返回连接 URL、凭据、原始 schema、描述或工具结果。

```bash
curl --fail --silent http://localhost:8080/api/agent/mcp/tools
```

Disabled response:

禁用时的响应：

```json
{"enabled":false,"discoveredCount":0,"allowedCount":0,"tools":[]}
```

## Trust boundary and budgets（信任边界与预算）

MCP tool descriptions and results are third-party, untrusted external data. The Agent system policy instructs the model that neither can override system policy, application rules, tool limits, or roles. This is defense in depth, not a claim that prompt injection is completely solved.

MCP 工具的描述和结果是来自第三方的、不可信的外部数据。Agent 系统策略会指示模型：两者都不能覆盖系统策略、应用规则、工具限制或角色。这是纵深防御，并不意味着提示词注入已被完全解决。

The final visible native and allowed MCP definitions—name, description, and input schema—are locally estimated with `NexusTokenEstimator`. Startup fails with `MCP_TOOL_DEFINITION_BUDGET_EXCEEDED` if they exceed `nexusmind.ai.context.tool-definition-reserve-tokens`; NexusMind never silently drops allowed tools or automatically enlarges the reserve.

最终可见的原生工具定义和已允许的 MCP 工具定义——名称、描述和输入 schema——都会用 `NexusTokenEstimator` 在本地估算。如果它们超出 `nexusmind.ai.context.tool-definition-reserve-tokens`，启动会以 `MCP_TOOL_DEFINITION_BUDGET_EXCEEDED` 失败；NexusMind 永远不会静默丢弃已允许的工具，也不会自动扩大预留。

`BudgetedMcpToolCallback` delegates protocol execution to Spring AI and then applies the CP18 budgets:

`BudgetedMcpToolCallback` 把协议执行委托给 Spring AI，然后施加 CP18 的预算：

- `max-tokens-per-call` bounds one opaque MCP result.
  - `max-tokens-per-call` 限制单条不透明的 MCP 结果。
- `max-tokens-per-run` is shared with native knowledge-tool results.
  - `max-tokens-per-run` 与原生知识工具的结果共享。
- An oversized result becomes a complete JSON envelope with `truncated=true`, an explicit NexusMind policy notice, and safely truncated textual content. Unknown MCP business schemas are never interpreted.
  - 超限的结果会变成一个完整的 JSON 封装，带有 `truncated=true`、一条明确的 NexusMind 策略提示，以及被安全截断的文本内容。未知的 MCP 业务 schema 永远不会被解析。
- MCP results never enter `AgentSourceRegistry`. `[S1]` citations remain exclusively knowledge-base chunk identities.
  - MCP 结果永远不会进入 `AgentSourceRegistry`。`[S1]` 引用始终只代表知识库的 chunk 身份。

## Limits, timeout, failures, and logging（限制、超时、故障与日志）

An MCP invocation counts exactly like a native invocation toward `maxToolCalls=5`. A mixed batch is rejected as a whole when it would exceed the limit.

一次 MCP 调用与一次原生调用在计入 `maxToolCalls=5` 时完全等价。当混合批次会超出该上限时，整个批次都会被拒绝。

The configured MCP request timeout must be shorter than the Agent absolute deadline. Before invoking MCP, NexusMind requires at least the configured request timeout to remain. Spring AI 2.0.1 does not expose a per-call dynamic timeout through this callback path, so the global short timeout remains a documented bound.

配置的 MCP 请求超时必须短于 Agent 的绝对截止时间。在调用 MCP 之前，NexusMind 要求至少还剩余配置的请求超时时间。Spring AI 2.0.1 无法通过这条回调路径暴露按调用动态设置的超时，因此全局的短超时仍然是一个有明确记录的边界。

MCP calls are not wrapped in CP17 provider retry. External tools may have side effects, so timeout, transport, protocol, and remote tool errors fail fast through the generic `tool_error → error` lifecycle. Terminal cleanup releases the Agent session lease.

MCP 调用不会被包在 CP17 的提供商重试里。外部工具可能有副作用，因此超时、传输、协议和远程工具错误都会通过通用的 `tool_error → error` 生命周期快速失败。终止清理会释放 Agent 会话租约。

SSE and logs expose only safe fields: run ID, final prefixed tool name, duration, and outcome. Raw MCP arguments, results, schemas, URLs, headers, and credentials are excluded. `tool_start.arguments` is `{}` for MCP tools.

SSE 和日志只暴露安全字段：run ID、最终带前缀的工具名、耗时和结果状态。原始 MCP 参数、结果、schema、URL、请求头和凭据都被排除。对于 MCP 工具，`tool_start.arguments` 为 `{}`。

## Remote demo configuration（远程演示配置）

Choose a trusted, no-auth Streamable HTTP MCP server or keep credentials exclusively in the untracked `deploy/.env`. Do not commit a real remote URL if its path or query contains a token.

选择一个可信的、无需认证的 Streamable HTTP MCP 服务器，或者把凭据只保存在未被版本跟踪的 `deploy/.env` 中。如果真实远程 URL 的路径或查询串中含有 token，不要提交它。

1. Identify the Streamable HTTP base URL and endpoint. For `https://example-mcp-host/some/path/mcp`, use base URL `https://example-mcp-host` and endpoint `/some/path/mcp`.
   - 确定 Streamable HTTP 的基础 URL 和端点。对于 `https://example-mcp-host/some/path/mcp`，基础 URL 为 `https://example-mcp-host`，端点为 `/some/path/mcp`。
2. Configure discovery with an empty allowlist:
   - 用空白名单配置发现：

   ```bash
   NEXUSMIND_MCP_ENABLED=true
   MCP_DEMO_BASE_URL=https://example-mcp-host
   MCP_DEMO_ENDPOINT=/some/path/mcp
   MCP_ALLOWED_TOOLS=
   MCP_REQUEST_TIMEOUT=8s
   ```

3. Start the existing local application plus the demo profile:
   - 在现有本地应用的基础上加上 demo profile 启动：

   ```bash
   cd /Users/wude/IdeaProjects/nexusmind/nexusmind-server
   set -a
   source ../deploy/.env
   set +a
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local,mcp-demo
   ```

4. Call `GET /api/agent/mcp/tools` and copy the desired final `mcp_...` names.
   - 调用 `GET /api/agent/mcp/tools`，复制所需的最终 `mcp_...` 名称。
5. Set a comma-separated allowlist, for example `MCP_ALLOWED_TOOLS=mcp_demo_search,mcp_demo_lookup`.
   - 设置逗号分隔的白名单，例如 `MCP_ALLOWED_TOOLS=mcp_demo_search,mcp_demo_lookup`。
6. Restart NexusMind because discovery and allowlisting are startup snapshots.
   - 重启 NexusMind，因为发现和白名单都是启动期的快照。
7. Confirm the endpoint reports `allowed=true`, then ask Agent Chat a question relevant to the advertised external capability.
   - 确认该端点报告 `allowed=true`，然后在 Agent Chat 中提一个与所公布的外部能力相关的问题。
8. Observe generic `tool_start`, `tool_result`, `assistant_delta`, and `done` events. A successful MCP run has at least one tool call and two model turns.
   - 观察通用的 `tool_start`、`tool_result`、`assistant_delta` 和 `done` 事件。一次成功的 MCP run 至少有一次工具调用和两个模型轮次。

No remote MCP URL was supplied or contacted during CP19 implementation. The automated protocol test uses a test-scope Spring AI WebMVC MCP server bound only to localhost, plus a fake ChatModel.

在 CP19 实现期间，没有提供也没有访问任何远程 MCP URL。自动化协议测试使用一个仅绑定到 localhost 的 test scope Spring AI WebMVC MCP 服务器，外加一个假的 ChatModel。

## Known limitations（已知局限）

- Only Streamable HTTP tool consumption is supported.
  - 只支持消费 Streamable HTTP 工具。
- No STDIO configuration is exposed.
  - 不暴露任何 STDIO 配置。
- No Resources, Prompts, Sampling, or Elicitation integration exists.
  - 不存在 Resources、Prompts、Sampling 或 Elicitation 的集成。
- NexusMind is not an MCP server.
  - NexusMind 不是 MCP 服务器。
- There is no user-managed MCP registry, OAuth flow, or hot reload.
  - 没有用户自管理的 MCP 注册表、OAuth 流程或热重载。
- MCP calls have no automatic retry.
  - MCP 调用没有自动重试。
- MCP results have no Source citation model.
  - MCP 结果没有来源引用模型。
- External descriptions and results remain untrusted.
  - 外部描述和结果仍然不可信。
- Dynamic per-invocation transport timeout is unavailable through the current callback integration.
  - 通过当前的回调集成无法实现按调用动态设置的传输超时。
- Observability finalization and package cleanup remain for Checkpoint 20.
  - 可观测性收尾和包结构清理留待 Checkpoint 20。
