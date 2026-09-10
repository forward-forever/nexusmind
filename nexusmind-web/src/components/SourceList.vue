<script setup lang="ts">
import type { RagSource } from '@/types/rag'

defineProps<{ sources: RagSource[]; highlightedSourceId: string | null }>()
</script>

<template>
  <section v-if="sources.length" class="sources-section" aria-label="Retrieved sources">
    <div class="sources-heading">
      <h3>Sources</h3>
      <span>{{ sources.length }} used in context</span>
    </div>
    <div class="source-list">
      <article
        v-for="source in sources"
        :id="`source-${source.id}`"
        :key="source.id"
        class="source-card"
        :class="{ highlighted: source.id === highlightedSourceId }"
      >
        <div class="source-id">{{ source.id }}</div>
        <div class="source-body">
          <strong>{{ source.fileName }}</strong>
          <div class="source-metadata">
            <span v-if="source.pageNo !== null">Page {{ source.pageNo }}</span>
            <span v-if="source.sectionTitle">{{ source.sectionTitle }}</span>
            <span>{{ source.scoreType }} {{ source.score.toFixed(4) }}</span>
            <span>Chunk #{{ source.chunkId }}</span>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>
