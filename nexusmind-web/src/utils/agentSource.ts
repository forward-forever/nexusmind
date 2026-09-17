export interface AgentCitationTarget {
  runId: string
  sourceId: string
}

export function agentSourceKey(runId: string, sourceId: string): string {
  return `${runId}:${sourceId}`
}

export function agentSourceDomId(runId: string, sourceId: string): string {
  return `agent-source-${domSafe(runId)}-${domSafe(sourceId)}`
}

function domSafe(value: string): string {
  return value.replace(/[^A-Za-z0-9_-]/g, '-')
}
