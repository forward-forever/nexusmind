# NexusMind

NexusMind 是一个个人 AI 应用项目，定位为 **AI Knowledge & Agent Platform**。

## 项目目标

项目将围绕私有知识库、检索增强生成（RAG）、Agent 与工程化能力逐步演进，最终用于简历展示、技术面试和现场演示。

## 当前阶段

V1 Basic RAG、V2 Retrieval Quality 与 V3 Agent 均已完成并冻结；V4 Production Engineering 正在进行：

```text
Document Upload → Local Storage → Parser → Sliding Window Chunk → MySQL
MySQL Chunk → Dense / BM25 → Application RRF → Cross-Encoder Rerank
Question → Configured RetrievalService → Context → qwen3.5-flash → SSE Answer + Citation
Browser → Knowledge Base → Upload → Process → Index → RAG + Retrieval Lab
Agent → qwen3.5-flash tool decision → KnowledgeSearchTool / DocumentContextTool
      → Multi-step tool loop → Conversation Memory → final answer + Tool Trace
HTTP Process / Index → MySQL Durable Task → Background Worker → Recovery
```

支持 PDF、Markdown 和 UTF-8 TXT。Web 端是一个 tabbed NexusMind Workbench：`Knowledge` 管理 Knowledge Base 与文档，`Agent Chat` 展示 Tool Calling 与跨轮 Session，`RAG Chat` 保留固定 RAG，`Retrieval Lab` 并排检查 DENSE、BM25、HYBRID_RRF、HYBRID_RERANK 的单 Query 结果与 provenance。

V1 的完整启动和人工验收步骤见 [`docs/v1-demo.md`](docs/v1-demo.md)，V2 正式实验结论见 [`docs/v2-retrieval-quality.md`](docs/v2-retrieval-quality.md)。
V3 的完整架构、Guardrails、Behavior Evaluation 与限制见 [`docs/v3-agent.md`](docs/v3-agent.md)。
V4 异步文档任务的状态机、幂等与恢复设计见 [`docs/async-document-tasks.md`](docs/async-document-tasks.md)。

## Retrieval Architecture

```text
                        ┌→ Dense ────┐
Query ──────────────────┤            ├→ RRF → TopN → Rerank
                        └→ BM25 ─────┘
                                      │
                                      ↓
                                 Final TopK
                                      │
                                      ↓
                                RAG Context
                                      │
                                      ↓
                                   ChatModel
```

当前产品默认路径仍为 `Query → Dense → Context → ChatModel`。其他 Retriever 通过配置、Debug Search API 和 Retrieval Lab 显式使用；当前 Benchmark 没有证明更复杂路径值得成为默认值。

## Agent Architecture

```text
Document → Parse / Chunk → MySQL + Milvus → Retrieval Layer
                                              ↓
                                    Knowledge Tools
                                              ↓
User → Agent LLM ↔ Tool Calling Loop → Conversation Memory
                                              ↓
                                  SSE Answer + Sources → Web UI
```

V1 Fixed RAG 与 V3 Agent 是两个独立入口：前者固定执行 Retrieval → Context → Chat，后者由模型决定是否搜索、是否扩展文档上下文以及何时完成回答。

## 高层架构设想

- `nexusmind-server`：Java 后端服务
- `nexusmind-web`：Vue 3 单页 Tabbed Workbench（Knowledge、Agent Chat、RAG Chat、Retrieval Lab）
- `deploy`：MySQL 与 Milvus 本地开发环境
- `docs`：架构、设计决策与评估文档

## 技术栈

- Java 17
- Maven
- Spring Boot 4.1.0
- Spring AI 2.0.1
- Spring MVC
- MyBatis 4.1.0 / Flyway
- MySQL 8.4.11 / Milvus 2.6.22
- Alibaba Cloud Model Studio OpenAI-compatible Embedding
- Alibaba Cloud Model Studio OpenAI-compatible Chat
- Alibaba Cloud Model Studio Text Rerank
- Apache PDFBox 3.0.8
- Vue 3 / TypeScript / Vite

## Delivery Status

- V1 - Basic RAG ✅
- V2 - Retrieval Quality ✅
  - Dense Retrieval
  - BM25
  - Hybrid RRF
  - Cross-Encoder Rerank
  - Golden Dataset Evaluation
  - HitRate / Recall / MRR
  - Retrieval Debug Panel
- V3 - Agent ✅
  - Real LLM Function Calling
  - User-Controlled Tool Loop
  - KnowledgeSearchTool / DocumentContextTool
  - Multi-Step Tool Chaining
  - Conversation Memory
  - Structured Agent SSE / Tool Trace
  - Agent Guardrails
  - Agent Behavior Evaluation
- V4 - Production Engineering（In Progress）
  - Durable Async Document Processing / Indexing ✅
