<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import DocumentPanel from '@/components/DocumentPanel.vue'
import AgentChatPanel from '@/components/AgentChatPanel.vue'
import KnowledgeBasePanel from '@/components/KnowledgeBasePanel.vue'
import RagChatPanel from '@/components/RagChatPanel.vue'
import RetrievalDebugPanel from '@/components/RetrievalDebugPanel.vue'
import WorkspaceContextBar from '@/components/WorkspaceContextBar.vue'
import { createKnowledgeBase, listKnowledgeBases } from '@/api/knowledge'
import { indexDocument, listDocuments, processDocument, uploadDocument } from '@/api/document'
import { errorMessage } from '@/api/http'
import type { CreateKnowledgeBaseRequest, KnowledgeBase } from '@/types/knowledge'
import type { DocumentSummary } from '@/types/document'

type NoticeKind = 'success' | 'info' | 'error'
type DocumentAction = 'process' | 'index' | 'prepare'
type WorkspaceTab = 'knowledge' | 'agent' | 'rag' | 'retrieval'

const workspaceTabs: ReadonlyArray<{ id: WorkspaceTab; label: string }> = [
  { id: 'knowledge', label: 'Knowledge' },
  { id: 'agent', label: 'Agent Chat' },
  { id: 'rag', label: 'RAG Chat' },
  { id: 'retrieval', label: 'Retrieval Lab' },
]

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
const activeTab = ref<WorkspaceTab>('knowledge')
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
const indexedDocumentCount = computed(
  () => documents.value.filter((document) => document.indexStatus === 'INDEXED').length,
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
      <div class="version-label"><span></span> V3 · Agent</div>
    </header>

    <div v-if="notice" class="notice" :data-kind="notice.kind" role="status">
      <span>{{ notice.message }}</span>
      <button type="button" aria-label="Dismiss notification" @click="notice = null">×</button>
    </div>

    <nav class="workbench-tabs" role="tablist" aria-label="NexusMind workbench">
      <button
        v-for="tab in workspaceTabs"
        :id="`tab-${tab.id}`"
        :key="tab.id"
        role="tab"
        type="button"
        :aria-controls="`panel-${tab.id}`"
        :aria-selected="activeTab === tab.id"
        :tabindex="activeTab === tab.id ? 0 : -1"
        @click="activeTab = tab.id"
      >
        {{ tab.label }}
      </button>
    </nav>

    <main class="workbench-content">
      <section
        v-show="activeTab === 'knowledge'"
        id="panel-knowledge"
        class="knowledge-workspace"
        role="tabpanel"
        aria-labelledby="tab-knowledge"
      >
        <KnowledgeBasePanel
          :knowledge-bases="knowledgeBases"
          :selected-id="selectedKnowledgeBaseId"
          :loading="loadingKnowledgeBases"
          :creating="creatingKnowledgeBase"
          @select="selectedKnowledgeBaseId = $event"
          @create="handleCreate"
        />
        <div class="knowledge-document-workspace">
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
          </template>
          <section v-else class="empty-workspace">
            <p class="eyebrow">Knowledge workspace</p>
            <h2>Create your first Knowledge Base</h2>
            <p>Then upload, process, and index a PDF, Markdown, or TXT document.</p>
          </section>
        </div>
      </section>

      <section
        v-show="activeTab === 'agent'"
        id="panel-agent"
        class="functional-workspace"
        role="tabpanel"
        aria-labelledby="tab-agent"
      >
        <WorkspaceContextBar
          :knowledge-bases="knowledgeBases"
          :selected-id="selectedKnowledgeBaseId"
          :selected-knowledge-base="selectedKnowledgeBase"
          :document-count="documents.length"
          :indexed-document-count="indexedDocumentCount"
          @select="selectedKnowledgeBaseId = $event"
        />
        <AgentChatPanel
          v-if="selectedKnowledgeBase"
          :key="`agent-${selectedKnowledgeBase.id}`"
          :knowledge-base-id="selectedKnowledgeBase.id"
          :knowledge-base-name="selectedKnowledgeBase.name"
          :ready="hasIndexedDocument"
        />
        <section v-else class="empty-workspace functional-empty">
          <h2>Select or create a Knowledge Base first</h2>
        </section>
      </section>

      <section
        v-show="activeTab === 'rag'"
        id="panel-rag"
        class="functional-workspace"
        role="tabpanel"
        aria-labelledby="tab-rag"
      >
        <WorkspaceContextBar
          :knowledge-bases="knowledgeBases"
          :selected-id="selectedKnowledgeBaseId"
          :selected-knowledge-base="selectedKnowledgeBase"
          :document-count="documents.length"
          :indexed-document-count="indexedDocumentCount"
          @select="selectedKnowledgeBaseId = $event"
        />
        <RagChatPanel
          v-if="selectedKnowledgeBase"
          :key="`rag-${selectedKnowledgeBase.id}`"
          :knowledge-base-id="selectedKnowledgeBase.id"
          :knowledge-base-name="selectedKnowledgeBase.name"
          :ready="hasIndexedDocument"
        />
        <section v-else class="empty-workspace functional-empty">
          <h2>Select or create a Knowledge Base first</h2>
        </section>
      </section>

      <section
        v-show="activeTab === 'retrieval'"
        id="panel-retrieval"
        class="functional-workspace"
        role="tabpanel"
        aria-labelledby="tab-retrieval"
      >
        <WorkspaceContextBar
          :knowledge-bases="knowledgeBases"
          :selected-id="selectedKnowledgeBaseId"
          :selected-knowledge-base="selectedKnowledgeBase"
          :document-count="documents.length"
          :indexed-document-count="indexedDocumentCount"
          @select="selectedKnowledgeBaseId = $event"
        />
        <RetrievalDebugPanel
          v-if="selectedKnowledgeBase"
          :key="`retrieval-${selectedKnowledgeBase.id}`"
          :knowledge-base-id="selectedKnowledgeBase.id"
          :knowledge-base-name="selectedKnowledgeBase.name"
          :ready="hasIndexedDocument"
        />
        <section v-else class="empty-workspace functional-empty">
          <h2>Select or create a Knowledge Base first</h2>
        </section>
      </section>
    </main>
  </div>
</template>
