<script setup lang="ts">
import { ref } from 'vue'
import type { CreateKnowledgeBaseRequest, KnowledgeBase } from '@/types/knowledge'

defineProps<{
  knowledgeBases: KnowledgeBase[]
  selectedId: number | null
  loading: boolean
  creating: boolean
}>()

const emit = defineEmits<{
  select: [id: number]
  create: [request: CreateKnowledgeBaseRequest]
}>()

const formOpen = ref(false)
const name = ref('')
const description = ref('')

function submit(): void {
  const normalizedName = name.value.trim()
  if (!normalizedName) return
  emit('create', { name: normalizedName, description: description.value.trim() })
  name.value = ''
  description.value = ''
  formOpen.value = false
}
</script>

<template>
  <aside class="knowledge-panel">
    <div class="panel-heading">
      <div>
        <p class="eyebrow">Workspace</p>
        <h2>Knowledge Bases</h2>
      </div>
      <button
        class="icon-button"
        type="button"
        aria-label="Create knowledge base"
        @click="formOpen = !formOpen"
      >
        +
      </button>
    </div>

    <form v-if="formOpen" class="create-form" @submit.prevent="submit">
      <label>
        Name
        <input v-model="name" maxlength="128" required placeholder="My knowledge base" />
      </label>
      <label>
        Description
        <textarea
          v-model="description"
          maxlength="1024"
          rows="3"
          placeholder="What belongs here?"
        />
      </label>
      <div class="form-actions">
        <button class="button secondary" type="button" @click="formOpen = false">Cancel</button>
        <button class="button primary" type="submit" :disabled="creating || !name.trim()">
          {{ creating ? 'Creating…' : 'Create' }}
        </button>
      </div>
    </form>

    <p v-if="loading" class="muted-state">Loading knowledge bases…</p>
    <p v-else-if="knowledgeBases.length === 0" class="muted-state">
      暂无知识库。创建一个开始 V1 Demo。
    </p>
    <nav v-else class="knowledge-list" aria-label="Knowledge bases">
      <button
        v-for="knowledgeBase in knowledgeBases"
        :key="knowledgeBase.id"
        class="knowledge-item"
        :class="{ selected: knowledgeBase.id === selectedId }"
        type="button"
        @click="emit('select', knowledgeBase.id)"
      >
        <span class="knowledge-name">{{ knowledgeBase.name }}</span>
        <span class="knowledge-description">{{
          knowledgeBase.description || 'No description'
        }}</span>
        <span class="knowledge-model">
          {{ knowledgeBase.embeddingModel }} · {{ knowledgeBase.embeddingDimension }}d
        </span>
      </button>
    </nav>
  </aside>
</template>
