import { createParser, type EventSourceMessage } from 'eventsource-parser'
import { apiUrl, toApiRequestError } from './http'
import type { RagRequest, RagSource, RagStreamEvent } from '@/types/rag'

interface StreamRagOptions {
  knowledgeBaseId: number
  request: RagRequest
  signal: AbortSignal
  onEvent: (event: RagStreamEvent) => void
}

export async function streamRagAnswer(options: StreamRagOptions): Promise<void> {
  const response = await fetch(
    apiUrl(`/api/knowledge-bases/${options.knowledgeBaseId}/rag/stream`),
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(options.request),
      signal: options.signal,
    },
  )

  if (!response.ok) {
    throw await toApiRequestError(response)
  }
  if (!response.body) {
    throw new Error('浏览器未提供可读取的响应流')
  }

  let terminalEventReceived = false
  const parser = createParser({
    maxBufferSize: 1024 * 1024,
    onEvent(message: EventSourceMessage) {
      const event = parseRagStreamEvent(message.data)
      options.onEvent(event)
      terminalEventReceived = event.type === 'done' || event.type === 'error'
    },
    onError(error) {
      throw error
    },
  })
  const reader = response.body.getReader()
  const decoder = new TextDecoder()

  try {
    while (!terminalEventReceived) {
      const { value, done } = await reader.read()
      if (done) break
      parser.feed(decoder.decode(value, { stream: true }))
    }
    parser.feed(decoder.decode())
    parser.reset({ consume: true })
  } finally {
    if (terminalEventReceived) {
      await reader.cancel().catch(() => undefined)
    }
    reader.releaseLock()
  }
}

export function parseRagStreamEvent(data: string): RagStreamEvent {
  const value: unknown = JSON.parse(data)
  if (!isRecord(value) || typeof value.type !== 'string') {
    throw new Error('收到无法识别的 SSE 事件')
  }

  switch (value.type) {
    case 'sources':
      if (!Array.isArray(value.sources) || !value.sources.every(isRagSource)) {
        throw new Error('sources 事件格式无效')
      }
      return { type: 'sources', sources: value.sources }
    case 'delta':
      if (typeof value.content !== 'string') throw new Error('delta 事件格式无效')
      return { type: 'delta', content: value.content }
    case 'done':
      if (typeof value.model !== 'string' || typeof value.elapsedMs !== 'number') {
        throw new Error('done 事件格式无效')
      }
      return { type: 'done', model: value.model, elapsedMs: value.elapsedMs }
    case 'error':
      if (typeof value.code !== 'string' || typeof value.message !== 'string') {
        throw new Error('error 事件格式无效')
      }
      return { type: 'error', code: value.code, message: value.message }
    default:
      throw new Error(`不支持的 SSE 事件类型: ${value.type}`)
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isRagSource(value: unknown): value is RagSource {
  if (!isRecord(value)) return false
  return (
    typeof value.id === 'string' &&
    typeof value.chunkId === 'number' &&
    typeof value.documentId === 'number' &&
    typeof value.fileName === 'string' &&
    (value.pageNo === null || typeof value.pageNo === 'number') &&
    (value.sectionTitle === null || typeof value.sectionTitle === 'string') &&
    typeof value.score === 'number'
  )
}
