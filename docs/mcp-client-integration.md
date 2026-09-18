# Controlled MCP Client Integration

Checkpoint 19 adds controlled consumption of remote MCP tools to NexusMind Agent. It does not turn NexusMind into an MCP server or a user-managed MCP marketplace.

## Roles and data flow

NexusMind is the MCP host and MCP client. The LLM never opens a connection to an MCP server. It only receives tool definitions and returns tool calls; NexusMind executes those calls through Spring AI.

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

## Transport scope

CP19 supports only remote Streamable HTTP with Spring AI's standard JDK HttpClient-based `spring-ai-starter-mcp-client`. It deliberately exposes no STDIO `command` or `args` configuration because allowing an application user to select `npx`, Python, or shell commands would grant process-launch authority.

Only MCP Tools are consumed. Resources, Prompts, Sampling, Elicitation, server mode, and user-managed server registration are out of scope.

## Startup discovery snapshot

When MCP is explicitly enabled, Spring AI performs MCP initialization and `tools/list`. NexusMind takes one immutable startup snapshot from `SyncMcpToolCallbackProvider` and builds the Agent catalog. Initialization or discovery failure fails startup instead of silently exposing zero tools.

Tool-list change notifications and runtime refresh are not used. Changes to the server or allowlist require an application restart.

MCP is disabled by default, so normal startup and `./mvnw test` do not contact an MCP server.

## Names and allowlist

Every external name is transformed to:

```text
mcp_<sanitized-server-name>_<sanitized-tool-name>
```

Only ASCII letters, digits, and `_` remain. Names longer than 64 characters are deterministically shortened with a stable SHA-256-derived suffix. Startup rejects duplicate names and any collision with a native Agent tool.

Discovery does not imply authorization. `nexusmind.mcp.allowed-tools` defaults to an empty list. Only final prefixed names in the allowlist reach the LLM. Unknown allowlist entries fail startup, making configuration mistakes visible.

The read-only endpoint below exposes only final names and allowed flags. It never returns connection URLs, credentials, raw schemas, descriptions, or tool results.

```bash
curl --fail --silent http://localhost:8080/api/agent/mcp/tools
```

Disabled response:

```json
{"enabled":false,"discoveredCount":0,"allowedCount":0,"tools":[]}
```

## Trust boundary and budgets

MCP tool descriptions and results are third-party, untrusted external data. The Agent system policy instructs the model that neither can override system policy, application rules, tool limits, or roles. This is defense in depth, not a claim that prompt injection is completely solved.

The final visible native and allowed MCP definitions—name, description, and input schema—are locally estimated with `NexusTokenEstimator`. Startup fails with `MCP_TOOL_DEFINITION_BUDGET_EXCEEDED` if they exceed `nexusmind.ai.context.tool-definition-reserve-tokens`; NexusMind never silently drops allowed tools or automatically enlarges the reserve.

`BudgetedMcpToolCallback` delegates protocol execution to Spring AI and then applies the CP18 budgets:

- `max-tokens-per-call` bounds one opaque MCP result.
- `max-tokens-per-run` is shared with native knowledge-tool results.
- An oversized result becomes a complete JSON envelope with `truncated=true`, an explicit NexusMind policy notice, and safely truncated textual content. Unknown MCP business schemas are never interpreted.
- MCP results never enter `AgentSourceRegistry`. `[S1]` citations remain exclusively knowledge-base chunk identities.

## Limits, timeout, failures, and logging

An MCP invocation counts exactly like a native invocation toward `maxToolCalls=5`. A mixed batch is rejected as a whole when it would exceed the limit.

The configured MCP request timeout must be shorter than the Agent absolute deadline. Before invoking MCP, NexusMind requires at least the configured request timeout to remain. Spring AI 2.0.1 does not expose a per-call dynamic timeout through this callback path, so the global short timeout remains a documented bound.

MCP calls are not wrapped in CP17 provider retry. External tools may have side effects, so timeout, transport, protocol, and remote tool errors fail fast through the generic `tool_error → error` lifecycle. Terminal cleanup releases the Agent session lease.

SSE and logs expose only safe fields: run ID, final prefixed tool name, duration, and outcome. Raw MCP arguments, results, schemas, URLs, headers, and credentials are excluded. `tool_start.arguments` is `{}` for MCP tools.

## Remote demo configuration

Choose a trusted, no-auth Streamable HTTP MCP server or keep credentials exclusively in the untracked `deploy/.env`. Do not commit a real remote URL if its path or query contains a token.

1. Identify the Streamable HTTP base URL and endpoint. For `https://example-mcp-host/some/path/mcp`, use base URL `https://example-mcp-host` and endpoint `/some/path/mcp`.
2. Configure discovery with an empty allowlist:

   ```bash
   NEXUSMIND_MCP_ENABLED=true
   MCP_DEMO_BASE_URL=https://example-mcp-host
   MCP_DEMO_ENDPOINT=/some/path/mcp
   MCP_ALLOWED_TOOLS=
   MCP_REQUEST_TIMEOUT=8s
   ```

3. Start the existing local application plus the demo profile:

   ```bash
   cd /Users/wude/IdeaProjects/nexusmind/nexusmind-server
   set -a
   source ../deploy/.env
   set +a
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local,mcp-demo
   ```

4. Call `GET /api/agent/mcp/tools` and copy the desired final `mcp_...` names.
5. Set a comma-separated allowlist, for example `MCP_ALLOWED_TOOLS=mcp_demo_search,mcp_demo_lookup`.
6. Restart NexusMind because discovery and allowlisting are startup snapshots.
7. Confirm the endpoint reports `allowed=true`, then ask Agent Chat a question relevant to the advertised external capability.
8. Observe generic `tool_start`, `tool_result`, `assistant_delta`, and `done` events. A successful MCP run has at least one tool call and two model turns.

No remote MCP URL was supplied or contacted during CP19 implementation. The automated protocol test uses a test-scope Spring AI WebMVC MCP server bound only to localhost, plus a fake ChatModel.

## Known limitations

- Only Streamable HTTP tool consumption is supported.
- No STDIO configuration is exposed.
- No Resources, Prompts, Sampling, or Elicitation integration exists.
- NexusMind is not an MCP server.
- There is no user-managed MCP registry, OAuth flow, or hot reload.
- MCP calls have no automatic retry.
- MCP results have no Source citation model.
- External descriptions and results remain untrusted.
- Dynamic per-invocation transport timeout is unavailable through the current callback integration.
- Observability finalization and package cleanup remain for Checkpoint 20.
