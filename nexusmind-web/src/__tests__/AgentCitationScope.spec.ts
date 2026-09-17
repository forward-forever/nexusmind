import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

vi.mock('@/composables/useAgentChat', async () => {
  const { shallowRef } = await import('vue')
  const source = (runId: string, chunkId: number) => ({
    sourceId: 'S1',
    chunkId,
    documentId: chunkId,
    fileName: `${runId}.pdf`,
    pageNo: 1,
    sectionTitle: null,
  })
  const run = (runId: string, chunkId: number) => ({
    runId,
    sessionId: 'session-1',
    status: 'done' as const,
    tools: [],
    sources: [source(runId, chunkId)],
    toolCallCount: 1,
    modelTurnCount: 2,
    durationMs: 100,
    error: null,
  })
  const state = shallowRef({
    sessionId: 'session-1',
    status: 'done' as const,
    activeAssistantMessageId: null,
    messages: [
      { id: 'a1', role: 'ASSISTANT' as const, content: 'Run one [S1]', status: 'done' as const, run: run('R1', 100) },
      { id: 'a2', role: 'ASSISTANT' as const, content: 'Run two [S1]', status: 'done' as const, run: run('R2', 900) },
    ],
  })
  return {
    useAgentChat: () => ({
      state,
      send: vi.fn<() => Promise<void>>(async () => undefined),
      stop: vi.fn<() => void>(),
      newConversation: vi.fn<() => void>(),
    }),
  }
})

import AgentChatPanel from '@/components/AgentChatPanel.vue'

describe('Agent run-scoped citations', () => {
  it('uses unique DOM identities and highlights only the clicked run source', async () => {
    const wrapper = mount(AgentChatPanel, {
      props: { knowledgeBaseId: 1, knowledgeBaseName: 'KB', ready: true },
    })

    const cards = wrapper.findAll('.agent-source-card')
    expect(cards.map((card) => card.attributes('id'))).toEqual([
      'agent-source-R1-S1',
      'agent-source-R2-S1',
    ])
    expect(new Set(cards.map((card) => card.attributes('id'))).size).toBe(2)

    const citations = wrapper.findAll('button.citation-link')
    await citations[1]?.trigger('click')
    expect(wrapper.get('#agent-source-R2-S1').classes()).toContain('highlighted')
    expect(wrapper.get('#agent-source-R1-S1').classes()).not.toContain('highlighted')

    await citations[0]?.trigger('click')
    expect(wrapper.get('#agent-source-R1-S1').classes()).toContain('highlighted')
    expect(wrapper.get('#agent-source-R2-S1').classes()).not.toContain('highlighted')
  })
})
