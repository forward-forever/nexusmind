# NexusMind V1 Demo

NexusMind V1 提供一条可从浏览器完成的 Basic RAG 链路：

```text
Knowledge Base
  → Upload PDF / Markdown / TXT
  → Process (Parser + char-based Chunking + MySQL)
  → Index (Embedding + Milvus HNSW)
  → Dense Retrieval
  → Context + Citation Mapping
  → qwen3.5-flash
  → POST SSE Streaming Answer
```

## 1. 启动本地基础设施

首次运行时，在 `deploy` 目录创建仅供本地使用的 `.env`：

```bash
cd deploy
cp .env.example .env
docker compose up -d
docker compose ps
```

MySQL 和 Milvus 应显示为 healthy。`docker compose down` 会保留数据卷；`docker compose down -v` 会删除本地 MySQL 和全部 Milvus 数据。

## 2. 启动 Backend

为 local profile 提供以下环境变量，值均只保存在本地环境中：

```bash
export DB_PASSWORD='<local mysql password>'
export DASHSCOPE_API_KEY='<development key>'
export EMBEDDING_BASE_URL='<OpenAI-compatible base URL>'
export CHAT_BASE_URL='<OpenAI-compatible base URL>'
```

数据库其他配置可按需使用 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME` 覆盖。然后启动：

```bash
cd nexusmind-server
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

API 默认监听 `http://localhost:8080`。

## 3. 启动 Web

```bash
cd nexusmind-web
npm install
npm run dev
```

打开 Vite 输出的本地 URL。开发服务器默认把 `/api` 代理到 `http://localhost:8080`，无需额外修改 Spring CORS。

## 4. V1 Demo Flow

1. 点击左侧 `+`，输入 name 和 description，创建 Knowledge Base。
2. 点击 `Upload document`，选择小于 20MB 的 PDF、Markdown 或 UTF-8 TXT。
3. 对 `UPLOADED` Document 点击 `Process`，确认状态变为 `READY` 且 chunk count 大于 0。
4. 点击 `Index`，等待真实 Embedding 和 Milvus 写入完成；确认 index status 为 `INDEXED`，并显示 `Ready for RAG`。
5. 也可使用 `Prepare for RAG` 由前端依次调用现有 Process、Index 两个 API；任一步失败都会停止。
6. 在 `Ask Knowledge Base` 中输入文档明确覆盖的问题，点击 `Send`。
7. 确认 Sources 先到达，Answer 随模型增量逐步显示，最终出现 `[S1]` 等引用。
8. 点击回答中的 `[S1]`，确认对应 Source card 被定位并高亮，且 file name、page/section、similarity 和 chunk ID 正确。
9. 用同义改写再次提问，观察 Dense Retrieval 是否仍能找到语义相关内容。
10. 提问文档完全未涉及的内容，预期模型明确说明当前知识库资料不足，而不是凭常识编造。

生成过程中可点击 `Stop`。Web 会调用 `AbortController.abort()` 取消 fetch response stream，而不是仅停止 UI 更新。

## 5. Demo Data

建议使用 Java/MySQL 官方公开技术资料、自编公开 Markdown，或仓库内非敏感文档。不要提交公司内部文件、私人资料、公司源码或 API Key。

## V1 当前限制

- Dense-only Retrieval；没有 BM25、Hybrid、RRF 或 Rerank。
- Stateless Chat；Backend 不保存会话，也没有多轮 Memory。
- PDF 不支持 OCR；扫描 PDF 无可提取文本时会失败。
- 没有 Authentication、Permission 或用户系统。
- 原始文件使用 Local File Storage。
- Process 与 Index 都是同步请求。
- Chunking 使用 char-based window，不是 token-aware chunking。
- Context guard 使用 character count，不是 token budget。
- Prompt 限制引用 Source ID，但没有 Citation Validation/Repair。
- V1 Web 不提供 Retrieval Debug Panel，也不渲染 Markdown。
