import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Message, ChatRequest, ChatResult, ToolCall } from '@/types'
import { postChat, postChatStream, createSession } from '@/api/chat'
import { ApiError } from '@/api/client'

export const useChatStore = defineStore('chat', () => {
  // ---- State ----
  const messages = ref<Message[]>([])
  const currentStreamingText = ref<string>('')
  const isStreaming = ref<boolean>(false)
  const error = ref<string | null>(null)
  const errorTraceId = ref<string | undefined>(undefined)
  const currentModel = ref<string>('deepseek-4-pro')
  const currentProvider = ref<string>('deepseek')
  const currentSessionId = ref<string | null>(null)

  // ---- Getters ----
  const messageCount = computed<number>(() => messages.value.length)

  const lastMessage = computed<Message | null>(
    () => messages.value[messages.value.length - 1] ?? null,
  )

  const lastUserMessage = computed<Message | null>(() => {
    for (let i = messages.value.length - 1; i >= 0; i--) {
      if (messages.value[i].role === 'user') {
        return messages.value[i]
      }
    }
    return null
  })

  // ---- Actions ----

  /**
   * Send a user message to the chat API.
   * Attempts streaming first, falls back to non-streaming on failure.
   */
  async function sendMessage(text: string, sessionId?: string): Promise<void> {
    if (!text.trim()) return

    const userMsg: Message = { role: 'user', content: text }
    messages.value.push(userMsg)
    error.value = null

    // Ensure a session exists
    const targetSessionId = sessionId || currentSessionId.value
    let activeSessionId: string | null = targetSessionId

    if (!activeSessionId) {
      try {
        const session = await createSession()
        activeSessionId = session.sessionId
        currentSessionId.value = activeSessionId
      } catch (err) {
        error.value = `Failed to create session: ${(err as Error).message}`
        errorTraceId.value = undefined
        return
      }
    }

    const request: ChatRequest = {
      sessionId: activeSessionId,
      messages: messages.value.map((m) => ({ role: m.role, content: m.content })),
      stream: true,
    }

    isStreaming.value = true
    currentStreamingText.value = ''

    try {
      await postChatStream(
        request,
        (chunk: string) => {
          currentStreamingText.value += chunk
        },
        () => {
          // Streaming completed successfully
          if (currentStreamingText.value) {
            const assistantMsg: Message = {
              role: 'assistant',
              content: currentStreamingText.value,
              model: currentModel.value,
            }
            messages.value.push(assistantMsg)
          }
          currentStreamingText.value = ''
          isStreaming.value = false
        },
        (err: Error) => {
          // If we accumulated partial streaming text, keep it as the response
          if (currentStreamingText.value) {
            const partialMsg: Message = {
              role: 'assistant',
              content: currentStreamingText.value,
              model: currentModel.value,
            }
            messages.value.push(partialMsg)
          }
          currentStreamingText.value = ''
          isStreaming.value = false
          // Capture traceId from ApiError if present
          if (err instanceof ApiError) {
            errorTraceId.value = (err as ApiError).traceId
          } else {
            errorTraceId.value = undefined
          }
          // Only fallback to non-streaming for non-abort errors
          if (err.name !== 'AbortError' && !(err instanceof ApiError && (err as ApiError).status === 0)) {
            error.value = err.message
            sendMessageNonStreaming(request)
          } else {
            error.value = null  // partial content already saved, don't show error
            errorTraceId.value = undefined
          }
        },
      )
    } catch (err) {
      isStreaming.value = false
      error.value = (err as Error).message
      if (err instanceof ApiError) {
        errorTraceId.value = (err as ApiError).traceId
      } else {
        errorTraceId.value = undefined
      }
    }
  }

  /** Non-streaming fallback for chat. */
  async function sendMessageNonStreaming(request: ChatRequest): Promise<void> {
    try {
      const result: ChatResult = await postChat({
        ...request,
        stream: false,
      })
      const assistantMsg: Message = {
        role: 'assistant',
        content: result.content,
        model: currentModel.value,
        usage: result.tokenUsage
          ? parseTokenUsage(result.tokenUsage)
          : undefined,
        toolCalls: result.toolResults
          ? result.toolResults.map((tr) => ({
              toolCallId: '',
              name: tr.toolName,
              arguments: '',
              result: tr.output,
            }))
          : undefined,
      }
      messages.value.push(assistantMsg)
      isStreaming.value = false
    } catch (fallbackErr) {
      error.value = `Chat failed: ${(fallbackErr as Error).message}`
      if (fallbackErr instanceof ApiError) {
        errorTraceId.value = (fallbackErr as ApiError).traceId
      } else {
        errorTraceId.value = undefined
      }
      isStreaming.value = false
      throw fallbackErr
    }
  }

  /** Stop the current streaming generation. */
  function stopGeneration(): void {
    isStreaming.value = false
  }

  /** Clear all chat messages. */
  function clearChat(): void {
    messages.value = []
    currentStreamingText.value = ''
    error.value = null
    errorTraceId.value = undefined
  }

  /** Retry the last user message. */
  async function retry(): Promise<void> {
    const lastUser = lastUserMessage.value
    if (!lastUser) return

    // Find the index of the last user message
    const userIdx = messages.value.lastIndexOf(lastUser)
    // Remove everything from the last user message onward
    messages.value.splice(userIdx)
    // Resend
    await sendMessage(lastUser.content, currentSessionId.value ?? undefined)
  }

  /** Set the current model and provider. */
  function setModel(model: string, provider?: string): void {
    currentModel.value = model
    if (provider) {
      currentProvider.value = provider
    } else if (model.startsWith('deepseek')) {
      currentProvider.value = 'deepseek'
    } else if (model.startsWith('claude')) {
      currentProvider.value = 'anthropic'
    } else if (model.startsWith('gpt')) {
      currentProvider.value = 'openai'
    }
  }

  /** Update the current session ID. */
  function setSessionId(id: string): void {
    currentSessionId.value = id
  }

  /** Add a tool call result message to the chat. */
  function addToolCallMessage(toolCall: ToolCall): void {
    const msg: Message = {
      role: 'tool',
      content: toolCall.result ?? '',
      toolCallId: toolCall.toolCallId,
    }
    messages.value.push(msg)
  }

  // ---- Internal helpers ----

  function parseTokenUsage(usageStr: string): {
    promptTokens: number
    completionTokens: number
    totalTokens: number
  } {
    try {
      const parsed = JSON.parse(usageStr)
      return {
        promptTokens: parsed.promptTokens ?? parsed.prompt_tokens ?? 0,
        completionTokens:
          parsed.completionTokens ?? parsed.completion_tokens ?? 0,
        totalTokens: parsed.totalTokens ?? parsed.total_tokens ?? 0,
      }
    } catch {
      return { promptTokens: 0, completionTokens: 0, totalTokens: 0 }
    }
  }

  return {
    // State
    messages,
    currentStreamingText,
    isStreaming,
    error,
    errorTraceId,
    currentModel,
    currentProvider,
    currentSessionId,
    // Getters
    messageCount,
    lastMessage,
    lastUserMessage,
    // Actions
    sendMessage,
    stopGeneration,
    clearChat,
    retry,
    setModel,
    setSessionId,
    addToolCallMessage,
  }
})
