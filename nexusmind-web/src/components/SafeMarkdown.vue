<script setup lang="ts">
import { computed } from 'vue'
import { renderSafeMarkdown } from '@/utils/markdown'

const props = defineProps<{ content: string; sourceIds: string[] }>()
const emit = defineEmits<{ citation: [sourceId: string] }>()

const rendered = computed(() => renderSafeMarkdown(props.content, new Set(props.sourceIds)))

function handleClick(event: MouseEvent): void {
  const element = event.target instanceof Element ? event.target : null
  const citation = element?.closest<HTMLButtonElement>('button[data-source-id]')
  if (!citation || !(event.currentTarget as HTMLElement).contains(citation)) return
  const sourceId = citation.dataset.sourceId
  if (sourceId) emit('citation', sourceId)
}
</script>

<template>
  <!-- HTML is produced by markdown-it with raw HTML disabled, then sanitized by DOMPurify. -->
  <div class="safe-markdown" @click="handleClick" v-html="rendered"></div>
</template>
