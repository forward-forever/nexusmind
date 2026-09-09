export type KnowledgeBaseStatus = 'ACTIVE' | 'DISABLED'

export interface KnowledgeBase {
  id: number
  name: string
  description: string | null
  embeddingModel: string
  embeddingDimension: number
  status: KnowledgeBaseStatus
  createdAt: string
  updatedAt: string
}

export interface CreateKnowledgeBaseRequest {
  name: string
  description?: string
}
