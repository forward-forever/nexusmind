<script setup lang="ts">
import { computed, ref } from 'vue'
import { formatRerankMovement, formatRetrievalScore } from '@/utils/retrieval'
import type { RetrievalHitResponse } from '@/types/retrieval'

const props = defineProps<{
  hit: RetrievalHitResponse
  rank: number
}>()

const expanded = ref(false)
const previewLimit = 260
const hasMoreContent = computed(() => props.hit.content.length > previewLimit)
const visibleContent = computed(() =>
  expanded.value || !hasMoreContent.value
    ? props.hit.content
    : `${props.hit.content.slice(0, previewLimit)}…`,
)
const movement = computed(() =>
  props.hit.rerank
    ? formatRerankMovement(props.hit.rerank.preRerankRank, props.rank)
    : null,
)
</script>

<template>
  <article class="retrieval-hit-card">
    <div class="retrieval-hit-heading">
      <span class="retrieval-rank">#{{ rank }}</span>
      <span class="retrieval-score">{{ formatRetrievalScore(hit.score, hit.scoreType) }}</span>
      <span v-if="movement" class="rank-movement" :data-movement="movement.charAt(0)">
        {{ movement }}
      </span>
    </div>

    <strong class="retrieval-file-name">{{ hit.fileName }}</strong>
    <div class="retrieval-hit-metadata">
      <span v-if="hit.pageNo !== null">Page {{ hit.pageNo }}</span>
      <span v-if="hit.sectionTitle">{{ hit.sectionTitle }}</span>
      <span>Chunk {{ hit.chunkId }}</span>
    </div>

    <div v-if="hit.rerank" class="retrieval-provenance">
      <span>Before rerank</span>
      <strong>
        #{{ hit.rerank.preRerankRank }} ·
        {{ formatRetrievalScore(hit.rerank.preRerankScore, hit.rerank.preRerankScoreType) }}
      </strong>
    </div>

    <div v-if="hit.contributions.length" class="retrieval-contributions">
      <div v-for="contribution in hit.contributions" :key="contribution.retrieverType">
        <span>{{ contribution.retrieverType }}</span>
        <strong>
          #{{ contribution.rank }} ·
          {{ formatRetrievalScore(contribution.rawScore, contribution.rawScoreType) }}
        </strong>
      </div>
    </div>

    <p class="retrieval-content-preview">{{ visibleContent }}</p>
    <button
      v-if="hasMoreContent"
      class="content-toggle"
      type="button"
      @click="expanded = !expanded"
    >
      {{ expanded ? 'Collapse' : 'Expand' }}
    </button>
  </article>
</template>
