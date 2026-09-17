<script setup lang="ts">
import { ref } from 'vue'
import type { DocumentSummary, DocumentTask } from '@/types/document'

const props = defineProps<{
  documents: DocumentSummary[]
  loading: boolean
  uploadBusy: boolean
  tasks: DocumentTask[]
}>()

const emit = defineEmits<{
  upload: [file: File]
  process: [document: DocumentSummary]
  index: [document: DocumentSummary]
}>()

const fileInput = ref<HTMLInputElement | null>(null)

function chooseFile(): void {
  fileInput.value?.click()
}

function fileSelected(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) emit('upload', file)
  input.value = ''
}

function canProcess(document: DocumentSummary): boolean {
  return document.status === 'UPLOADED' || document.status === 'FAILED'
}

function canIndex(document: DocumentSummary): boolean {
  return (
    document.status === 'READY' &&
    (document.indexStatus === 'NOT_INDEXED' || document.indexStatus === 'FAILED')
  )
}

function currentTask(document: DocumentSummary): DocumentTask | undefined {
  const candidates = props.tasks.filter((task) => task.documentId === document.id)
  return candidates.find((task) => task.status === 'PENDING' || task.status === 'RUNNING')
    ?? candidates.find((task) => task.status === 'FAILED' && (
      (task.taskType === 'PROCESS' && document.status === 'FAILED')
      || (task.taskType === 'INDEX' && document.indexStatus === 'FAILED')
    ))
}

function taskLabel(task: DocumentTask): string {
  if (task.status === 'PENDING') return 'Queued'
  if (task.status === 'RUNNING') return task.taskType === 'PROCESS' ? 'Processing…' : 'Indexing…'
  return task.status === 'FAILED' ? 'Failed' : 'Succeeded'
}

function hasActiveTask(document: DocumentSummary, type: DocumentTask['taskType']): boolean {
  return props.tasks.some((task) => task.documentId === document.id && task.taskType === type
    && (task.status === 'PENDING' || task.status === 'RUNNING'))
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
</script>

<template>
  <section class="section-panel document-panel">
    <div class="section-heading">
      <div>
        <p class="eyebrow">Ingestion pipeline</p>
        <h2>Documents</h2>
      </div>
      <div>
        <input
          ref="fileInput"
          class="visually-hidden"
          type="file"
          accept=".pdf,.md,.markdown,.txt,application/pdf,text/plain,text/markdown"
          @change="fileSelected"
        />
        <button class="button primary" type="button" :disabled="uploadBusy" @click="chooseFile">
          {{ uploadBusy ? 'Uploading…' : 'Upload document' }}
        </button>
      </div>
    </div>
    <p class="section-hint">PDF / Markdown / TXT · 最大 20MB · 扫描 PDF 暂不支持 OCR</p>

    <p v-if="loading" class="muted-state compact">Loading documents…</p>
    <p v-else-if="documents.length === 0" class="muted-state compact">当前知识库还没有文档。</p>
    <div v-else class="document-list">
      <article v-for="document in documents" :key="document.id" class="document-row">
        <div class="document-main">
          <div class="document-title-row">
            <strong>{{ document.originalFileName }}</strong>
            <span v-if="document.indexStatus === 'INDEXED'" class="ready-label">Ready for RAG</span>
          </div>
          <div class="document-meta">
            <span>{{ formatBytes(document.fileSize) }}</span>
            <span>{{ document.chunkCount }} chunks</span>
            <span class="status-badge" :data-status="document.status.toLowerCase()">
              {{ document.status }}
            </span>
            <span class="status-badge" :data-status="document.indexStatus.toLowerCase()">
              {{ document.indexStatus }}
            </span>
          </div>
          <p v-if="document.errorMessage" class="row-error">{{ document.errorMessage }}</p>
          <p v-if="document.indexErrorMessage" class="row-error">
            {{ document.indexErrorMessage }}
          </p>
          <div v-if="currentTask(document)" class="document-task-summary" :data-status="currentTask(document)?.status.toLowerCase()">
            <strong>{{ currentTask(document) && taskLabel(currentTask(document)!) }}</strong>
            <span>Attempt {{ currentTask(document)?.attemptCount }}</span>
            <span>Task #{{ currentTask(document)?.taskId }}</span>
            <p v-if="currentTask(document)?.lastError" class="row-error">{{ currentTask(document)?.lastError }}</p>
          </div>
        </div>
        <div class="document-actions">
          <button
            v-if="canProcess(document)"
            class="button secondary small"
            type="button"
            :disabled="hasActiveTask(document, 'PROCESS')"
            @click="emit('process', document)"
          >
            {{ document.status === 'FAILED' ? 'Retry Process' : 'Process' }}
          </button>
          <button
            v-if="canIndex(document)"
            class="button secondary small"
            type="button"
            :disabled="hasActiveTask(document, 'INDEX')"
            @click="emit('index', document)"
          >
            {{ document.indexStatus === 'FAILED' ? 'Retry Index' : 'Index' }}
          </button>
        </div>
      </article>
    </div>
  </section>
</template>
