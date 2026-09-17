import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import SafeMarkdown from '@/components/SafeMarkdown.vue'

describe('SafeMarkdown', () => {
  it('renders the supported Markdown subset', () => {
    const wrapper = mount(SafeMarkdown, {
      props: {
        content: '# Heading\n\n**bold** and *italic* with `code`\n\n1. first\n2. second',
        sourceIds: [],
      },
    })

    expect(wrapper.find('h1').text()).toBe('Heading')
    expect(wrapper.find('strong').text()).toBe('bold')
    expect(wrapper.find('em').text()).toBe('italic')
    expect(wrapper.find('code').text()).toBe('code')
    expect(wrapper.findAll('ol li')).toHaveLength(2)
  })

  it('disables raw HTML and sanitizes dangerous links', () => {
    const wrapper = mount(SafeMarkdown, {
      props: {
        content:
          '<script>window.pwned = true</script>\n\n<img src=x onerror="window.pwned=true">\n\n[bad](javascript:alert(1))',
        sourceIds: [],
      },
    })

    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('a[href^="javascript:"]').exists()).toBe(false)
  })

  it('keeps valid citations clickable outside model-generated HTML', async () => {
    const wrapper = mount(SafeMarkdown, {
      props: { content: '**Read View** [S1] and [S99]', sourceIds: ['S1'] },
    })

    expect(wrapper.find('strong').text()).toBe('Read View')
    expect(wrapper.findAll('button.citation-link')).toHaveLength(1)
    expect(wrapper.find('.citation-invalid').text()).toBe('[S99]')
    await wrapper.get('button.citation-link').trigger('click')
    expect(wrapper.emitted('citation')).toEqual([['S1']])
  })
})
