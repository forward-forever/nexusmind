import { defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App.vue'
import type { DocumentSummary } from '@/types/document'
import type { KnowledgeBase } from '@/types/knowledge'

const listKnowledgeBases = vi.fn<() => Promise<KnowledgeBase[]>>()
const listDocuments = vi.fn<(knowledgeBaseId: number) => Promise<DocumentSummary[]>>()

vi.mock('@/api/knowledge', () => ({
  listKnowledgeBases: () => listKnowledgeBases(),
  createKnowledgeBase: vi.fn<() => void>(),
}))
vi.mock('@/api/document', () => ({
  listDocuments: (knowledgeBaseId: number) => listDocuments(knowledgeBaseId),
  uploadDocument: vi.fn<() => void>(),
  processDocument: vi.fn<() => void>(),
  indexDocument: vi.fn<() => void>(),
}))

const StatefulPanel = defineComponent({
  props: { knowledgeBaseId: Number },
  setup() {
    return { local: ref('') }
  },
  template: '<input data-testid="panel-state" v-model="local" />',
})

const knowledgeBases: KnowledgeBase[] = [
  {
    id: 33,
    name: 'KB 33',
    description: null,
    embeddingModel: 'embedding',
    embeddingDimension: 1024,
    status: 'ACTIVE',
    createdAt: '',
    updatedAt: '',
  },
  {
    id: 44,
    name: 'KB 44',
    description: null,
    embeddingModel: 'embedding',
    embeddingDimension: 1024,
    status: 'ACTIVE',
    createdAt: '',
    updatedAt: '',
  },
]

function mountWorkbench() {
  return mount(App, {
    global: {
      stubs: {
        KnowledgeBasePanel: { template: '<aside />' },
        DocumentPanel: { template: '<section />' },
        AgentChatPanel: StatefulPanel,
        RagChatPanel: StatefulPanel,
        RetrievalDebugPanel: StatefulPanel,
      },
    },
  })
}

describe('App workbench', () => {
  beforeEach(() => {
    listKnowledgeBases.mockReset().mockResolvedValue(knowledgeBases)
    listDocuments.mockReset().mockResolvedValue([])
  })

  it('preserves functional panel state while switching tabs', async () => {
    const wrapper = mountWorkbench()
    await flushPromises()

    await wrapper.get('#tab-agent').trigger('click')
    const agentInput = wrapper.get('#panel-agent [data-testid="panel-state"]')
    await agentInput.setValue('same session remains visible')
    await wrapper.get('#tab-retrieval').trigger('click')
    await wrapper.get('#tab-agent').trigger('click')

    expect((agentInput.element as HTMLInputElement).value).toBe('same session remains visible')
    expect(wrapper.get('#tab-agent').attributes('aria-selected')).toBe('true')
  })

  it('uses one global KB selection and remounts KB-bound Agent state', async () => {
    const wrapper = mountWorkbench()
    await flushPromises()
    await wrapper.get('#tab-agent').trigger('click')
    await wrapper.get('#panel-agent [data-testid="panel-state"]').setValue('KB 33 session')

    await wrapper.get('#panel-agent .workspace-context-bar select').setValue('44')
    await flushPromises()

    expect(listDocuments).toHaveBeenLastCalledWith(44)
    expect((wrapper.get('#panel-agent [data-testid="panel-state"]').element as HTMLInputElement).value)
      .toBe('')
    expect(wrapper.get('#panel-agent .workspace-context-bar select').element)
      .toHaveProperty('value', '44')
  })
})
