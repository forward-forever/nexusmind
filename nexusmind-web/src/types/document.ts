export type DocumentStatus = 'UPLOADED' | 'PROCESSING' | 'READY' | 'FAILED'
export type DocumentIndexStatus = 'NOT_INDEXED' | 'INDEXING' | 'INDEXED' | 'FAILED'
export type DocumentTaskType = 'PROCESS' | 'INDEX'
export type DocumentTaskStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export interface DocumentSummary {
  id: number
  originalFileName: string
  contentType: string
  fileSize: number
  status: DocumentStatus
  chunkCount: number
  indexStatus: DocumentIndexStatus
  indexedAt: string | null
  errorMessage: string | null
  indexErrorMessage: string | null
  createdAt: string
}

export interface KnowledgeDocument extends DocumentSummary {
  knowledgeBaseId: number
  storagePath: string | null
  fileSha256: string
  updatedAt: string
}

export interface UploadDocumentResponse {
  document: KnowledgeDocument
  duplicate: boolean
}

export interface DocumentTask {
  taskId: number
  documentId: number
  taskType: DocumentTaskType
  status: DocumentTaskStatus
  attemptCount: number
  enqueuedAt: string
  startedAt: string | null
  finishedAt: string | null
  lastError: string | null
}
