<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import DocumentPanel from '@/components/DocumentPanel.vue'
import KnowledgeBasePanel from '@/components/KnowledgeBasePanel.vue'
import RagChatPanel from '@/components/RagChatPanel.vue'
import { createKnowledgeBase, listKnowledgeBases } from '@/api/knowledge'
import { indexDocument, listDocuments, processDocument, uploadDocument } from '@/api/document'
import { errorMessage } from '@/api/http'
import type { CreateKnowledgeBaseRequest, KnowledgeBase } from '@/types/knowledge'
import type { DocumentSummary } from '@/types/document'

type NoticeKind = 'success' | 'info' | 'error'
type DocumentAction = 'process' | 'index' | 'prepare'

const knowledgeBases = ref<KnowledgeBase[]>([])
const selectedKnowledgeBaseId = ref<number | null>(null)
const documents = ref<DocumentSummary[]>([])
const loadingKnowledgeBases = ref(true)
const loadingDocuments = ref(false)
const creatingKnowledgeBase = ref(false)
const uploadBusy = ref(false)
const activeDocumentId = ref<number | null>(null)
const activeDocumentAction = ref<DocumentAction | null>(null)
const notice = ref<{ kind: NoticeKind; message: string } | null>(null)
let noticeTimer: ReturnType<typeof setTimeout> | null = null
let documentLoadVersion = 0

const selectedKnowledgeBase = computed(
  () =>
    knowledgeBases.value.find(
      (knowledgeBase) => knowledgeBase.id === selectedKnowledgeBaseId.value,
    ) ?? null,
)
const hasIndexedDocument = computed(() =>
  documents.value.some((document) => document.indexStatus === 'INDEXED'),
)

onMounted(() => void refreshKnowledgeBases())

watch(selectedKnowledgeBaseId, (id) => {
  documents.value = []
  if (id !== null) void refreshDocuments(id)
})

async function refreshKnowledgeBases(preferredId?: number): Promise<void> {
  loadingKnowledgeBases.value = true
  try {
    knowledgeBases.value = await listKnowledgeBases()
    if (preferredId && knowledgeBases.value.some((item) => item.id === preferredId)) {
      selectedKnowledgeBaseId.value = preferredId
    } else if (
      selectedKnowledgeBaseId.value === null ||
      !knowledgeBases.value.some((item) => item.id === selectedKnowledgeBaseId.value)
    ) {
      selectedKnowledgeBaseId.value = knowledgeBases.value[0]?.id ?? null
    }
  } catch (error) {
    showNotice('error', errorMessage(error))
  } finally {
    loadingKnowledgeBases.value = false
  }
}

async function refreshDocuments(knowledgeBaseId = selectedKnowledgeBaseId.value): Promise<void> {
  if (knowledgeBaseId === null) return
  const version = ++documentLoadVersion
  loadingDocuments.value = true
  try {
    const result = await listDocuments(knowledgeBaseId)
    if (version === documentLoadVersion && knowledgeBaseId === selectedKnowledgeBaseId.value) {
      documents.value = result
    }
  } catch (error) {
    if (version === documentLoadVersion) showNotice('error', errorMessage(error))
  } finally {
    if (version === documentLoadVersion) loadingDocuments.value = false
  }
}

async function handleCreate(request: CreateKnowledgeBaseRequest): Promise<void> {
  creatingKnowledgeBase.value = true
  try {
    const created = await createKnowledgeBase(request)
    await refreshKnowledgeBases(created.id)
    showNotice('success', `Knowledge Base “${created.name}” 已创建`)
  } catch (error) {
    showNotice('error', errorMessage(error))
  } finally {
    creatingKnowledgeBase.value = false
  }
}

async function handleUpload(file: File): Promise<void> {
  const knowledgeBaseId = selectedKnowledgeBaseId.value
  if (knowledgeBaseId === null) return
  if (file.size > 20 * 1024 * 1024) {
    showNotice('error', '文件超过 20MB 上限')
    return
  }

  uploadBusy.value = true
  try {
    const result = await uploadDocument(knowledgeBaseId, file)
    await refreshDocuments(knowledgeBaseId)
    showNotice(
      result.duplicate ? 'info' : 'success',
      result.duplicate
        ? '该文件已经存在，已使用已有 Document。'
        : `已上传 ${result.document.originalFileName}`,
    )
  } catch (error) {
    showNotice('error', errorMessage(error))
  } finally {
    uploadBusy.value = false
  }
}

async function handleProcess(document: DocumentSummary): Promise<void> {
  await runDocumentAction(document, 'process', async () => {
    await processDocument(document.id)
    showNotice('success', `${document.originalFileName} Process 完成`)
  })
}

async function handleIndex(document: DocumentSummary): Promise<void> {
  await runDocumentAction(document, 'index', async () => {
    await indexDocument(document.id)
    showNotice('success', `${document.originalFileName} 已可用于 RAG`)
  })
}

async function handlePrepare(document: DocumentSummary): Promise<void> {
  await runDocumentAction(document, 'prepare', async () => {
    let current = document
    if (current.status === 'UPLOADED' || current.status === 'FAILED') {
      current = await processDocument(current.id)
    }
    if (
      current.status === 'READY' &&
      (current.indexStatus === 'NOT_INDEXED' || current.indexStatus === 'FAILED')
    ) {
      await indexDocument(current.id)
    }
    showNotice('success', `${document.originalFileName} 已完成 Process → Index`)
  })
}

async function runDocumentAction(
  document: DocumentSummary,
  action: DocumentAction,
  operation: () => Promise<void>,
): Promise<void> {
  if (activeDocumentId.value !== null) return
  activeDocumentId.value = document.id
  activeDocumentAction.value = action
  try {
    await operation()
  } catch (error) {
    showNotice('error', errorMessage(error))
  } finally {
    activeDocumentId.value = null
    activeDocumentAction.value = null
    await refreshDocuments()
  }
}

function showNotice(kind: NoticeKind, message: string): void {
  notice.value = { kind, message }
  if (noticeTimer) clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => {
    notice.value = null
  }, 6000)
}
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="brand">
        <span class="brand-mark">N</span>
        <div>
          <h1>NexusMind</h1>
          <p>AI Knowledge & Agent Platform</p>
        </div>
      </div>
      <div class="version-label"><span></span> V1 · Dense RAG</div>
    </header>

    <div v-if="notice" class="notice" :data-kind="notice.kind" role="status">
      <span>{{ notice.message }}</span>
      <button type="button" aria-label="Dismiss notification" @click="notice = null">×</button>
    </div>

    <div class="workspace">
      <KnowledgeBasePanel
        :knowledge-bases="knowledgeBases"
        :selected-id="selectedKnowledgeBaseId"
        :loading="loadingKnowledgeBases"
        :creating="creatingKnowledgeBase"
        @select="selectedKnowledgeBaseId = $event"
        @create="handleCreate"
      />

      <main class="main-content">
        <template v-if="selectedKnowledgeBase">
          <div class="workspace-title">
            <div>
              <p class="eyebrow">Current knowledge base</p>
              <h2>{{ selectedKnowledgeBase.name }}</h2>
            </div>
            <span class="runtime-badge">
              {{ selectedKnowledgeBase.embeddingDimension }}d · COSINE
            </span>
          </div>

          <DocumentPanel
            :documents="documents"
            :loading="loadingDocuments"
            :upload-busy="uploadBusy"
            :active-document-id="activeDocumentId"
            :active-action="activeDocumentAction"
            @upload="handleUpload"
            @process="handleProcess"
            @index="handleIndex"
            @prepare="handlePrepare"
          />

          <RagChatPanel
            :key="selectedKnowledgeBase.id"
            :knowledge-base-id="selectedKnowledgeBase.id"
            :knowledge-base-name="selectedKnowledgeBase.name"
            :ready="hasIndexedDocument"
          />
        </template>
        <section v-else class="empty-workspace">
          <p class="eyebrow">NexusMind V1</p>
          <h2>Create a Knowledge Base to begin</h2>
          <p>Upload, process, index, and ask grounded questions—all from this page.</p>
        </section>
      </main>
    </div>
  </div>
</template>
