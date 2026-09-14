import { describe, expect, it, vi } from 'vitest'
import {
  runRetrievalComparison,
  type RetrievalSearch,
} from '@/composables/retrievalComparison'
import type {
  RetrievalSearchRequest,
  RetrievalSearchResponse,
  RetrievalStrategyRun,
  RetrieverType,
} from '@/types/retrieval'

describe('runRetrievalComparison', () => {
  it('uses the same knowledge base, query, and topK for all four retrievers', async () => {
    const requests: RetrievalSearchRequest[] = []
    const search = vi.fn<RetrievalSearch>(
      async (_knowledgeBaseId: number, request: RetrievalSearchRequest) => {
        requests.push(request)
        return response(request.retrieverType)
      },
    )
    const settled: RetrievalStrategyRun[] = []

    await runRetrievalComparison({
      knowledgeBaseId: 12,
      query: '为什么会发生死锁？',
      topK: 5,
      signal: new AbortController().signal,
      search,
      now: () => 10,
      onSettled: (run) => settled.push(run),
    })

    expect(search).toHaveBeenCalledTimes(4)
    expect(requests.map((request) => request.retrieverType)).toEqual([
      'DENSE',
      'BM25',
      'HYBRID_RRF',
      'HYBRID_RERANK',
    ])
    expect(requests.every((request) => request.query === '为什么会发生死锁？')).toBe(true)
    expect(requests.every((request) => request.topK === 5)).toBe(true)
    expect(settled.every((run) => run.status === 'success')).toBe(true)
  })

  it('preserves successful routes when rerank fails', async () => {
    const settled: RetrievalStrategyRun[] = []

    await runRetrievalComparison({
      knowledgeBaseId: 12,
      query: 'MVCC',
      topK: 3,
      signal: new AbortController().signal,
      search: async (_knowledgeBaseId, request) => {
        if (request.retrieverType === 'HYBRID_RERANK') {
          throw new Error('Rerank provider timeout')
        }
        return response(request.retrieverType)
      },
      now: () => 10,
      onSettled: (run) => settled.push(run),
    })

    const byType = Object.fromEntries(settled.map((run) => [run.retrieverType, run]))
    expect(byType.DENSE?.status).toBe('success')
    expect(byType.BM25?.status).toBe('success')
    expect(byType.HYBRID_RRF?.status).toBe('success')
    expect(byType.HYBRID_RERANK).toMatchObject({
      status: 'error',
      error: 'Rerank provider timeout',
    })
  })

  it('marks every pending route cancelled when the shared signal aborts', async () => {
    const controller = new AbortController()
    const settled: RetrievalStrategyRun[] = []
    const search = async (): Promise<RetrievalSearchResponse> => {
      controller.abort()
      throw new DOMException('Aborted', 'AbortError')
    }

    await runRetrievalComparison({
      knowledgeBaseId: 12,
      query: 'cancel',
      topK: 5,
      signal: controller.signal,
      search,
      now: () => 10,
      onSettled: (run) => settled.push(run),
    })

    expect(settled).toHaveLength(4)
    expect(settled.every((run) => run.status === 'cancelled')).toBe(true)
  })
})

function response(retrieverType: RetrieverType): RetrievalSearchResponse {
  const scoreType = {
    DENSE: 'COSINE',
    BM25: 'BM25',
    HYBRID_RRF: 'RRF',
    HYBRID_RERANK: 'RERANK',
  } as const
  return {
    query: 'query',
    knowledgeBaseId: 12,
    model: null,
    dimension: 0,
    retrieverType,
    metric: scoreType[retrieverType],
    topK: 5,
    results: [],
    rerank: null,
  }
}
