import { describe, expect, it } from 'vitest'
import { formatRerankMovement, formatRetrievalScore } from '@/utils/retrieval'

describe('retrieval display formatting', () => {
  it.each([
    ['COSINE', 0.43831, 'COSINE 0.4383'],
    ['BM25', 22.52414, 'BM25 22.5241'],
    ['RRF', 0.03281, 'RRF 0.0328'],
    ['RERANK', 0.99224, 'RERANK 0.9922'],
  ] as const)('formats %s without calling every score similarity', (scoreType, score, expected) => {
    expect(formatRetrievalScore(score, scoreType)).toBe(expected)
  })

  it.each([
    [5, 2, '↑ 3'],
    [2, 5, '↓ 3'],
    [1, 1, '—'],
  ])('formats pre-rerank rank %i to final rank %i', (preRank, finalRank, expected) => {
    expect(formatRerankMovement(preRank, finalRank)).toBe(expected)
  })
})
