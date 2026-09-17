<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import DocumentPanel from '@/components/DocumentPanel.vue'
import AgentChatPanel from '@/components/AgentChatPanel.vue'
import KnowledgeBasePanel from '@/components/KnowledgeBasePanel.vue'
import RagChatPanel from '@/components/RagChatPanel.vue'
import RetrievalDebugPanel from '@/components/RetrievalDebugPanel.vue'
import WorkspaceContextBar from '@/components/WorkspaceContextBar.vue'
import { createKnowledgeBase, listKnowledgeBases } from '@/api/knowledge'
import {
  getDocumentTask,
  indexDocument,
  listActiveDocumentTasks,
  listDocuments,
  processDocument,
  uploadDocument,
} from '@/api/document'
import { errorMessage } from '@/api/http'
import type { CreateKnowledgeBaseRequest, KnowledgeBase } from '@/types/knowledge'
import type { DocumentSummary, DocumentTask } from '@/types/document'

type NoticeKind = 'success' | 'info' | 'error'
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
const documentTasks = ref<DocumentTask[]>([])
const notice = ref<{ kind: NoticeKind; message: string } | null>(null)
const activeTab = ref<WorkspaceTab>('knowledge')
let noticeTimer: ReturnType<typeof setTimeout> | null = null
let documentLoadVersion = 0
let taskPollTimer: ReturnType<typeof setTimeout> | null = null
let taskPollVersion = 0

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
onBeforeUnmount(stopTaskPolling)

watch(selectedKnowledgeBaseId, (id) => {
  stopTaskPolling()
  taskPollVersion++
  documentTasks.value = []
  documents.value = []
  if (id !== null) void Promise.all([refreshDocuments(id), loadActiveTasks(id)])
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
  try {
    const task = await processDocument(document.id)
    upsertTask(task)
    showNotice('info', `${document.originalFileName} Process 已进入队列`)
    ensureTaskPolling()
  } catch (error) {
    showNotice('error', errorMessage(error))
  }
}

async function handleIndex(document: DocumentSummary): Promise<void> {
  try {
    const task = await indexDocument(document.id)
    upsertTask(task)
    showNotice('info', `${document.originalFileName} Index 已进入队列`)
    ensureTaskPolling()
  } catch (error) {
    showNotice('error', errorMessage(error))
  }
}

async function loadActiveTasks(knowledgeBaseId: number): Promise<void> {
  const version = taskPollVersion
  try {
    const tasks = await listActiveDocumentTasks(knowledgeBaseId)
    if (version !== taskPollVersion || knowledgeBaseId !== selectedKnowledgeBaseId.value) return
    documentTasks.value = tasks
    ensureTaskPolling()
  } catch (error) {
    if (version === taskPollVersion) showNotice('error', errorMessage(error))
  }
}

function upsertTask(task: DocumentTask): void {
  const index = documentTasks.value.findIndex((item) => item.taskId === task.taskId)
  if (index < 0) documentTasks.value = [...documentTasks.value, task]
  else documentTasks.value = documentTasks.value.map((item, itemIndex) => itemIndex === index ? task : item)
}

function ensureTaskPolling(): void {
  if (taskPollTimer || !documentTasks.value.some(isActiveTask)) return
  taskPollTimer = setTimeout(() => void pollTasks(), 1500)
}

async function pollTasks(): Promise<void> {
  taskPollTimer = null
  const version = taskPollVersion
  const active = documentTasks.value.filter(isActiveTask)
  if (active.length === 0) return
  try {
    const updated = await Promise.all(active.map((task) => getDocumentTask(task.taskId)))
    if (version !== taskPollVersion) return
    const hadTerminal = updated.some((task) => !isActiveTask(task))
    updated.forEach(upsertTask)
    if (hadTerminal) await refreshDocuments()
  } catch (error) {
    if (version === taskPollVersion) showNotice('error', errorMessage(error))
  }
  if (version === taskPollVersion) ensureTaskPolling()
}

function stopTaskPolling(): void {
  if (taskPollTimer) clearTimeout(taskPollTimer)
  taskPollTimer = null
}

function isActiveTask(task: DocumentTask): boolean {
  return task.status === 'PENDING' || task.status === 'RUNNING'
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
      <div class="version-label"><span></span> V4 · Production</div>
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
              :tasks="documentTasks"
              @upload="handleUpload"
              @process="handleProcess"
              @index="handleIndex"
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
