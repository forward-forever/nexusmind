<script setup lang="ts">
import type { KnowledgeBase } from '@/types/knowledge'

const props = defineProps<{
  knowledgeBases: KnowledgeBase[]
  selectedId: number | null
  selectedKnowledgeBase: KnowledgeBase | null
  documentCount: number
  indexedDocumentCount: number
}>()

const emit = defineEmits<{ select: [id: number] }>()

function selectionChanged(event: Event): void {
  const id = Number((event.target as HTMLSelectElement).value)
  if (Number.isInteger(id) && props.knowledgeBases.some((item) => item.id === id)) {
    emit('select', id)
  }
}
</script>

<template>
  <section class="workspace-context-bar" aria-label="Workspace context">
    <label>
      <span>Knowledge Base</span>
      <select
        :value="selectedId ?? ''"
        :disabled="knowledgeBases.length === 0"
        @change="selectionChanged"
      >
        <option v-if="knowledgeBases.length === 0" value="">No knowledge bases</option>
        <option v-for="knowledgeBase in knowledgeBases" :key="knowledgeBase.id" :value="knowledgeBase.id">
          {{ knowledgeBase.name }}
        </option>
      </select>
    </label>
    <div v-if="selectedKnowledgeBase" class="workspace-context-metrics">
      <span>{{ documentCount }} documents</span>
      <span>{{ indexedDocumentCount }} indexed</span>
      <span>{{ selectedKnowledgeBase.embeddingDimension }}d · COSINE</span>
    </div>
  </section>
</template>
