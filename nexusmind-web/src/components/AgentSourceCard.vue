<script setup lang="ts">
import type { AgentSource } from '@/types/agent'
import { agentSourceDomId } from '@/utils/agentSource'

defineProps<{ runId: string; source: AgentSource; highlighted: boolean }>()
</script>

<template>
  <article
    :id="agentSourceDomId(runId, source.sourceId)"
    class="agent-source-card"
    :class="{ highlighted }"
  >
    <div class="source-id">{{ source.sourceId }}</div>
    <div class="source-body">
      <strong>{{ source.fileName }}</strong>
      <div class="source-metadata">
        <span v-if="source.pageNo !== null">Page {{ source.pageNo }}</span>
        <span v-if="source.sectionTitle">{{ source.sectionTitle }}</span>
        <span class="agent-source-debug">Chunk #{{ source.chunkId }}</span>
      </div>
    </div>
  </article>
</template>
