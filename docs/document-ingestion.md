# Document Ingestion

Checkpoint 3 实现同步文档摄取链路：

```text
Upload → Temporary File + SHA-256 → Local Storage → MySQL Document
       → Parser → ParsedDocument → SlidingWindowChunker → MySQL Chunks
```

当前支持 PDF、Markdown（`.md` / `.markdown`）和 UTF-8 TXT。Embedding、Milvus Retrieval 与 RAG 仍为 **Planned**。

## 配置

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| `nexusmind.storage.root` | `NEXUSMIND_STORAGE_ROOT` | `${user.home}/.nexusmind/storage` |
| `nexusmind.storage.max-file-size` | `NEXUSMIND_MAX_FILE_SIZE` | `20MB` |
| `nexusmind.chunking.chunk-size-chars` | `NEXUSMIND_CHUNK_SIZE_CHARS` | `1000` |
| `nexusmind.chunking.chunk-overlap-chars` | `NEXUSMIND_CHUNK_OVERLAP_CHARS` | `150` |

Spring Multipart 的单文件和单请求限制也使用 `NEXUSMIND_MAX_FILE_SIZE`。`chunk-overlap-chars` 必须大于等于 0 且小于 `chunk-size-chars`。

数据库只保存相对于 storage root 的路径，例如：

```text
knowledge/42/4e8...c1a/notes.txt
```

## 本地运行

先启动 `deploy` 中的 MySQL，再从 `nexusmind-server` 启动 local profile：

```bash
cd /Users/wude/IdeaProjects/nexusmind/deploy
docker compose up -d mysql

cd /Users/wude/IdeaProjects/nexusmind/nexusmind-server
set -a
source ../deploy/.env
set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

## API 示例

创建 KnowledgeBase：

```bash
curl -sS -X POST http://localhost:8080/api/knowledge-bases \
  -H 'Content-Type: application/json' \
  -d '{"name":"Local Notes","description":"Checkpoint 3 smoke test","embeddingModel":"text-embedding-3-small","embeddingDimension":1536}'
```

上传文件（将 `1` 替换为 KnowledgeBase ID）：

```bash
curl -sS -X POST http://localhost:8080/api/knowledge-bases/1/documents \
  -F 'file=@/absolute/path/to/notes.txt'
```

处理、查询 Document 与查看 Chunks（将 `1` 替换为 Document ID）：

```bash
curl -sS -X POST http://localhost:8080/api/documents/1/process
curl -sS http://localhost:8080/api/documents/1
curl -sS http://localhost:8080/api/documents/1/chunks
```

重复上传相同内容时返回已有 Document，并将 `duplicate` 标记为 `true`。

## 设计边界

- 上传内容通过流写入临时文件，并在同一遍读取中计算 SHA-256，不调用 `MultipartFile#getBytes()`。
- 原始文件由 `DocumentStorage` 管理；本地实现会清理文件名并校验路径始终位于 storage root 下。
- Parser 输出带页码或章节标题的 `ParsedDocument`，Chunker 不跨 PDF 页或 Markdown Section 合并内容。
- PDF 未提取到有效文本时明确失败；V1 不提供 OCR。
- Parser 与 Chunk 计算不占用数据库事务；只有状态变化及 Chunk 替换使用短事务。
- MySQL 是业务数据 Source of Truth。Milvus 后续仅作为可重建的 Retrieval Index。
