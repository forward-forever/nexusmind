<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import RetrievalStrategyColumn from './RetrievalStrategyColumn.vue'
import {
  idleRetrievalRun,
  loadingRetrievalRun,
  RETRIEVER_TYPES,
  runRetrievalComparison,
} from '@/composables/retrievalComparison'
import type { RetrievalStrategyRun, RetrieverType } from '@/types/retrieval'

const props = defineProps<{
  knowledgeBaseId: number
  knowledgeBaseName: string
  ready: boolean
}>()

const question = ref('')
const topK = ref(5)
const runs = ref(createRuns('idle'))
let activeController: AbortController | null = null

const active = computed(() =>
  RETRIEVER_TYPES.some((retrieverType) => runs.value[retrieverType].status === 'loading'),
)

watch(
  () => props.knowledgeBaseId,
  () => {
    cancel()
    question.value = ''
    runs.value = createRuns('idle')
  },
)

onBeforeUnmount(cancel)

async function run(): Promise<void> {
  const normalizedQuestion = question.value.trim()
  if (!props.ready || !normalizedQuestion || active.value) return

  const controller = new AbortController()
  activeController = controller
  runs.value = createRuns('loading')
  await runRetrievalComparison({
    knowledgeBaseId: props.knowledgeBaseId,
    query: normalizedQuestion,
    topK: topK.value,
    signal: controller.signal,
    onSettled(settledRun) {
      runs.value = { ...runs.value, [settledRun.retrieverType]: settledRun }
    },
  })
  if (activeController === controller) activeController = null
}

function cancel(): void {
  activeController?.abort()
  activeController = null
}

function createRuns(status: 'idle' | 'loading'): Record<RetrieverType, RetrievalStrategyRun> {
  return Object.fromEntries(
    RETRIEVER_TYPES.map((retrieverType) => [
      retrieverType,
      status === 'idle' ? idleRetrievalRun(retrieverType) : loadingRetrievalRun(retrieverType),
    ]),
  ) as Record<RetrieverType, RetrievalStrategyRun>
}
</script>

<template>
  <section class="section-panel retrieval-debug-panel">
    <div class="section-heading">
      <div>
        <p class="eyebrow">Development tool · single-query inspection</p>
        <h2>Retrieval Lab</h2>
        <p class="section-hint">
          Compare four retrieval pipelines against {{ knowledgeBaseName }}. Product RAG remains
          DENSE by default.
        </p>
      </div>
      <span class="model-chip">V2 · Retrieval Quality</span>
    </div>

    <div v-if="!ready" class="chat-unavailable">
      请先准备至少一份 READY + INDEXED 文档，再运行 Retrieval Comparison。
    </div>

    <form class="retrieval-debug-form" @submit.prevent="run">
      <label for="retrieval-debug-question">Question</label>
      <textarea
        id="retrieval-debug-question"
        v-model="question"
        rows="3"
        maxlength="4000"
        :disabled="!ready || active"
        placeholder="Run the same question through DENSE, BM25, HYBRID_RRF and HYBRID_RERANK…"
      />
      <div class="question-controls">
        <label class="top-k-control">
          Top K
          <select v-model.number="topK" :disabled="!ready || active">
            <option v-for="value in 10" :key="value" :value="value">{{ value }}</option>
          </select>
        </label>
        <span class="character-count">{{ question.length }} / 4000</span>
        <button v-if="active" class="button danger" type="button" @click="cancel">Cancel</button>
        <button v-else class="button primary" type="submit" :disabled="!ready || !question.trim()">
          Run Comparison
        </button>
      </div>
    </form>

    <div class="retrieval-comparison-grid" aria-live="polite">
      <RetrievalStrategyColumn
        v-for="retrieverType in RETRIEVER_TYPES"
        :key="retrieverType"
        :run="runs[retrieverType]"
      />
    </div>
  </section>
</template>
