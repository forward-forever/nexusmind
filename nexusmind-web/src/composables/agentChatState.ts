import type {
  AgentChatState,
  AgentConversationMessage,
  AgentRunView,
  AgentStreamEvent,
} from '@/types/agent'

let messageSequence = 0

export function createInitialAgentChatState(): AgentChatState {
  return { sessionId: null, status: 'idle', messages: [], activeAssistantMessageId: null }
}

export function beginAgentRun(state: AgentChatState, message: string): AgentChatState {
  if (state.status === 'connecting' || state.status === 'streaming') return state
  const user = conversationMessage('USER', message, 'done', null)
  const assistant = conversationMessage('ASSISTANT', '', 'streaming', emptyRun())
  return {
    ...state,
    status: 'connecting',
    messages: [...state.messages, user, assistant],
    activeAssistantMessageId: assistant.id,
  }
}

export function reduceAgentStreamEvent(
  state: AgentChatState,
  event: AgentStreamEvent,
): AgentChatState {
  const activeId = state.activeAssistantMessageId
  if (!activeId) return state
  const messages = state.messages.map((message) => {
    if (message.id !== activeId || message.role !== 'ASSISTANT' || !message.run) return message
    const run = withIdentity(message.run, event)
    switch (event.type) {
      case 'assistant_delta':
        return {
          ...message,
          content: message.content + event.content,
          run: { ...run, status: 'streaming' as const },
        }
      case 'tool_start':
        return {
          ...message,
          run: {
            ...run,
            status: 'streaming' as const,
            tools: [
              ...run.tools,
              {
                invocationId: event.invocationId,
                toolName: event.toolName,
                arguments: event.arguments,
                status: 'running' as const,
                durationMs: null,
                resultCount: null,
                sources: [],
                code: null,
                message: null,
              },
            ],
          },
        }
      case 'tool_result':
        return {
          ...message,
          run: {
            ...run,
            tools: run.tools.map((tool) =>
              tool.invocationId === event.invocationId
                ? {
                    ...tool,
                    status: 'success' as const,
                    durationMs: event.durationMs,
                    resultCount: event.resultCount,
                    sources: event.sources,
                  }
                : tool,
            ),
          },
        }
      case 'tool_error':
        return {
          ...message,
          run: {
            ...run,
            tools: run.tools.map((tool) =>
              tool.invocationId === event.invocationId
                ? { ...tool, status: 'error' as const, code: event.code, message: event.message }
                : tool,
            ),
          },
        }
      case 'done':
        return {
          ...message,
          status: 'done' as const,
          run: {
            ...run,
            status: 'done' as const,
            sources: event.sources,
            toolCallCount: event.toolCallCount,
            modelTurnCount: event.modelTurnCount,
            durationMs: event.durationMs,
          },
        }
      case 'error':
        return {
          ...message,
          status: 'error' as const,
          run: { ...run, status: 'error' as const, error: `${event.code}: ${event.message}` },
        }
    }
  })

  const terminal = event.type === 'done' || event.type === 'error'
  return {
    ...state,
    sessionId: event.sessionId,
    status: event.type === 'done' ? 'done' : event.type === 'error' ? 'error' : 'streaming',
    messages,
    activeAssistantMessageId: terminal ? null : activeId,
  }
}

export function cancelAgentRun(state: AgentChatState): AgentChatState {
  if (state.status !== 'connecting' && state.status !== 'streaming') return state
  return {
    ...state,
    status: 'cancelled',
    activeAssistantMessageId: null,
    messages: state.messages.map((message) =>
      message.id === state.activeAssistantMessageId && message.run
        ? {
            ...message,
            status: 'cancelled' as const,
            run: { ...message.run, status: 'cancelled' as const },
          }
        : message,
    ),
  }
}

export function failAgentRun(state: AgentChatState, error: string): AgentChatState {
  if (!state.activeAssistantMessageId) return state
  return {
    ...state,
    status: 'error',
    activeAssistantMessageId: null,
    messages: state.messages.map((message) =>
      message.id === state.activeAssistantMessageId && message.run
        ? {
            ...message,
            status: 'error' as const,
            run: { ...message.run, status: 'error' as const, error },
          }
        : message,
    ),
  }
}

function conversationMessage(
  role: AgentConversationMessage['role'],
  content: string,
  status: AgentConversationMessage['status'],
  run: AgentRunView | null,
): AgentConversationMessage {
  messageSequence += 1
  return { id: `agent-message-${messageSequence}`, role, content, status, run }
}

function emptyRun(): AgentRunView {
  return {
    runId: null,
    sessionId: null,
    status: 'connecting',
    tools: [],
    sources: [],
    toolCallCount: null,
    modelTurnCount: null,
    durationMs: null,
    error: null,
  }
}

function withIdentity(run: AgentRunView, event: AgentStreamEvent): AgentRunView {
  return { ...run, runId: event.runId, sessionId: event.sessionId }
}
