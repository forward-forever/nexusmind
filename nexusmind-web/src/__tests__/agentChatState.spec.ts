import { describe, expect, it } from 'vitest'
import {
  beginAgentRun,
  cancelAgentRun,
  createInitialAgentChatState,
  reduceAgentStreamEvent,
} from '@/composables/agentChatState'
import type { AgentSource } from '@/types/agent'

const source: AgentSource = {
  sourceId: 'S1',
  chunkId: 101,
  documentId: 10,
  fileName: 'mysql.pdf',
  pageNo: 17,
  sectionTitle: 'Read View',
}

describe('agentChatState', () => {
  it('accumulates deltas and completes with metrics and source cards', () => {
    let state = beginAgentRun(createInitialAgentChatState(), '解释 MVCC')
    state = reduceAgentStreamEvent(state, baseEvent('assistant_delta', { content: 'MV' }))
    state = reduceAgentStreamEvent(state, baseEvent('assistant_delta', { content: 'CC' }))
    state = reduceAgentStreamEvent(
      state,
      baseEvent('done', {
        durationMs: 7200,
        sources: [source],
        toolCallCount: 2,
        modelTurnCount: 3,
      }),
    )

    expect(state.sessionId).toBe('session-1')
    expect(state.status).toBe('done')
    expect(state.messages[1]).toMatchObject({ content: 'MVCC', status: 'done' })
    expect(state.messages[1]?.run).toMatchObject({
      sources: [source],
      toolCallCount: 2,
      modelTurnCount: 3,
      durationMs: 7200,
    })
  })

  it('creates and updates a matching tool trace lifecycle', () => {
    let state = beginAgentRun(createInitialAgentChatState(), 'search')
    state = reduceAgentStreamEvent(
      state,
      baseEvent('tool_start', {
        invocationId: 'call-1',
        toolName: 'search_knowledge_base',
        arguments: { query: 'MVCC' },
      }),
    )
    expect(state.messages[1]?.run?.tools[0]).toMatchObject({
      invocationId: 'call-1',
      status: 'running',
    })

    state = reduceAgentStreamEvent(
      state,
      baseEvent('tool_result', {
        invocationId: 'call-1',
        toolName: 'search_knowledge_base',
        durationMs: 492,
        resultCount: 1,
        sources: [source],
      }),
    )
    expect(state.messages[1]?.run?.tools[0]).toMatchObject({
      status: 'success',
      durationMs: 492,
      resultCount: 1,
      sources: [source],
    })
  })

  it('records tool errors and terminal run errors without done', () => {
    let state = beginAgentRun(createInitialAgentChatState(), 'context')
    state = reduceAgentStreamEvent(
      state,
      baseEvent('tool_start', {
        invocationId: 'call-2',
        toolName: 'get_document_context',
        arguments: { sourceId: 'S1' },
      }),
    )
    state = reduceAgentStreamEvent(
      state,
      baseEvent('tool_error', {
        invocationId: 'call-2',
        toolName: 'get_document_context',
        code: 'TOOL_ERROR',
        message: 'Context unavailable',
      }),
    )
    state = reduceAgentStreamEvent(
      state,
      baseEvent('error', { code: 'AGENT_TOOL_ERROR', message: 'Agent failed' }),
    )

    expect(state.status).toBe('error')
    expect(state.messages[1]?.run?.tools[0]).toMatchObject({ status: 'error', code: 'TOOL_ERROR' })
    expect(state.messages[1]?.run?.error).toContain('AGENT_TOOL_ERROR')
  })

  it('marks an active run cancelled', () => {
    const state = cancelAgentRun(beginAgentRun(createInitialAgentChatState(), 'stop'))
    expect(state.status).toBe('cancelled')
    expect(state.messages[1]).toMatchObject({ status: 'cancelled' })
  })

  it('shows an agent session busy error without special retry behavior', () => {
    let state = beginAgentRun(createInitialAgentChatState(), 'continue')
    state = reduceAgentStreamEvent(
      state,
      baseEvent('error', {
        code: 'AGENT_SESSION_BUSY',
        message: '当前会话已有 Agent 请求正在执行，请稍后重试',
      }),
    )

    expect(state.status).toBe('error')
    expect(state.messages[1]?.run?.error).toContain('AGENT_SESSION_BUSY')
  })
})

function baseEvent<T extends string, P extends object>(type: T, payload: P) {
  return { type, runId: 'run-1', sessionId: 'session-1', ...payload } as never
}
