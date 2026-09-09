# NexusMind Web

NexusMind V1 的最小单页 Web UI。它通过 NexusMind Backend API 完成 Knowledge Base 创建、Document 上传/处理/索引，以及带 Citation 的流式 RAG 问答。

## 技术栈

- Vue 3
- TypeScript
- Vite
- Vitest
- ESLint / Prettier
- `eventsource-parser` 4.1.0

## 本地开发

```bash
npm install
npm run dev
```

Vite 默认把 `/api` 代理到 `http://localhost:8080`。如需使用其他 Backend origin，可复制 `.env.example` 并设置 `VITE_API_BASE_URL`。

前端不需要、也不应持有 DashScope API Key。所有 Embedding 和 Chat 请求都由 Backend 发起。

## 验证

```bash
npm run lint
npm run test
npm run build
```

完整 V1 演示步骤见 [`../docs/v1-demo.md`](../docs/v1-demo.md)。
