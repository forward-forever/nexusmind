import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'

interface MarkdownEnvironment {
  sourceIds: ReadonlySet<string>
}

const citationPattern = /\[S\d+\]/g
const markdown = new MarkdownIt({
  html: false,
  linkify: false,
  breaks: true,
  typographer: false,
})

markdown.renderer.rules.text = (tokens, index, _options, environment) => {
  const content = tokens[index]?.content ?? ''
  const sourceIds = (environment as unknown as MarkdownEnvironment | undefined)?.sourceIds ?? new Set()
  let cursor = 0
  let rendered = ''

  for (const match of content.matchAll(citationPattern)) {
    const matchIndex = match.index
    rendered += markdown.utils.escapeHtml(content.slice(cursor, matchIndex))
    const citation = match[0]
    const sourceId = citation.slice(1, -1)
    rendered += sourceIds.has(sourceId)
      ? `<button type="button" class="citation-link" data-source-id="${sourceId}" aria-label="View source ${sourceId}">${citation}</button>`
      : `<span class="citation-invalid" title="${sourceId} is not in returned sources">${citation}</span>`
    cursor = matchIndex + citation.length
  }

  return rendered + markdown.utils.escapeHtml(content.slice(cursor))
}

export function renderSafeMarkdown(content: string, sourceIds: ReadonlySet<string>): string {
  const rendered = markdown.render(content, { sourceIds } satisfies MarkdownEnvironment)
  return DOMPurify.sanitize(rendered, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style'],
    FORBID_ATTR: ['style'],
  })
}
