<script setup lang="ts">
import { computed } from 'vue'
import SafeMarkdown from './SafeMarkdown.vue'
import type { AgentCitationTarget } from '@/utils/agentSource'
import type { AgentConversationMessage } from '@/types/agent'

const props = defineProps<{ message: AgentConversationMessage }>()
const emit = defineEmits<{ citation: [target: AgentCitationTarget] }>()

const sourceIds = computed(() => props.message.run?.sources.map((source) => source.sourceId) ?? [])

function emitCitation(sourceId: string): void {
  const runId = props.message.run?.runId
  if (runId) emit('citation', { runId, sourceId })
}
</script>

<template>
  <article class="agent-message" :data-role="message.role" :data-status="message.status">
    <span class="agent-message-role">{{ message.role === 'USER' ? 'You' : 'NexusMind' }}</span>
    <SafeMarkdown
      v-if="message.content"
      class="agent-message-content"
      :content="message.content"
      :source-ids="sourceIds"
      @citation="emitCitation"
    />
    <p v-else-if="message.status === 'streaming'" class="agent-thinking">Agent is working…</p>
    <p v-else-if="message.status === 'cancelled'" class="agent-message-note">Stopped</p>
  </article>
</template>
