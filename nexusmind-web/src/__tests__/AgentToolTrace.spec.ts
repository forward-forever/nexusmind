import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AgentSourceCard from '@/components/AgentSourceCard.vue'
import AgentToolTrace from '@/components/AgentToolTrace.vue'
import type { AgentRunView, AgentSource } from '@/types/agent'

const source: AgentSource = {
  sourceId: 'S1',
  chunkId: 101,
  documentId: 10,
  fileName: 'mysql.pdf',
  pageNo: 17,
  sectionTitle: 'Read View',
}

describe('Agent tool and source presentation', () => {
  it('renders safe arguments, result metadata, and run metrics', () => {
    const run: AgentRunView = {
      runId: 'd26d73f7-0000-0000-0000-000000000000',
      sessionId: '6ce0f79d-0000-0000-0000-000000000000',
      status: 'done',
      tools: [
        {
          invocationId: 'call-1',
          toolName: 'search_knowledge_base',
          arguments: { query: 'MVCC Read View' },
          status: 'success',
          durationMs: 492,
          resultCount: 1,
          sources: [source],
          code: null,
          message: null,
        },
      ],
      sources: [source],
      toolCallCount: 1,
      modelTurnCount: 2,
      durationMs: 1300,
      error: null,
    }

    const wrapper = mount(AgentToolTrace, { props: { run } })
    expect(wrapper.text()).toContain('search_knowledge_base')
    expect(wrapper.text()).toContain('MVCC Read View')
    expect(wrapper.text()).toContain('1 results · 492 ms')
    expect(wrapper.text()).toContain('S1 · Page 17')
    expect(wrapper.text()).toContain('1 tools · 2 model turns · 1300 ms')
    expect(wrapper.text()).not.toContain('knowledgeBaseId')
  })

  it('renders final source metadata as text', () => {
    const wrapper = mount(AgentSourceCard, { props: { source, highlighted: false } })
    expect(wrapper.text()).toContain('S1')
    expect(wrapper.text()).toContain('mysql.pdf')
    expect(wrapper.text()).toContain('Page 17')
    expect(wrapper.text()).toContain('Read View')
    expect(wrapper.text()).toContain('Chunk #101')
    expect(wrapper.find('[v-html]').exists()).toBe(false)
  })
})
