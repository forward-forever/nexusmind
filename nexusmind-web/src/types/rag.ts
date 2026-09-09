export interface RagSource {
  id: string
  chunkId: number
  documentId: number
  fileName: string
  pageNo: number | null
  sectionTitle: string | null
  score: number
}

export interface SourcesEvent {
  type: 'sources'
  sources: RagSource[]
}

export interface DeltaEvent {
  type: 'delta'
  content: string
}

export interface DoneEvent {
  type: 'done'
  model: string
  elapsedMs: number
}

export interface ErrorEvent {
  type: 'error'
  code: string
  message: string
}

export type RagStreamEvent = SourcesEvent | DeltaEvent | DoneEvent | ErrorEvent

export interface RagRequest {
  question: string
  topK: number
}

export type RagStreamStatus = 'idle' | 'connecting' | 'streaming' | 'done' | 'error' | 'cancelled'

export interface RagStreamState {
  status: RagStreamStatus
  answer: string
  sources: RagSource[]
  model: string | null
  elapsedMs: number | null
  error: string | null
}
