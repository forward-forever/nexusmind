export type RetrieverType = 'DENSE' | 'BM25' | 'HYBRID_RRF' | 'HYBRID_RERANK'

export type RetrievalScoreType = 'COSINE' | 'BM25' | 'RRF' | 'RERANK'

export interface RetrievalContribution {
  retrieverType: 'DENSE' | 'BM25'
  rank: number
  rawScore: number
  rawScoreType: 'COSINE' | 'BM25'
}

export interface RerankProvenance {
  preRerankRank: number
  preRerankScore: number
  preRerankScoreType: 'RRF'
}

export interface RerankUsage {
  promptTokens: number | null
  totalTokens: number | null
}

export interface RerankExecutionMetadata {
  model: string
  candidateCount: number
  requestedTopN: number
  latencyMs: number
  usage: RerankUsage | null
}

export interface RetrievalHitResponse {
  chunkId: number
  documentId: number
  fileName: string
  chunkIndex: number
  score: number
  scoreType: RetrievalScoreType
  content: string
  pageNo: number | null
  sectionTitle: string | null
  contributions: RetrievalContribution[]
  rerank: RerankProvenance | null
}

export interface RetrievalSearchRequest {
  query: string
  topK: number
  retrieverType: RetrieverType
}

export interface RetrievalSearchResponse {
  query: string
  knowledgeBaseId: number
  model: string | null
  dimension: number
  retrieverType: RetrieverType
  metric: RetrievalScoreType
  topK: number
  results: RetrievalHitResponse[]
  rerank: RerankExecutionMetadata | null
}

export type RetrievalStrategyStatus = 'idle' | 'loading' | 'success' | 'error' | 'cancelled'

export interface RetrievalStrategyRun {
  retrieverType: RetrieverType
  status: RetrievalStrategyStatus
  result: RetrievalSearchResponse | null
  error: string | null
  elapsedMs: number | null
}
