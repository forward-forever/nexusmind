import { shallowRef } from 'vue'
import { streamRagAnswer } from '@/api/rag'
import { errorMessage } from '@/api/http'
import {
  cancelRagStream,
  createInitialRagStreamState,
  reduceRagStreamEvent,
} from './ragStreamState'

export function useRagStream() {
  const state = shallowRef(createInitialRagStreamState())
  let activeController: AbortController | null = null
  let generation = 0

  async function start(knowledgeBaseId: number, question: string, topK: number): Promise<void> {
    generation += 1
    const currentGeneration = generation
    activeController?.abort()
    const controller = new AbortController()
    activeController = controller
    state.value = { ...createInitialRagStreamState(), status: 'connecting' }

    try {
      await streamRagAnswer({
        knowledgeBaseId,
        request: { question, topK },
        signal: controller.signal,
        onEvent(event) {
          if (currentGeneration === generation) {
            state.value = reduceRagStreamEvent(state.value, event)
          }
        },
      })
      if (
        currentGeneration === generation &&
        state.value.status !== 'done' &&
        state.value.status !== 'error' &&
        state.value.status !== 'cancelled'
      ) {
        state.value = { ...state.value, status: 'error', error: '模型响应流意外结束' }
      }
    } catch (error) {
      if (currentGeneration !== generation) return
      if (isAbortError(error)) {
        state.value = cancelRagStream(state.value)
      } else {
        state.value = { ...state.value, status: 'error', error: errorMessage(error) }
      }
    } finally {
      if (currentGeneration === generation) activeController = null
    }
  }

  function stop(): void {
    if (!activeController) return
    activeController.abort()
    activeController = null
    state.value = cancelRagStream(state.value)
  }

  function reset(): void {
    generation += 1
    activeController?.abort()
    activeController = null
    state.value = createInitialRagStreamState()
  }

  return { state, start, stop, reset }
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
