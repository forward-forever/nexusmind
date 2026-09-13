# Retrieval Evaluation Datasets

Golden Dataset 使用 UTF-8 JSONL；每行是一条由人工确认的 Retrieval Query 与 relevant Chunk 标注。

- `*.example.jsonl`：可提交的虚构格式示例，不代表真实评测结果。
- `local-*.jsonl`：本地真实 Golden Dataset，默认被 Git 忽略。

不要提交私人文档内容、公司资料、API Key 或根据当前 Dense TopK 自动反推出来的标签。详细标注与执行流程见 [`docs/retrieval-evaluation.md`](../../docs/retrieval-evaluation.md)。
