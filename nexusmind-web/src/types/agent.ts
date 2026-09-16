export type AgentMessageRole = 'USER' | 'ASSISTANT'
export type AgentMessageStatus = 'streaming' | 'done' | 'error' | 'cancelled'
export type AgentStreamStatus = 'idle' | 'connecting' | 'streaming' | 'done' | 'error' | 'cancelled'
export type AgentToolStatus = 'running' | 'success' | 'error'

export interface AgentSource {
  sourceId: string
  chunkId: number
  documentId: number
  fileName: string
  pageNo: number | null
  sectionTitle: string | null
}

export type AgentToolArgument = string | number | boolean | null

interface AgentEventBase {
  runId: string
  sessionId: string
}

export interface AssistantDeltaEvent extends AgentEventBase {
  type: 'assistant_delta'
  content: string
}

export interface ToolStartEvent extends AgentEventBase {
  type: 'tool_start'
  invocationId: string
  toolName: string
  arguments: Record<string, AgentToolArgument>
}

export interface ToolResultEvent extends AgentEventBase {
  type: 'tool_result'
  invocationId: string
  toolName: string
  durationMs: number
  resultCount: number
  sources: AgentSource[]
}

export interface ToolErrorEvent extends AgentEventBase {
  type: 'tool_error'
  invocationId: string
  toolName: string
  code: string
  message: string
}

export interface AgentDoneEvent extends AgentEventBase {
  type: 'done'
  durationMs: number
  sources: AgentSource[]
  toolCallCount: number
  modelTurnCount: number
}

export interface AgentErrorEvent extends AgentEventBase {
  type: 'error'
  code: string
  message: string
}

export type AgentStreamEvent =
  | AssistantDeltaEvent
  | ToolStartEvent
  | ToolResultEvent
  | ToolErrorEvent
  | AgentDoneEvent
  | AgentErrorEvent

export interface AgentChatRequest {
  sessionId?: string
  message: string
}

export interface AgentToolTrace {
  invocationId: string
  toolName: string
  arguments: Record<string, AgentToolArgument>
  status: AgentToolStatus
  durationMs: number | null
  resultCount: number | null
  sources: AgentSource[]
  code: string | null
  message: string | null
}

export interface AgentRunView {
  runId: string | null
  sessionId: string | null
  status: AgentStreamStatus
  tools: AgentToolTrace[]
  sources: AgentSource[]
  toolCallCount: number | null
  modelTurnCount: number | null
  durationMs: number | null
  error: string | null
}

export interface AgentConversationMessage {
  id: string
  role: AgentMessageRole
  content: string
  status: AgentMessageStatus
  run: AgentRunView | null
}

export interface AgentChatState {
  sessionId: string | null
  status: AgentStreamStatus
  messages: AgentConversationMessage[]
  activeAssistantMessageId: string | null
}
