import { createParser, type EventSourceMessage } from 'eventsource-parser'
import { apiUrl, toApiRequestError } from './http'
import type {
  AgentChatRequest,
  AgentSource,
  AgentStreamEvent,
  AgentToolArgument,
} from '@/types/agent'

export interface StreamAgentChatOptions {
  knowledgeBaseId: number
  request: AgentChatRequest
  signal: AbortSignal
  onEvent: (event: AgentStreamEvent) => void
}

export async function streamAgentChat(options: StreamAgentChatOptions): Promise<void> {
  const response = await fetch(
    apiUrl(`/api/knowledge-bases/${options.knowledgeBaseId}/agent/chat`),
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(options.request),
      signal: options.signal,
    },
  )

  if (!response.ok) throw await toApiRequestError(response)
  if (!response.body) throw new Error('浏览器未提供可读取的 Agent 响应流')

  let terminalEventReceived = false
  const parser = createParser({
    maxBufferSize: 1024 * 1024,
    onEvent(message: EventSourceMessage) {
      const event = parseAgentStreamEvent(message.data)
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
    if (terminalEventReceived) await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}

export function parseAgentStreamEvent(data: string): AgentStreamEvent {
  const value: unknown = JSON.parse(data)
  if (!isRecord(value) || typeof value.type !== 'string') {
    throw new Error('收到无法识别的 Agent SSE 事件')
  }
  const base = parseBase(value)

  switch (value.type) {
    case 'assistant_delta':
      if (typeof value.content !== 'string') throw new Error('assistant_delta 事件格式无效')
      return { type: value.type, ...base, content: value.content }
    case 'tool_start':
      return {
        type: value.type,
        ...base,
        invocationId: requiredString(value.invocationId, 'tool_start.invocationId'),
        toolName: requiredString(value.toolName, 'tool_start.toolName'),
        arguments: parseArguments(value.arguments),
      }
    case 'tool_result':
      return {
        type: value.type,
        ...base,
        invocationId: requiredString(value.invocationId, 'tool_result.invocationId'),
        toolName: requiredString(value.toolName, 'tool_result.toolName'),
        durationMs: requiredNumber(value.durationMs, 'tool_result.durationMs'),
        resultCount: requiredNumber(value.resultCount, 'tool_result.resultCount'),
        sources: parseSources(value.sources),
      }
    case 'tool_error':
      return {
        type: value.type,
        ...base,
        invocationId: requiredString(value.invocationId, 'tool_error.invocationId'),
        toolName: requiredString(value.toolName, 'tool_error.toolName'),
        code: requiredString(value.code, 'tool_error.code'),
        message: requiredString(value.message, 'tool_error.message'),
      }
    case 'done':
      return {
        type: value.type,
        ...base,
        durationMs: requiredNumber(value.durationMs, 'done.durationMs'),
        sources: parseSources(value.sources),
        toolCallCount: requiredNumber(value.toolCallCount, 'done.toolCallCount'),
        modelTurnCount: requiredNumber(value.modelTurnCount, 'done.modelTurnCount'),
      }
    case 'error':
      return {
        type: value.type,
        ...base,
        code: requiredString(value.code, 'error.code'),
        message: requiredString(value.message, 'error.message'),
      }
    default:
      throw new Error(`不支持的 Agent SSE 事件类型: ${value.type}`)
  }
}

function parseBase(value: Record<string, unknown>): { runId: string; sessionId: string } {
  return {
    runId: requiredString(value.runId, 'runId'),
    sessionId: requiredString(value.sessionId, 'sessionId'),
  }
}

function parseArguments(value: unknown): Record<string, AgentToolArgument> {
  if (!isRecord(value)) throw new Error('tool_start.arguments 格式无效')
  const result: Record<string, AgentToolArgument> = {}
  for (const [key, item] of Object.entries(value)) {
    if (item !== null && !['string', 'number', 'boolean'].includes(typeof item)) {
      throw new Error('tool_start.arguments 包含不支持的值')
    }
    result[key] = item as AgentToolArgument
  }
  return result
}

function parseSources(value: unknown): AgentSource[] {
  if (!Array.isArray(value) || !value.every(isAgentSource)) {
    throw new Error('Agent source 格式无效')
  }
  return value
}

function isAgentSource(value: unknown): value is AgentSource {
  return (
    isRecord(value) &&
    typeof value.sourceId === 'string' &&
    typeof value.chunkId === 'number' &&
    typeof value.documentId === 'number' &&
    typeof value.fileName === 'string' &&
    (value.pageNo === null || typeof value.pageNo === 'number') &&
    (value.sectionTitle === null || typeof value.sectionTitle === 'string')
  )
}

function requiredString(value: unknown, field: string): string {
  if (typeof value !== 'string' || !value) throw new Error(`${field} 格式无效`)
  return value
}

function requiredNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`${field} 格式无效`)
  return value
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
