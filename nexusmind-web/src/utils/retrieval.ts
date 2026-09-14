import type { RetrievalScoreType } from '@/types/retrieval'

export function formatRetrievalScore(score: number, scoreType: RetrievalScoreType): string {
  return `${scoreType} ${score.toFixed(4)}`
}

export function formatRerankMovement(preRerankRank: number, finalRank: number): string {
  const movement = preRerankRank - finalRank
  if (movement > 0) return `↑ ${movement}`
  if (movement < 0) return `↓ ${Math.abs(movement)}`
  return '—'
}
