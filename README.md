# NexusMind

NexusMind 是一个个人 AI 应用项目，定位为 **AI Knowledge & Agent Platform**。

## 项目目标

项目将围绕私有知识库、检索增强生成（RAG）、Agent 与工程化能力逐步演进，最终用于简历展示、技术面试和现场演示。

## 当前阶段

V1 Basic RAG 与 V2 Retrieval Quality 均已完成。文档摄取、可重建检索索引、无状态流式 RAG、统一 Retrieval Evaluation 和单 Query Retrieval Debug 已形成完整闭环：

```text
Document Upload → Local Storage → Parser → Sliding Window Chunk → MySQL
MySQL Chunk → Dense / BM25 → Application RRF → Cross-Encoder Rerank
Question → Configured RetrievalService → Context → qwen3.5-flash → SSE Answer + Citation
Browser → Knowledge Base → Upload → Process → Index → RAG + Retrieval Lab
```

支持 PDF、Markdown 和 UTF-8 TXT。Web UI 可创建 Knowledge Base、管理 Document 的 Process/Index 阶段，通过 POST SSE 展示真实增量回答和稳定 Source ID Citation，并并排检查 DENSE、BM25、HYBRID_RRF、HYBRID_RERANK 的单 Query 结果与 provenance。

V1 的完整启动和人工验收步骤见 [`docs/v1-demo.md`](docs/v1-demo.md)，V2 正式实验结论见 [`docs/v2-retrieval-quality.md`](docs/v2-retrieval-quality.md)。

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

## 高层架构设想

- `nexusmind-server`：Java 后端服务
- `nexusmind-web`：Vue 3 单页 V1 Demo
- `deploy`：MySQL 与 Milvus 本地开发环境
- `docs`：架构、设计决策与评估文档

## 技术栈

- Java 17
- Maven
- Spring Boot 4.1.0
- Spring AI 2.0.0
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
- V3 - Agent & Tool Calling（Planned）
- V4 - Production Engineering（Planned）
