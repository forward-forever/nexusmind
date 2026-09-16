import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AgentChatPanel from '@/components/AgentChatPanel.vue'

describe('AgentChatPanel', () => {
  it('clears the draft when the selected knowledge base changes', async () => {
    const wrapper = mount(AgentChatPanel, {
      props: { knowledgeBaseId: 1, knowledgeBaseName: 'KB 1', ready: true },
    })
    const textarea = wrapper.get('textarea')
    await textarea.setValue('draft for KB 1')
    await wrapper.setProps({ knowledgeBaseId: 2, knowledgeBaseName: 'KB 2' })
    expect((textarea.element as HTMLTextAreaElement).value).toBe('')
    expect(wrapper.text()).toContain('Session: new')
  })

  it('renders source metadata without raw HTML', () => {
    const wrapper = mount(AgentChatPanel, {
      props: { knowledgeBaseId: 1, knowledgeBaseName: 'KB', ready: false },
    })
    expect(wrapper.text()).toContain('Agent Chat')
    expect(wrapper.find('[v-html]').exists()).toBe(false)
  })
})
