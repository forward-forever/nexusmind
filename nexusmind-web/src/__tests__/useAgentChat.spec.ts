import { describe, expect, it, vi } from 'vitest'
import { useAgentChat, type AgentChatTransport } from '@/composables/useAgentChat'
import type { StreamAgentChatOptions } from '@/api/agent'

describe('useAgentChat', () => {
  it('persists the server sessionId between sends', async () => {
    const requests: StreamAgentChatOptions['request'][] = []
    const transport: AgentChatTransport = async (options) => {
      requests.push(options.request)
      options.onEvent({
        type: 'assistant_delta',
        runId: `run-${requests.length}`,
        sessionId: 'session-1',
        content: 'answer',
      })
      options.onEvent({
        type: 'done',
        runId: `run-${requests.length}`,
        sessionId: 'session-1',
        durationMs: 10,
        sources: [],
        toolCallCount: 0,
        modelTurnCount: 1,
      })
    }
    const chat = useAgentChat(transport)

    await chat.send(7, 'first')
    await chat.send(7, 'second')

    expect(requests).toEqual([
      { message: 'first' },
      { sessionId: 'session-1', message: 'second' },
    ])
    expect(chat.state.value.messages).toHaveLength(4)
  })

  it('new conversation clears visible messages and the session', async () => {
    const chat = useAgentChat(async (options) => {
      options.onEvent({
        type: 'done',
        runId: 'run-1',
        sessionId: 'session-1',
        durationMs: 1,
        sources: [],
        toolCallCount: 0,
        modelTurnCount: 1,
      })
    })
    await chat.send(7, 'hello')
    chat.newConversation()
    expect(chat.state.value).toMatchObject({ sessionId: null, status: 'idle', messages: [] })
  })

  it('prevents concurrent sends and aborts the active request on stop', async () => {
    let release: (() => void) | undefined
    const transport = vi.fn<AgentChatTransport>(
      (options) =>
        new Promise<void>((resolve, reject) => {
          release = resolve
          options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
        }),
    )
    const chat = useAgentChat(transport)
    const first = chat.send(7, 'first')
    await chat.send(7, 'second')
    expect(transport).toHaveBeenCalledTimes(1)
    chat.stop()
    await first
    release?.()
    expect(chat.state.value.status).toBe('cancelled')
  })
})
