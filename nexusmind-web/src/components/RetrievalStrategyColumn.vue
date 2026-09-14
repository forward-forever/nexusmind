<script setup lang="ts">
import RetrievalHitCard from './RetrievalHitCard.vue'
import type { RetrievalStrategyRun } from '@/types/retrieval'

defineProps<{
  run: RetrievalStrategyRun
}>()

function title(value: string): string {
  return value.replace('_', ' + ').replace('HYBRID + ', 'HYBRID ')
}
</script>

<template>
  <section class="retrieval-strategy-column" :data-status="run.status">
    <header class="retrieval-strategy-heading">
      <div>
        <h3>{{ title(run.retrieverType) }}</h3>
        <span v-if="run.elapsedMs !== null">
          Client request elapsed {{ Math.round(run.elapsedMs) }} ms
        </span>
      </div>
      <span class="strategy-status">{{ run.status }}</span>
    </header>

    <p v-if="run.status === 'idle'" class="strategy-placeholder">Run a comparison to inspect hits.</p>
    <p v-else-if="run.status === 'loading'" class="strategy-placeholder">Retrieving…</p>
    <p v-else-if="run.status === 'cancelled'" class="strategy-placeholder">Request cancelled.</p>
    <div v-else-if="run.status === 'error'" class="strategy-error">
      <strong>{{ run.retrieverType }} unavailable</strong>
      <p>{{ run.error }}</p>
    </div>
    <p
      v-else-if="run.result && run.result.results.length === 0"
      class="strategy-placeholder"
    >
      No visible retrieval hits.
    </p>
    <div v-else-if="run.result" class="retrieval-hit-list">
      <RetrievalHitCard
        v-for="(hit, index) in run.result.results"
        :key="hit.chunkId"
        :hit="hit"
        :rank="index + 1"
      />
    </div>
  </section>
</template>
