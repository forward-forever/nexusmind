import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App.vue'
import type { DocumentSummary, DocumentTask } from '@/types/document'
import type { KnowledgeBase } from '@/types/knowledge'

const listDocuments = vi.fn<(knowledgeBaseId: number) => Promise<DocumentSummary[]>>()
const listActiveDocumentTasks = vi.fn<(knowledgeBaseId: number) => Promise<DocumentTask[]>>()
const getDocumentTask = vi.fn<(taskId: number) => Promise<DocumentTask>>()

vi.mock('@/api/knowledge', () => ({
  listKnowledgeBases: vi.fn<() => Promise<KnowledgeBase[]>>().mockResolvedValue([{
    id: 3, name: 'Task KB', description: null, embeddingModel: 'embedding',
    embeddingDimension: 1024, status: 'ACTIVE', createdAt: '', updatedAt: '',
  }]),
  createKnowledgeBase: vi.fn<() => Promise<KnowledgeBase>>(),
}))

vi.mock('@/api/document', () => ({
  listDocuments: (knowledgeBaseId: number) => listDocuments(knowledgeBaseId),
  listActiveDocumentTasks: (knowledgeBaseId: number) => listActiveDocumentTasks(knowledgeBaseId),
  getDocumentTask: (taskId: number) => getDocumentTask(taskId),
  uploadDocument: vi.fn<() => Promise<void>>(),
  processDocument: vi.fn<() => Promise<void>>(),
  indexDocument: vi.fn<() => Promise<void>>(),
}))

const TaskProbe = defineComponent({
  props: { tasks: { type: Array, default: () => [] } },
  template: '<div data-testid="tasks">{{ JSON.stringify(tasks) }}</div>',
})

const baseTask: DocumentTask = {
  taskId: 11, documentId: 7, taskType: 'PROCESS', status: 'PENDING', attemptCount: 0,
  enqueuedAt: '', startedAt: null, finishedAt: null, lastError: null,
}

describe('document task polling', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    listDocuments.mockReset().mockResolvedValue([])
    listActiveDocumentTasks.mockReset().mockResolvedValue([baseTask])
    getDocumentTask.mockReset()
  })

  afterEach(() => vi.useRealTimers())

  it('recovers active tasks in one KB request and stops polling after success', async () => {
    getDocumentTask
      .mockResolvedValueOnce({ ...baseTask, status: 'RUNNING', attemptCount: 1 })
      .mockResolvedValueOnce({ ...baseTask, status: 'SUCCEEDED', attemptCount: 1, finishedAt: '' })
    const wrapper = mount(App, { global: { stubs: {
      KnowledgeBasePanel: { template: '<aside />' }, DocumentPanel: TaskProbe,
      AgentChatPanel: { template: '<section />' }, RagChatPanel: { template: '<section />' },
      RetrievalDebugPanel: { template: '<section />' },
    } } })
    await flushPromises()

    expect(listActiveDocumentTasks).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="tasks"]').text()).toContain('PENDING')

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.get('[data-testid="tasks"]').text()).toContain('RUNNING')

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.get('[data-testid="tasks"]').text()).toContain('SUCCEEDED')
    expect(getDocumentTask).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(5000)
    expect(getDocumentTask).toHaveBeenCalledTimes(2)
  })
})
