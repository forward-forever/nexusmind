import { searchKnowledgeBase } from '@/api/retrieval'
import { errorMessage } from '@/api/http'
import type {
  RetrievalSearchRequest,
  RetrievalSearchResponse,
  RetrievalStrategyRun,
  RetrieverType,
} from '@/types/retrieval'

export const RETRIEVER_TYPES: readonly RetrieverType[] = [
  'DENSE',
  'BM25',
  'HYBRID_RRF',
  'HYBRID_RERANK',
]

export type RetrievalSearch = (
  knowledgeBaseId: number,
  request: RetrievalSearchRequest,
  signal: AbortSignal,
) => Promise<RetrievalSearchResponse>

interface RunRetrievalComparisonOptions {
  knowledgeBaseId: number
  query: string
  topK: number
  signal: AbortSignal
  onSettled: (run: RetrievalStrategyRun) => void
  search?: RetrievalSearch
  now?: () => number
}

export async function runRetrievalComparison(
  options: RunRetrievalComparisonOptions,
): Promise<void> {
  const search = options.search ?? searchKnowledgeBase
  const now = options.now ?? performance.now.bind(performance)
  const requests = RETRIEVER_TYPES.map(async (retrieverType) => {
    const startedAt = now()
    try {
      const result = await search(
        options.knowledgeBaseId,
        { query: options.query, topK: options.topK, retrieverType },
        options.signal,
      )
      options.onSettled({
        retrieverType,
        status: 'success',
        result,
        error: null,
        elapsedMs: Math.max(0, now() - startedAt),
      })
    } catch (error) {
      options.onSettled({
        retrieverType,
        status: isAbortError(error) || options.signal.aborted ? 'cancelled' : 'error',
        result: null,
        error: isAbortError(error) || options.signal.aborted ? null : errorMessage(error),
        elapsedMs: Math.max(0, now() - startedAt),
      })
    }
  })

  await Promise.allSettled(requests)
}

export function idleRetrievalRun(retrieverType: RetrieverType): RetrievalStrategyRun {
  return { retrieverType, status: 'idle', result: null, error: null, elapsedMs: null }
}

export function loadingRetrievalRun(retrieverType: RetrieverType): RetrievalStrategyRun {
  return { retrieverType, status: 'loading', result: null, error: null, elapsedMs: null }
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
