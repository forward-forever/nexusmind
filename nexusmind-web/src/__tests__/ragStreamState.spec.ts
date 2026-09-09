import { describe, expect, it } from 'vitest'
import {
  cancelRagStream,
  createInitialRagStreamState,
  reduceRagStreamEvent,
} from '@/composables/ragStreamState'
import type { RagSource, RagStreamState } from '@/types/rag'

const source: RagSource = {
  id: 'S1',
  chunkId: 101,
  documentId: 10,
  fileName: 'mysql.pdf',
  pageNo: 17,
  sectionTitle: 'Deadlocks',
  score: 0.8231,
}

describe('RAG stream reducer', () => {
  it('reduces sources, deltas, and done in protocol order', () => {
    let state: RagStreamState = { ...createInitialRagStreamState(), status: 'connecting' }
    state = reduceRagStreamEvent(state, { type: 'sources', sources: [source] })
    state = reduceRagStreamEvent(state, { type: 'delta', content: 'MV' })
    state = reduceRagStreamEvent(state, { type: 'delta', content: 'CC' })
    state = reduceRagStreamEvent(state, {
      type: 'done',
      model: 'qwen3.5-flash',
      elapsedMs: 1320,
    })

    expect(state.sources).toEqual([source])
    expect(state.answer).toBe('MVCC')
    expect(state.status).toBe('done')
    expect(state.model).toBe('qwen3.5-flash')
    expect(state.elapsedMs).toBe(1320)
  })

  it('keeps error terminal and never transitions to done', () => {
    let state: RagStreamState = { ...createInitialRagStreamState(), status: 'connecting' }
    state = reduceRagStreamEvent(state, { type: 'sources', sources: [source] })
    state = reduceRagStreamEvent(state, { type: 'delta', content: 'partial' })
    state = reduceRagStreamEvent(state, {
      type: 'error',
      code: 'CHAT_MODEL_ERROR',
      message: '模型生成失败，请稍后重试',
    })
    state = reduceRagStreamEvent(state, {
      type: 'done',
      model: 'qwen3.5-flash',
      elapsedMs: 999,
    })

    expect(state.status).toBe('error')
    expect(state.error).toBe('模型生成失败，请稍后重试')
    expect(state.model).toBeNull()
  })

  it('records cancellation only for an active stream', () => {
    const active = { ...createInitialRagStreamState(), status: 'streaming' as const }
    expect(cancelRagStream(active).status).toBe('cancelled')
    expect(cancelRagStream(createInitialRagStreamState()).status).toBe('idle')
  })
})
