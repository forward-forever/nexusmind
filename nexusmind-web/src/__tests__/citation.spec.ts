import { describe, expect, it } from 'vitest'
import { parseCitations } from '@/utils/citation'

describe('parseCitations', () => {
  const available = new Set(['S1', 'S2', 'S3'])

  it('keeps an answer without citations as text', () => {
    expect(parseCitations('No citation here.', available)).toEqual([
      { type: 'text', value: 'No citation here.' },
    ])
  })

  it('parses one known citation', () => {
    expect(parseCitations('Deadlocks are detected [S1].', available)).toEqual([
      { type: 'text', value: 'Deadlocks are detected ' },
      { type: 'citation', value: '[S1]', sourceId: 'S1', valid: true },
      { type: 'text', value: '.' },
    ])
  })

  it('parses multiple citations among text', () => {
    const segments = parseCitations('First [S1], then second [S3].', available)
    expect(segments.filter((segment) => segment.type === 'citation')).toEqual([
      { type: 'citation', value: '[S1]', sourceId: 'S1', valid: true },
      { type: 'citation', value: '[S3]', sourceId: 'S3', valid: true },
    ])
  })

  it('parses adjacent citations independently', () => {
    expect(parseCitations('[S1][S2]', available)).toEqual([
      { type: 'citation', value: '[S1]', sourceId: 'S1', valid: true },
      { type: 'citation', value: '[S2]', sourceId: 'S2', valid: true },
    ])
  })

  it('marks an unknown citation without inventing a source', () => {
    expect(parseCitations('Unsupported [S99]', available)).toContainEqual({
      type: 'citation',
      value: '[S99]',
      sourceId: 'S99',
      valid: false,
    })
  })

  it('does not parse similar ordinary text', () => {
    expect(parseCitations('S1 [s1] [ S1 ] [Source1]', available)).toEqual([
      { type: 'text', value: 'S1 [s1] [ S1 ] [Source1]' },
    ])
  })
})
