<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import AgentMessage from './AgentMessage.vue'
import AgentSourceCard from './AgentSourceCard.vue'
import AgentToolTrace from './AgentToolTrace.vue'
import { useAgentChat } from '@/composables/useAgentChat'

const props = defineProps<{
  knowledgeBaseId: number
  knowledgeBaseName: string
  ready: boolean
}>()

const message = ref('')
const highlightedSourceId = ref<string | null>(null)
const { state, send, stop, newConversation } = useAgentChat()

const active = computed(
  () => state.value.status === 'connecting' || state.value.status === 'streaming',
)

watch(
  () => props.knowledgeBaseId,
  () => resetConversation(),
)

function submit(): void {
  const normalized = message.value.trim()
  if (!props.ready || !normalized || active.value) return
  message.value = ''
  highlightedSourceId.value = null
  void send(props.knowledgeBaseId, normalized)
}

function resetConversation(): void {
  newConversation()
  message.value = ''
  highlightedSourceId.value = null
}

async function focusSource(sourceId: string): Promise<void> {
  highlightedSourceId.value = sourceId
  await nextTick()
  document
    .getElementById(`agent-source-${sourceId}`)
    ?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}
</script>

<template>
  <section class="section-panel agent-chat-panel">
    <div class="section-heading">
      <div>
        <p class="eyebrow">Stateful · Tool calling</p>
        <h2>Agent Chat · {{ knowledgeBaseName }}</h2>
      </div>
      <button class="button secondary small" type="button" :disabled="active" @click="resetConversation">
        New Conversation
      </button>
    </div>

    <div v-if="!ready" class="chat-unavailable">
      请先上传并完成至少一份文档的 Process 和 Index。
    </div>

    <div v-if="state.messages.length" class="agent-conversation" aria-live="polite">
      <div v-for="item in state.messages" :key="item.id" class="agent-turn">
        <AgentMessage :message="item" @citation="focusSource" />
        <template v-if="item.role === 'ASSISTANT' && item.run">
          <AgentToolTrace :run="item.run" />
          <section v-if="item.run.sources.length" class="agent-sources" aria-label="Agent sources">
            <h3>Sources</h3>
            <div class="agent-source-list">
              <AgentSourceCard
                v-for="source in item.run.sources"
                :key="source.sourceId"
                :source="source"
                :highlighted="source.sourceId === highlightedSourceId"
              />
            </div>
          </section>
        </template>
      </div>
    </div>
    <div v-else class="agent-empty-state">
      <strong>Ask directly, or let the model decide when knowledge tools are needed.</strong>
      <p>The same session remembers successful User and final Assistant turns.</p>
    </div>

    <form class="question-form" @submit.prevent="submit">
      <label for="agent-message">Message</label>
      <textarea
        id="agent-message"
        v-model="message"
        rows="3"
        maxlength="4000"
        :disabled="!ready"
        placeholder="Ask the Agent, request a knowledge search, or continue the conversation…"
      />
      <div class="question-controls">
        <span class="agent-session-label">
          Session: {{ state.sessionId ? `${state.sessionId.slice(0, 8)}…` : 'new' }}
        </span>
        <span class="character-count">{{ message.length }} / 4000</span>
        <button v-if="active" class="button danger" type="button" @click="stop">Stop</button>
        <button v-else class="button primary" type="submit" :disabled="!ready || !message.trim()">
          Send
        </button>
      </div>
    </form>
  </section>
</template>
