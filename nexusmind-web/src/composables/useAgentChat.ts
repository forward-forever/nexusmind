import { shallowRef } from 'vue'
import { streamAgentChat, type StreamAgentChatOptions } from '@/api/agent'
import { errorMessage } from '@/api/http'
import {
  beginAgentRun,
  cancelAgentRun,
  createInitialAgentChatState,
  failAgentRun,
  reduceAgentStreamEvent,
} from './agentChatState'

export type AgentChatTransport = (options: StreamAgentChatOptions) => Promise<void>

export function useAgentChat(transport: AgentChatTransport = streamAgentChat) {
  const state = shallowRef(createInitialAgentChatState())
  let activeController: AbortController | null = null
  let generation = 0

  async function send(knowledgeBaseId: number, message: string): Promise<void> {
    const normalized = message.trim()
    if (!normalized || activeController) return
    generation += 1
    const currentGeneration = generation
    const controller = new AbortController()
    activeController = controller
    state.value = beginAgentRun(state.value, normalized)

    try {
      await transport({
        knowledgeBaseId,
        request: {
          ...(state.value.sessionId ? { sessionId: state.value.sessionId } : {}),
          message: normalized,
        },
        signal: controller.signal,
        onEvent(event) {
          if (currentGeneration === generation) {
            state.value = reduceAgentStreamEvent(state.value, event)
          }
        },
      })
      if (
        currentGeneration === generation &&
        state.value.status !== 'done' &&
        state.value.status !== 'error' &&
        state.value.status !== 'cancelled'
      ) {
        state.value = failAgentRun(state.value, 'Agent 响应流意外结束')
      }
    } catch (error) {
      if (currentGeneration !== generation) return
      state.value = isAbortError(error)
        ? cancelAgentRun(state.value)
        : failAgentRun(state.value, errorMessage(error))
    } finally {
      if (currentGeneration === generation) activeController = null
    }
  }

  function stop(): void {
    if (!activeController) return
    activeController.abort()
    activeController = null
    state.value = cancelAgentRun(state.value)
  }

  function newConversation(): void {
    generation += 1
    activeController?.abort()
    activeController = null
    state.value = createInitialAgentChatState()
  }

  return { state, send, stop, newConversation }
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
