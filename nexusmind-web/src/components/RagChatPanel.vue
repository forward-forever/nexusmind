<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import AnswerContent from './AnswerContent.vue'
import SourceList from './SourceList.vue'
import { useRagStream } from '@/composables/useRagStream'

const props = defineProps<{
  knowledgeBaseId: number
  knowledgeBaseName: string
  ready: boolean
}>()

const question = ref('')
const topK = ref(5)
const highlightedSourceId = ref<string | null>(null)
const { state, start, stop, reset } = useRagStream()

const active = computed(
  () => state.value.status === 'connecting' || state.value.status === 'streaming',
)
const statusText = computed(() => {
  switch (state.value.status) {
    case 'connecting':
      return 'Retrieving sources and connecting to the model…'
    case 'streaming':
      return 'Streaming answer…'
    case 'done':
      return `Completed${state.value.elapsedMs === null ? '' : ` in ${state.value.elapsedMs} ms`}`
    case 'cancelled':
      return 'Generation stopped'
    default:
      return ''
  }
})

watch(
  () => props.knowledgeBaseId,
  () => {
    reset()
    question.value = ''
    highlightedSourceId.value = null
  },
)

function submit(): void {
  const normalizedQuestion = question.value.trim()
  if (!props.ready || !normalizedQuestion || active.value) return
  highlightedSourceId.value = null
  void start(props.knowledgeBaseId, normalizedQuestion, topK.value)
}

async function focusSource(sourceId: string): Promise<void> {
  highlightedSourceId.value = sourceId
  await nextTick()
  document
    .getElementById(`source-${sourceId}`)
    ?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}
</script>

<template>
  <section class="section-panel rag-panel">
    <div class="section-heading">
      <div>
        <p class="eyebrow">Stateless · Dense retrieval</p>
        <h2>Ask {{ knowledgeBaseName }}</h2>
      </div>
      <span class="model-chip">qwen3.5-flash</span>
    </div>

    <div v-if="!ready" class="chat-unavailable">
      请先上传并完成至少一份文档的 Process 和 Index。
    </div>

    <form class="question-form" @submit.prevent="submit">
      <label for="rag-question">Question</label>
      <textarea
        id="rag-question"
        v-model="question"
        rows="3"
        maxlength="4000"
        :disabled="!ready || active"
        placeholder="Ask a question grounded in the indexed documents…"
      />
      <div class="question-controls">
        <label class="top-k-control">
          Top K
          <select v-model.number="topK" :disabled="!ready || active">
            <option v-for="value in 10" :key="value" :value="value">{{ value }}</option>
          </select>
        </label>
        <span class="character-count">{{ question.length }} / 4000</span>
        <button v-if="active" class="button danger" type="button" @click="stop">Stop</button>
        <button v-else class="button primary" type="submit" :disabled="!ready || !question.trim()">
          Send
        </button>
      </div>
    </form>

    <div v-if="state.status !== 'idle'" class="answer-area" aria-live="polite">
      <div class="answer-heading">
        <h3>Answer</h3>
        <span v-if="statusText" class="stream-status" :data-status="state.status">{{
          statusText
        }}</span>
      </div>
      <p v-if="state.status === 'connecting' && !state.answer" class="answer-placeholder">
        Building grounded context…
      </p>
      <AnswerContent
        v-if="state.answer"
        :answer="state.answer"
        :sources="state.sources"
        @citation="focusSource"
      />
      <p v-if="state.error" class="inline-error">{{ state.error }}</p>
      <SourceList :sources="state.sources" :highlighted-source-id="highlightedSourceId" />
      <footer v-if="state.model" class="answer-footer">
        {{ state.model }} · {{ state.elapsedMs }} ms
      </footer>
    </div>
  </section>
</template>
