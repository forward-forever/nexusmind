import { requestJson } from './http'
import type {
  RetrievalSearchRequest,
  RetrievalSearchResponse,
} from '@/types/retrieval'

export function searchKnowledgeBase(
  knowledgeBaseId: number,
  request: RetrievalSearchRequest,
  signal: AbortSignal,
): Promise<RetrievalSearchResponse> {
  return requestJson(`/api/knowledge-bases/${knowledgeBaseId}/search`, {
    method: 'POST',
    body: JSON.stringify(request),
    signal,
  })
}
