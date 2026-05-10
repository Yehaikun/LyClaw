import { ref, computed, onUnmounted } from 'vue'
import { useSSE } from './useSSE'
import type { Message, ToolCallEvent, SSEChatEvent } from '@/types'

interface UseChatOptions {
  apiEndpoint?: string
  onError?: (error: string) => void
}

export function useChat(options: UseChatOptions = {}) {
  const { apiEndpoint = '/api/chat/stream', onError } = options

  const messages = ref<Message[]>([])
  const currentOutput = ref('')
  const isStreaming = ref(false)
  const sessionId = ref(crypto.randomUUID())
  const chatError = ref<string | null>(null)

  let currentToolCalls: Map<string, number> = new Map() // name -> message index

  // Helper to generate unique IDs
  let messageCounter = 0
  function generateId(): string {
    return `msg_${Date.now()}_${messageCounter++}`
  }

  const { connectionState, error: sseError, connect, disconnect } = useSSE({
    url: apiEndpoint,
    onEvent: handleSSEEvent,
    onError: (err) => {
      chatError.value = err.message
      onError?.(err.message)
    },
  })

  function handleSSEEvent(event: SSEChatEvent): void {
    switch (event.type) {
      case 'message':
        currentOutput.value += event.data
        break

      case 'tool_call':
        handleToolCallEvent(event.data)
        break

      case 'error':
        chatError.value = event.data
        onError?.(event.data)
        break

      case 'done':
        finishStreaming()
        break
    }
  }

  function handleToolCallEvent(toolEvent: ToolCallEvent): void {
    const existingIdx = currentToolCalls.get(toolEvent.name)

    if (existingIdx !== undefined) {
      // Update existing tool call message
      const msg = messages.value[existingIdx]
      if (msg) {
        msg.status = toolEvent.status
        if (toolEvent.result) {
          msg.result = toolEvent.result
        }
        msg.createdAt = new Date().toISOString()
      }
    } else {
      // Create new tool call message
      const newMsg: Message = {
        id: generateId(),
        role: 'tool_call',
        content: toolEvent.name,
        name: toolEvent.name,
        status: toolEvent.status,
        result: toolEvent.result,
        arguments: toolEvent.arguments,
        createdAt: new Date().toISOString(),
      }
      messages.value.push(newMsg)
      currentToolCalls.set(toolEvent.name, messages.value.length - 1)
    }
  }

  function finishStreaming(): void {
    if (currentOutput.value) {
      messages.value.push({
        id: generateId(),
        role: 'assistant',
        content: currentOutput.value,
        createdAt: new Date().toISOString(),
      })
      currentOutput.value = ''
    }

    // Finalize any executing tool calls
    for (const msg of messages.value) {
      if (msg.role === 'tool_call' && msg.status === 'executing') {
        msg.status = 'done'
      }
    }

    isStreaming.value = false
    currentToolCalls.clear()
  }

  async function sendMessage(text: string): Promise<void> {
    if (!text.trim() || isStreaming.value) return

    chatError.value = null

    // Add user message
    messages.value.push({
      id: generateId(),
      role: 'user',
      content: text.trim(),
      createdAt: new Date().toISOString(),
    })

    currentOutput.value = ''
    isStreaming.value = true

    await connect({
      sessionId: sessionId.value,
      messages: [{ role: 'user', content: text.trim() }],
      stream: true,
    })
  }

  function stopGeneration(): void {
    disconnect()
    if (currentOutput.value) {
      messages.value.push({
        id: generateId(),
        role: 'assistant',
        content: currentOutput.value,
        createdAt: new Date().toISOString(),
      })
      currentOutput.value = ''
    }
    isStreaming.value = false
  }

  function clearChat(): void {
    stopGeneration()
    messages.value = []
    currentOutput.value = ''
    chatError.value = null
    currentToolCalls.clear()
    sessionId.value = crypto.randomUUID()
  }

  function newSession(): void {
    clearChat()
    sessionId.value = crypto.randomUUID()
  }

  const messageCount = computed(() => messages.value.length)
  const lastMessage = computed(() =>
    messages.value.length > 0 ? messages.value[messages.value.length - 1] : null,
  )

  onUnmounted(() => {
    disconnect()
  })

  return {
    // State
    messages,
    currentOutput,
    isStreaming,
    sessionId,
    chatError,
    connectionState,

    // Computed
    messageCount,
    lastMessage,

    // Actions
    sendMessage,
    stopGeneration,
    clearChat,
    newSession,
  }
}
