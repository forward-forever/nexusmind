<script setup lang="ts">
import { computed } from 'vue'
import SafeMarkdown from './SafeMarkdown.vue'
import type { RagSource } from '@/types/rag'

const props = defineProps<{ answer: string; sources: RagSource[] }>()
const emit = defineEmits<{ citation: [sourceId: string] }>()

const sourceIds = computed(() => props.sources.map((source) => source.id))
</script>

<template>
  <SafeMarkdown
    class="answer-content"
    :content="answer"
    :source-ids="sourceIds"
    @citation="emit('citation', $event)"
  />
</template>
