# NexusMind

NexusMind 是一个规划中的个人 AI 应用项目，定位为 **AI Knowledge & Agent Platform**。

## 项目目标

项目将围绕私有知识库、检索增强生成（RAG）、Agent 与工程化能力逐步演进，最终用于简历展示、技术面试和现场演示。

## 当前阶段

当前已完成本地基础设施、Knowledge Domain/MySQL 持久化基础，以及第一版文档摄取链路：

```text
Document Upload → Local Storage → Parser → Sliding Window Chunk → MySQL
```

支持 PDF、Markdown 和 UTF-8 TXT。Embedding、Milvus Retrieval、RAG、Chat 与 Agent 仍为 **Planned**。

## 高层架构设想

- `nexusmind-server`：Java 后端服务（当前开发重点）
- `nexusmind-web`：Web 管理与交互界面（Planned）
- `deploy`：MySQL 与 Milvus 本地开发环境
- `docs`：架构、设计决策与评估文档

## 技术栈

- Java 17
- Maven
- Spring Boot 4.1.0
- Spring AI 2.0.0
- Spring MVC
- MyBatis 4.1.0 / Flyway
- MySQL 8.4.11 / Milvus 2.6.22（Milvus 业务接入 Planned）
- Apache PDFBox 3.0.8

## Roadmap

- V1 - Basic RAG（Planned）
- V2 - Hybrid Retrieval & Evaluation（Planned）
- V3 - Agent & Tool Calling（Planned）
- V4 - Production Engineering（Planned）
