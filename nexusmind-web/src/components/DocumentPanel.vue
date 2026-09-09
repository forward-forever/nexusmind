<script setup lang="ts">
import { ref } from 'vue'
import type { DocumentSummary } from '@/types/document'

const props = defineProps<{
  documents: DocumentSummary[]
  loading: boolean
  uploadBusy: boolean
  activeDocumentId: number | null
  activeAction: 'process' | 'index' | 'prepare' | null
}>()

const emit = defineEmits<{
  upload: [file: File]
  process: [document: DocumentSummary]
  index: [document: DocumentSummary]
  prepare: [document: DocumentSummary]
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

function canPrepare(document: DocumentSummary): boolean {
  return canProcess(document) || canIndex(document)
}

function isBusy(document: DocumentSummary): boolean {
  return props.activeDocumentId === document.id
}

function busyLabel(document: DocumentSummary): string {
  if (!isBusy(document)) return ''
  if (props.activeAction === 'process') return 'Processing…'
  if (props.activeAction === 'index') return 'Indexing…'
  return 'Preparing…'
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
        </div>
        <div class="document-actions">
          <span v-if="isBusy(document)" class="working-label">{{ busyLabel(document) }}</span>
          <template v-else>
            <button
              v-if="canProcess(document)"
              class="button secondary small"
              type="button"
              :disabled="activeDocumentId !== null"
              @click="emit('process', document)"
            >
              Process
            </button>
            <button
              v-if="canIndex(document)"
              class="button secondary small"
              type="button"
              :disabled="activeDocumentId !== null"
              @click="emit('index', document)"
            >
              Index
            </button>
            <button
              v-if="canPrepare(document)"
              class="button quiet small"
              type="button"
              :disabled="activeDocumentId !== null"
              @click="emit('prepare', document)"
            >
              Prepare for RAG
            </button>
          </template>
        </div>
      </article>
    </div>
  </section>
</template>
