import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Message } from '@/types'

export const useChatStore = defineStore('chat', () => {
  // State
  const messages = ref<Message[]>([])
  const currentOutput = ref('')
  const isStreaming = ref(false)
  const error = ref<string | null>(null)
  const _messageCounter = ref(0)

  // Computed
  const messageCount = computed(() => messages.value.length)

  const lastUserMessage = computed(() => {
    for (let i = messages.value.length - 1; i >= 0; i--) {
      if (messages.value[i].role === 'user') return messages.value[i]
    }
    return null
  })

  const lastAssistantMessage = computed(() => {
    for (let i = messages.value.length - 1; i >= 0; i--) {
      if (messages.value[i].role === 'assistant') return messages.value[i]
    }
    return null
  })

  const toolCallMessages = computed(() =>
    messages.value.filter((m) => m.role === 'tool_call'),
  )

  // Actions
  function generateId(): string {
    return `msg_${Date.now()}_${_messageCounter.value++}`
  }

  function addMessage(msg: Omit<Message, 'id' | 'createdAt'> & Partial<Pick<Message, 'id' | 'createdAt'>>): Message {
    const fullMsg: Message = {
      ...msg,
      id: msg.id || generateId(),
      createdAt: msg.createdAt || new Date().toISOString(),
    } as Message
    messages.value.push(fullMsg)
    return fullMsg
  }

  function updateMessage(id: string, updates: Partial<Message>): void {
    const idx = messages.value.findIndex((m) => m.id === id)
    if (idx !== -1) {
      messages.value[idx] = { ...messages.value[idx], ...updates }
    }
  }

  function appendToCurrentOutput(text: string): void {
    currentOutput.value += text
  }

  function finalizeAssistantMessage(): void {
    if (currentOutput.value) {
      addMessage({
        role: 'assistant',
        content: currentOutput.value,
      })
      currentOutput.value = ''
    }
    isStreaming.value = false
  }

  function clearChat(): void {
    messages.value = []
    currentOutput.value = ''
    error.value = null
    isStreaming.value = false
  }

  function setError(err: string | null): void {
    error.value = err
  }

  function setStreaming(value: boolean): void {
    isStreaming.value = value
  }

  function removeMessage(id: string): void {
    const idx = messages.value.findIndex((m) => m.id === id)
    if (idx !== -1) {
      messages.value.splice(idx, 1)
    }
  }

  return {
    // State
    messages,
    currentOutput,
    isStreaming,
    error,

    // Computed
    messageCount,
    lastUserMessage,
    lastAssistantMessage,
    toolCallMessages,

    // Actions
    addMessage,
    updateMessage,
    appendToCurrentOutput,
    finalizeAssistantMessage,
    clearChat,
    setError,
    setStreaming,
    removeMessage,
  }
})
