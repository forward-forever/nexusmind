<script setup lang="ts">
import type { AgentRunView, AgentToolTrace } from '@/types/agent'

defineProps<{ run: AgentRunView }>()

function safeArgument(tool: AgentToolTrace): string | null {
  const value =
    tool.toolName === 'search_knowledge_base'
      ? tool.arguments.query
      : tool.toolName === 'get_document_context'
        ? tool.arguments.sourceId
        : null
  return typeof value === 'string' ? value : null
}

function shortId(value: string | null): string {
  return value ? `${value.slice(0, 8)}…` : 'pending'
}

function traceSummary(run: AgentRunView): string {
  const tools = run.toolCallCount ?? run.tools.length
  const turns = run.modelTurnCount === null ? '…' : run.modelTurnCount
  const duration = run.durationMs === null ? 'running' : `${run.durationMs} ms`
  return `Agent Trace · ${tools} tools · ${turns} turns · ${duration}`
}
</script>

<template>
  <details
    class="agent-trace"
    :open="run.status === 'streaming' || run.status === 'error'"
  >
    <summary class="agent-trace-heading">
      <strong>{{ traceSummary(run) }}</strong>
      <span>Session {{ shortId(run.sessionId) }} · Run {{ shortId(run.runId) }}</span>
    </summary>

    <div v-if="run.tools.length" class="agent-tool-list">
      <article
        v-for="tool in run.tools"
        :key="tool.invocationId"
        class="agent-tool-item"
        :data-status="tool.status"
      >
        <span class="agent-tool-status" aria-hidden="true">
          {{ tool.status === 'running' ? '…' : tool.status === 'success' ? '✓' : '✕' }}
        </span>
        <div>
          <strong>{{ tool.toolName }}</strong>
          <p v-if="safeArgument(tool)" class="agent-tool-argument">{{ safeArgument(tool) }}</p>
          <p v-if="tool.status === 'running'">Running…</p>
          <p v-else-if="tool.status === 'success'">
            {{ tool.resultCount }} results · {{ tool.durationMs }} ms
          </p>
          <p v-else class="agent-tool-error">{{ tool.code }} · {{ tool.message }}</p>
          <div v-if="tool.sources.length" class="agent-tool-sources">
            <span v-for="source in tool.sources" :key="source.sourceId">
              {{ source.sourceId }}<template v-if="source.pageNo !== null"> · Page {{ source.pageNo }}</template>
            </span>
          </div>
        </div>
      </article>
    </div>
    <p v-else class="agent-no-tools">No tool calls</p>

    <footer v-if="run.toolCallCount !== null" class="agent-run-metrics">
      {{ run.toolCallCount }} tools · {{ run.modelTurnCount }} model turns · {{ run.durationMs }} ms
    </footer>
    <p v-if="run.error" class="inline-error">{{ run.error }}</p>
  </details>
</template>
