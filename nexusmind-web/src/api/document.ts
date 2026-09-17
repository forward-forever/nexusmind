import { requestJson } from './http'
import type { DocumentSummary, DocumentTask, UploadDocumentResponse } from '@/types/document'

export function listDocuments(knowledgeBaseId: number): Promise<DocumentSummary[]> {
  return requestJson(`/api/knowledge-bases/${knowledgeBaseId}/documents`)
}

export function uploadDocument(
  knowledgeBaseId: number,
  file: File,
): Promise<UploadDocumentResponse> {
  const form = new FormData()
  form.append('file', file)
  return requestJson(`/api/knowledge-bases/${knowledgeBaseId}/documents`, {
    method: 'POST',
    body: form,
  })
}

export function processDocument(documentId: number): Promise<DocumentTask> {
  return requestJson(`/api/documents/${documentId}/process`, { method: 'POST' })
}

export function indexDocument(documentId: number): Promise<DocumentTask> {
  return requestJson(`/api/documents/${documentId}/index`, { method: 'POST' })
}

export function getDocumentTask(taskId: number): Promise<DocumentTask> {
  return requestJson(`/api/document-tasks/${taskId}`)
}

export function listActiveDocumentTasks(knowledgeBaseId: number): Promise<DocumentTask[]> {
  return requestJson(`/api/knowledge-bases/${knowledgeBaseId}/document-tasks?active=true`)
}
