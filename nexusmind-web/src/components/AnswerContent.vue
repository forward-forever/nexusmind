<script setup lang="ts">
import { computed } from 'vue'
import { parseCitations } from '@/utils/citation'
import type { RagSource } from '@/types/rag'

const props = defineProps<{ answer: string; sources: RagSource[] }>()
const emit = defineEmits<{ citation: [sourceId: string] }>()

const sourceIds = computed(() => new Set(props.sources.map((source) => source.id)))
const segments = computed(() => parseCitations(props.answer, sourceIds.value))
</script>

<template>
  <div class="answer-content">
    <template v-for="(segment, index) in segments" :key="`${index}-${segment.value}`">
      <span v-if="segment.type === 'text'">{{ segment.value }}</span>
      <button
        v-else-if="segment.valid"
        class="citation-link"
        type="button"
        :aria-label="`View source ${segment.sourceId}`"
        @click="emit('citation', segment.sourceId)"
      >
        {{ segment.value }}
      </button>
      <span
        v-else
        class="citation-invalid"
        :title="`${segment.sourceId} is not in returned sources`"
      >
        {{ segment.value }}
      </span>
    </template>
  </div>
</template>
