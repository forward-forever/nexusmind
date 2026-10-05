# NexusMind Documentation

## Start Here

- [Project Overview](project-overview.md)：10 分钟了解项目目标、V1→V4 演进、Evaluation 与关键决策
- [Architecture](architecture.md)：系统 Pipeline、数据所有权、故障模型与详细 Mermaid 图
- [Demo Guide](demo-guide.md)：5–10 分钟现场演示顺序及每一步要证明的能力
- [Observability](observability.md)：Actuator、Micrometer、Prometheus、Tag 与日志策略

## Knowledge and RAG

- [Document Ingestion](document-ingestion.md)：上传、存储、解析与分块
- [Dense Retrieval](dense-retrieval.md)：Embedding、Milvus HNSW 与 COSINE TopK
- [RAG Chat](rag-chat.md)：Context、Citation Mapping 与 SSE Streaming
- [V1 Demo](v1-demo.md)：V1 全链路启动和演示记录

## Retrieval Quality

- [Retrieval Evaluation](retrieval-evaluation.md)：Golden Dataset、Page Resolver、指标与 CLI
- [BM25 Retrieval](bm25-retrieval.md)：Sparse Retrieval 设计
- [Hybrid Retrieval](hybrid-retrieval.md)：Application RRF 与 provenance
- [Rerank](rerank.md)：Cross-Encoder Rerank Provider 与 Pipeline
- [V2 Retrieval Quality](v2-retrieval-quality.md)：四种 Retriever 的最终真实对比与 ceiling effect

## Agent

- [Agent Foundation](agent-foundation.md)：Function Calling、Tool Loop、SSE 与 Guardrails
- [Agent Memory](agent-memory.md)：Session、跨请求 Memory 与 Source scope
- [V3 Agent](v3-agent.md)：Multi-Tool、UI、Behavior Evaluation 与 V3 总结
- [Controlled MCP Client](mcp-client-integration.md)：Streamable HTTP、startup discovery 与 allowlist

## Production Engineering

- [Async Document Tasks](async-document-tasks.md)：Durable Task、claim、heartbeat 与 recovery
- [Resilience and Concurrency](resilience-concurrency.md)：Provider Retry、Parallel Hybrid 与 Session Lease
- [Token and Context Management](token-context-management.md)：RAG、Memory 与 Tool Result Budget

历史 Feature 文档保留设计演进和实现细节；项目功能在 V4 冻结，不继续规划新的功能版本。
