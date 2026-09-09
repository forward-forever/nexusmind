import type { RagStreamEvent, RagStreamState } from '@/types/rag'

export function createInitialRagStreamState(): RagStreamState {
  return {
    status: 'idle',
    answer: '',
    sources: [],
    model: null,
    elapsedMs: null,
    error: null,
  }
}

export function reduceRagStreamEvent(state: RagStreamState, event: RagStreamEvent): RagStreamState {
  if (state.status === 'error' || state.status === 'cancelled' || state.status === 'done') {
    return state
  }

  switch (event.type) {
    case 'sources':
      return { ...state, status: 'streaming', sources: event.sources }
    case 'delta':
      return { ...state, status: 'streaming', answer: state.answer + event.content }
    case 'done':
      return {
        ...state,
        status: 'done',
        model: event.model,
        elapsedMs: event.elapsedMs,
      }
    case 'error':
      return { ...state, status: 'error', error: event.message }
  }
}

export function cancelRagStream(state: RagStreamState): RagStreamState {
  if (state.status !== 'connecting' && state.status !== 'streaming') return state
  return { ...state, status: 'cancelled', error: null }
}
