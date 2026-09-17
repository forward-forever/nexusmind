<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import AgentMessage from './AgentMessage.vue'
import AgentSourceCard from './AgentSourceCard.vue'
import AgentToolTrace from './AgentToolTrace.vue'
import { useAgentChat } from '@/composables/useAgentChat'
import {
  agentSourceDomId,
  agentSourceKey,
  type AgentCitationTarget,
} from '@/utils/agentSource'

const props = defineProps<{
  knowledgeBaseId: number
  knowledgeBaseName: string
  ready: boolean
}>()

const message = ref('')
const highlightedSourceKey = ref<string | null>(null)
const expandedSourceRuns = ref<ReadonlySet<string>>(new Set())
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
  highlightedSourceKey.value = null
  void send(props.knowledgeBaseId, normalized)
}

function resetConversation(): void {
  newConversation()
  message.value = ''
  highlightedSourceKey.value = null
  expandedSourceRuns.value = new Set()
}

async function focusSource(target: AgentCitationTarget): Promise<void> {
  highlightedSourceKey.value = agentSourceKey(target.runId, target.sourceId)
  setSourcesExpanded(target.runId, true)
  await nextTick()
  document
    .getElementById(agentSourceDomId(target.runId, target.sourceId))
    ?.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' })
}

function sourceHighlighted(runId: string, sourceId: string): boolean {
  return highlightedSourceKey.value === agentSourceKey(runId, sourceId)
}

function setSourcesExpanded(runId: string, expanded: boolean): void {
  const next = new Set(expandedSourceRuns.value)
  if (expanded) next.add(runId)
  else next.delete(runId)
  expandedSourceRuns.value = next
}

function sourcesToggled(runId: string, event: Event): void {
  setSourcesExpanded(runId, (event.currentTarget as HTMLDetailsElement).open)
}
</script>

<template>
  <section class="section-panel agent-chat-panel">
    <div class="section-heading">
      <div>
        <p class="eyebrow">Stateful · Tool calling</p>
        <h2>Agent Chat · {{ knowledgeBaseName }}</h2>
      </div>
      <button class="button secondary small" type="button" @click="resetConversation">
        New Conversation
      </button>
    </div>

    <div v-if="!ready" class="chat-unavailable">
      请先上传并完成至少一份文档的 Process 和 Index。
    </div>

    <div class="agent-conversation" aria-live="polite">
      <template v-if="state.messages.length">
        <div v-for="item in state.messages" :key="item.id" class="agent-turn">
          <AgentMessage :message="item" @citation="focusSource" />
          <template v-if="item.role === 'ASSISTANT' && item.run">
            <AgentToolTrace :run="item.run" />
            <details
              v-if="item.run.sources.length && item.run.runId"
              class="agent-sources"
              :open="expandedSourceRuns.has(item.run.runId)"
              @toggle="sourcesToggled(item.run.runId, $event)"
            >
              <summary>Sources · {{ item.run.sources.length }}</summary>
              <div class="agent-source-list">
                <AgentSourceCard
                  v-for="source in item.run.sources"
                  :key="`${item.run.runId}-${source.sourceId}`"
                  :run-id="item.run.runId"
                  :source="source"
                  :highlighted="sourceHighlighted(item.run.runId, source.sourceId)"
                />
              </div>
            </details>
          </template>
        </div>
      </template>
      <div v-else class="agent-empty-state">
        <strong>Ask directly, or let the model decide when knowledge tools are needed.</strong>
        <p>The same session remembers successful User and final Assistant turns.</p>
      </div>
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
