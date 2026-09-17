import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import DocumentPanel from '@/components/DocumentPanel.vue'
import type { DocumentSummary, DocumentTask } from '@/types/document'

const document: DocumentSummary = {
  id: 7,
  originalFileName: 'mvcc.txt',
  contentType: 'text/plain',
  fileSize: 100,
  status: 'UPLOADED',
  chunkCount: 0,
  indexStatus: 'NOT_INDEXED',
  indexedAt: null,
  errorMessage: null,
  indexErrorMessage: null,
  createdAt: '',
}

function task(status: DocumentTask['status']): DocumentTask {
  return {
    taskId: 11,
    documentId: 7,
    taskType: 'PROCESS',
    status,
    attemptCount: status === 'PENDING' ? 0 : 1,
    enqueuedAt: '',
    startedAt: status === 'PENDING' ? null : '',
    finishedAt: status === 'FAILED' ? '' : null,
    lastError: status === 'FAILED' ? 'PROCESS failed' : null,
  }
}

function mountPanel(tasks: DocumentTask[], current = document) {
  return mount(DocumentPanel, {
    props: { documents: [current], loading: false, uploadBusy: false, tasks },
  })
}

describe('DocumentPanel durable task states', () => {
  it('renders queued then running state and disables duplicate process', async () => {
    const wrapper = mountPanel([task('PENDING')])
    expect(wrapper.text()).toContain('Queued')
    const processButton = wrapper.get('button.secondary')
    expect(processButton.element).toBeInstanceOf(HTMLButtonElement)
    expect(processButton.attributes('disabled')).toBeDefined()

    await wrapper.setProps({ tasks: [task('RUNNING')] })
    expect(wrapper.text()).toContain('Processing…')
    expect(wrapper.text()).toContain('Attempt 1')
  })

  it('offers retry for failed process and emits the original document', async () => {
    const failedDocument = { ...document, status: 'FAILED' as const, errorMessage: 'parse failed' }
    const wrapper = mountPanel([task('FAILED')], failedDocument)

    expect(wrapper.text()).toContain('Failed')
    const retryButton = wrapper.get('button.secondary')
    expect(retryButton.element).toBeInstanceOf(HTMLButtonElement)
    await retryButton.trigger('click')

    expect(wrapper.emitted('process')?.[0]).toEqual([failedDocument])
    expect(wrapper.text()).toContain('Retry Process')
  })

  it('renders retry index as a live browser button', () => {
    const failedIndexDocument = {
      ...document,
      status: 'READY' as const,
      indexStatus: 'FAILED' as const,
      indexErrorMessage: 'embedding failed',
    }
    const wrapper = mountPanel([], failedIndexDocument)

    const retryButton = wrapper.get('button.secondary')
    expect(retryButton.element).toBeInstanceOf(HTMLButtonElement)
    expect(retryButton.text()).toBe('Retry Index')
  })
})
