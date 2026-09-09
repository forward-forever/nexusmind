import { requestJson } from './http'
import type { CreateKnowledgeBaseRequest, KnowledgeBase } from '@/types/knowledge'

export function listKnowledgeBases(): Promise<KnowledgeBase[]> {
  return requestJson('/api/knowledge-bases')
}

export function createKnowledgeBase(request: CreateKnowledgeBaseRequest): Promise<KnowledgeBase> {
  return requestJson('/api/knowledge-bases', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}
