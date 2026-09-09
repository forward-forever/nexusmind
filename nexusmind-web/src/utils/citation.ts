export type CitationSegment =
  | { type: 'text'; value: string }
  | { type: 'citation'; value: string; sourceId: string; valid: boolean }

const CITATION_PATTERN = /\[S\d+\]/g

export function parseCitations(
  answer: string,
  availableSourceIds: ReadonlySet<string>,
): CitationSegment[] {
  const segments: CitationSegment[] = []
  let cursor = 0

  for (const match of answer.matchAll(CITATION_PATTERN)) {
    const index = match.index
    if (index > cursor) segments.push({ type: 'text', value: answer.slice(cursor, index) })

    const value = match[0]
    const sourceId = value.slice(1, -1)
    segments.push({
      type: 'citation',
      value,
      sourceId,
      valid: availableSourceIds.has(sourceId),
    })
    cursor = index + value.length
  }

  if (cursor < answer.length) segments.push({ type: 'text', value: answer.slice(cursor) })
  if (segments.length === 0 && answer.length > 0) return [{ type: 'text', value: answer }]
  return segments
}
