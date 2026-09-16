<script setup lang="ts">
import { computed } from 'vue'
import { parseCitations } from '@/utils/citation'
import type { AgentConversationMessage } from '@/types/agent'

const props = defineProps<{ message: AgentConversationMessage }>()
const emit = defineEmits<{ citation: [sourceId: string] }>()

const sourceIds = computed(
  () => new Set(props.message.run?.sources.map((source) => source.sourceId) ?? []),
)
const segments = computed(() => parseCitations(props.message.content, sourceIds.value))
</script>

<template>
  <article class="agent-message" :data-role="message.role" :data-status="message.status">
    <span class="agent-message-role">{{ message.role === 'USER' ? 'You' : 'NexusMind' }}</span>
    <div v-if="message.content" class="agent-message-content">
      <template v-for="(segment, index) in segments" :key="`${index}-${segment.value}`">
        <span v-if="segment.type === 'text'">{{ segment.value }}</span>
        <button
          v-else-if="segment.valid"
          class="citation-link"
          type="button"
          @click="emit('citation', segment.sourceId)"
        >
          {{ segment.value }}
        </button>
        <span v-else class="citation-invalid">{{ segment.value }}</span>
      </template>
    </div>
    <p v-else-if="message.status === 'streaming'" class="agent-thinking">Agent is working…</p>
    <p v-else-if="message.status === 'cancelled'" class="agent-message-note">Stopped</p>
  </article>
</template>
