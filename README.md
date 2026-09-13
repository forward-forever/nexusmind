# NexusMind

NexusMind 是一个个人 AI 应用项目，定位为 **AI Knowledge & Agent Platform**。

## 项目目标

项目将围绕私有知识库、检索增强生成（RAG）、Agent 与工程化能力逐步演进，最终用于简历展示、技术面试和现场演示。

## 当前阶段

V1 Basic RAG 已达到 **Feature Complete**：本地基础设施、Knowledge Domain/MySQL 持久化、文档摄取、Dense Vector Retrieval、无状态流式 RAG 和最小 Web UI 已形成完整闭环：

```text
Document Upload → Local Storage → Parser → Sliding Window Chunk → MySQL
MySQL Chunk → Embedding → Milvus HNSW → COSINE TopK
Question → Dense Retrieval → Context → qwen3.5-flash → SSE Answer + Citation
Browser → Knowledge Base → Upload → Process → Index → Streaming Answer → Source
```

支持 PDF、Markdown 和 UTF-8 TXT。Web UI 可创建 Knowledge Base、管理 Document 的 Process/Index 阶段，并通过 POST SSE 展示真实增量回答和稳定 Source ID Citation。

V1 的完整启动和人工验收步骤见 [`docs/v1-demo.md`](docs/v1-demo.md)。

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
- Apache PDFBox 3.0.8
- Vue 3 / TypeScript / Vite

## Roadmap

- V1 - Basic RAG（Feature Complete）
- V2 - Retrieval Quality（Evaluation Baseline In Progress；Hybrid Planned）
- V3 - Agent & Tool Calling（Planned）
- V4 - Production Engineering（Planned）
