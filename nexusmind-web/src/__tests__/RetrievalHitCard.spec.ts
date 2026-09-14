import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import RetrievalHitCard from '@/components/RetrievalHitCard.vue'
import type { RetrievalHitResponse } from '@/types/retrieval'

describe('RetrievalHitCard', () => {
  it('renders hybrid route contributions without recomputing their scores', () => {
    const wrapper = mount(RetrievalHitCard, { props: { hit: hybridHit(), rank: 1 } })

    expect(wrapper.text()).toContain('RRF 0.0328')
    expect(wrapper.text()).toContain('DENSE')
    expect(wrapper.text()).toContain('#2 · COSINE 0.7100')
    expect(wrapper.text()).toContain('BM25')
    expect(wrapper.text()).toContain('#1 · BM25 15.4200')
  })

  it('renders rerank provenance and ranking movement', () => {
    const hit: RetrievalHitResponse = {
      ...hybridHit(),
      score: 0.9922,
      scoreType: 'RERANK',
      rerank: { preRerankRank: 3, preRerankScore: 0.0308, preRerankScoreType: 'RRF' },
    }
    const wrapper = mount(RetrievalHitCard, { props: { hit, rank: 1 } })

    expect(wrapper.text()).toContain('RERANK 0.9922')
    expect(wrapper.text()).toContain('↑ 2')
    expect(wrapper.text()).toContain('#3 · RRF 0.0308')
  })

  it('limits long content until the user expands it', async () => {
    const content = 'a'.repeat(320)
    const wrapper = mount(RetrievalHitCard, {
      props: { hit: { ...hybridHit(), content }, rank: 1 },
    })

    expect(wrapper.find('.retrieval-content-preview').text()).toHaveLength(261)
    await wrapper.get('.content-toggle').trigger('click')
    expect(wrapper.find('.retrieval-content-preview').text()).toHaveLength(320)
    expect(wrapper.text()).toContain('Collapse')
  })
})

function hybridHit(): RetrievalHitResponse {
  return {
    chunkId: 17389,
    documentId: 25,
    fileName: 'nexusmind-retrieval-benchmark-v1.pdf',
    chunkIndex: 3,
    score: 0.0328,
    scoreType: 'RRF',
    content: 'InnoDB detects deadlocks with a wait-for graph.',
    pageNo: 4,
    sectionTitle: null,
    contributions: [
      { retrieverType: 'DENSE', rank: 2, rawScore: 0.71, rawScoreType: 'COSINE' },
      { retrieverType: 'BM25', rank: 1, rawScore: 15.42, rawScoreType: 'BM25' },
    ],
    rerank: null,
  }
}
