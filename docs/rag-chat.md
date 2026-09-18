# Stateless RAG Chat

Checkpoint 5 完成 NexusMind V1 后端 RAG 主链：

```text
Question
   ↓
Dense Retrieval
   ↓
Context Builder
   ↓
Citation Mapping
   ↓
System / User Prompt
   ↓
qwen3.5-flash
   ↓
Spring AI Streaming
   ↓
SSE
```

当前 V1 的 Retriever 仅为 Dense Retrieval，Chat 是无会话历史的 Stateless 模式。Citation 是 Java 建立的 Source ID 映射；Memory、Hybrid Retrieval、Rerank 与 Agent 均为 **Planned**。

## 配置

local profile 从环境变量读取 `DASHSCOPE_API_KEY` 与 `CHAT_BASE_URL`，不在配置文件或日志中保存密钥和真实业务空间 URL。ChatModel 固定为 `qwen3.5-flash`，temperature 为 `0.2`，通过 OpenAI-compatible `extra-body.enable_thinking=false` 关闭 thinking。

```yaml
nexusmind:
  ai:
    chat:
      model: qwen3.5-flash
      temperature: 0.2
      default-top-k: 5
      max-top-k: 10
      stream-timeout: 120s
      mvc-timeout: 150s
  rag:
    context:
      max-tokens: 12000
```

`rag.context.max-tokens` 是应用层 Token Budget 上限，实际可用 Context 还会扣除 System Prompt、当前 Question、输出保留和 Safety Margin；旧 `max-context-chars` 仅为兼容配置，不再参与 Context 构建。模型 stream timeout 与 Spring MVC async timeout 分开配置，并强制 `mvc-timeout > stream-timeout`，让模型超时后仍有时间发送 SSE `error` 并关闭响应。Spring AI 与 NexusMind 应用配置引用相同的 model 和 temperature，避免配置分叉。默认 profile 关闭 Chat、Vector 与 RAG，所以普通测试无需 MySQL、Milvus、网络或 API Key。

## Retrieval、Context 与 Citation

`RagChatService` 依赖稳定的 `RetrievalService`，不直接依赖 Dense 实现。Dense Retriever 把 Milvus `DenseVectorHit` 经 MySQL 可见性校验和文件名补齐后转换为 `RetrievalHit`；Context Builder 只接收上层 Hit。每个 Hit 同时携带 `score` 与 `scoreType`，V1 为 `COSINE`，避免未来把不同检索阶段的分数都误称为 similarity。每个真正进入上下文的完整 Chunk 按顺序获得 `S1`、`S2` 等稳定 ID。若加入下一个完整 Source 会超过 Token Budget，则按排名前缀停止；仅当排名第一的 Source 本身过大时截断其正文并保留元数据和截断标记。客户端只收到模型实际看过的 Source。

模型看到的 Source Block 包含文件名、可空页码、可空章节、Chunk ID、原始检索分数和正文。Milvus 对象、Embedding Vector、API Key 与无关数据库字段不会进入 Prompt。

`sources` 事件是引用元数据的事实来源。模型答案中的 `[S1]` 只是指针，前端应使用 Java 给出的 `S1 → chunkId → documentId → fileName → pageNo/sectionTitle` 映射，不应把模型自行描述的文件名或页码当作可信元数据。

## Prompt 边界

System Message 要求模型仅依据 Reference Context 回答；资料不足时明确说明；只能使用系统分配的 Source ID；不得创造来源；按用户语言回答。Reference Context 被明确标记为不可信数据，其中诸如 `Ignore previous instructions` 的内容不能覆盖 System Rule。

User Message 只承载清晰分隔的 `REFERENCE CONTEXT` 与 `USER QUESTION`。没有检索结果时不调用 ChatModel，而是保持同一 SSE 协议，返回空 `sources`、固定说明文本和 `done`。

## SSE 协议

接口：

```text
POST /api/knowledge-bases/{knowledgeBaseId}/rag/stream
Content-Type: application/json
Accept: text/event-stream
```

事件顺序：

- `sources`：生成前发送一次，只含真实进入 Context 的来源元数据，不含正文。
- `delta`：Spring AI ChatClient 的真实模型增量，不是把完整答案人工切片。
- `done`：正常结束，包含 model 与 elapsedMs。
- `error`：`sources` 已发送后的模型错误；发送后结束，不再发送 `done`。

KnowledgeBase 校验、Dense Retrieval 或 Context Build 在 Controller 返回 Flux 前同步执行，失败时仍由统一 HTTP JSON 异常处理。模型流开始后的异常已无法切换为 JSON 500，因此转为 SSE `error`。客户端断开会沿 Reactor subscription 取消下游模型流。

应用仍运行在 Spring MVC、Tomcat 与 `DispatcherServlet` 上。项目没有增加 `spring-boot-starter-webflux`；已有 Spring AI OpenAI Starter 传递提供 Reactor/WebFlux 客户端类型，Spring MVC 使用其异步 reactive return type 承载 SSE。

## 人工验证

启动 local profile 后，用已完成 Process 与 Index 的 KnowledgeBase 调用：

```bash
curl --no-buffer \
  --request POST \
  'http://localhost:8080/api/knowledge-bases/{KB_ID}/rag/stream' \
  --header 'Content-Type: application/json' \
  --header 'Accept: text/event-stream' \
  --data '{
    "question": "根据这份资料解释核心概念，并标注引用。",
    "topK": 5
  }'
```

`--no-buffer`（或 `-N`）很重要，否则 curl 可能缓冲输出。建议依次测试：

1. PDF 中有明确答案的问题，验证回答中的 `[S1]` 能映射到真实文件和页码。
2. 不使用 PDF 原句关键词的同义问题，观察 Dense Retrieval 的语义召回。
3. 与 PDF 完全无关的问题，期望回答“当前知识库资料不足以回答这个问题”。V1 尚未引入 score threshold，相关性边界主要由 System Prompt 约束。
