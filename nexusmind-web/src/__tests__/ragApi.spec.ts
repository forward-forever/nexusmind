import { afterEach, describe, expect, it, vi } from 'vitest'
import { streamRagAnswer } from '@/api/rag'
import type { RagStreamEvent } from '@/types/rag'

afterEach(() => vi.unstubAllGlobals())

describe('streamRagAnswer', () => {
  it('parses SSE events split across arbitrary network chunks', async () => {
    const payload = [
      'data:{"type":"sources","sources":[{"id":"S1","chunkId":101,"documentId":10,',
      '"fileName":"mysql.pdf","pageNo":17,"sectionTitle":"Deadlocks","score":0.8231,"scoreType":"COSINE"}]}\n\n',
      'data:{"type":"delta","content":"MV"}\n\n',
      'data:{"type":"delta","content":"CC"}\n\n',
      'data:{"type":"done","model":"qwen3.5-flash","elapsedMs":1320}\n\n',
    ]
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          new ReadableStream({
            start(controller) {
              const encoder = new TextEncoder()
              for (const chunk of payload) controller.enqueue(encoder.encode(chunk))
              controller.close()
            },
          }),
          { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
        ),
      ),
    )
    const events: RagStreamEvent[] = []

    await streamRagAnswer({
      knowledgeBaseId: 7,
      request: { question: 'What is MVCC?', topK: 5 },
      signal: new AbortController().signal,
      onEvent: (event) => events.push(event),
    })

    expect(events.map((event) => event.type)).toEqual(['sources', 'delta', 'delta', 'done'])
    expect(events[0]).toMatchObject({
      type: 'sources',
      sources: [{ id: 'S1', pageNo: 17, scoreType: 'COSINE' }],
    })
  })

  it('preserves a pre-stream backend JSON error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            status: 409,
            code: 'KNOWLEDGE_BASE_INACTIVE',
            message: 'Knowledge base is disabled',
          }),
          { status: 409, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const promise = streamRagAnswer({
      knowledgeBaseId: 7,
      request: { question: 'Question', topK: 5 },
      signal: new AbortController().signal,
      onEvent: () => undefined,
    })

    await expect(promise).rejects.toMatchObject({
      status: 409,
      code: 'KNOWLEDGE_BASE_INACTIVE',
      message: 'Knowledge base is disabled',
    })
  })
})
