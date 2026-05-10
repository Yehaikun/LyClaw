import { ref, onUnmounted } from 'vue'
import type { SSEChatEvent, ConnectionState } from '@/types'

interface SSEOptions {
  url: string
  onEvent?: (event: SSEChatEvent) => void
  onError?: (error: Error) => void
  onConnectionChange?: (state: ConnectionState) => void
  maxRetries?: number
  retryDelay?: number
}

export function useSSE(options: SSEOptions) {
  const {
    url,
    onEvent,
    onError,
    onConnectionChange,
    maxRetries = 5,
    retryDelay = 2000,
  } = options

  const connectionState = ref<ConnectionState>('disconnected')
  const error = ref<Error | null>(null)

  let abortController: AbortController | null = null
  let retryCount = 0
  let retryTimer: ReturnType<typeof setTimeout> | null = null

  function setState(state: ConnectionState) {
    connectionState.value = state
    onConnectionChange?.(state)
  }

  function parseSSEChunk(
    buffer: string,
    currentEventType: string,
  ): { eventType: string; events: SSEChatEvent[]; remaining: string } {
    const events: SSEChatEvent[] = []
    const lines = buffer.split('\n')
    let newEventType = currentEventType
    let dataBuffer = ''

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i]

      if (line.startsWith('event:')) {
        // Flush any pending data before switching event type
        if (dataBuffer) {
          const evt = createEvent(newEventType, dataBuffer)
          if (evt) events.push(evt)
          dataBuffer = ''
        }
        newEventType = line.slice(6).trim()
        continue
      }

      if (line.startsWith('data:')) {
        const chunk = line.slice(5).trim()
        if (!chunk || chunk === '[DONE]') {
          if (chunk === '[DONE]') {
            events.push({ type: 'done' })
          }
          continue
        }
        dataBuffer += chunk
      } else if (line.trim() === '') {
        // Empty line marks end of event - flush data
        if (dataBuffer) {
          const evt = createEvent(newEventType, dataBuffer)
          if (evt) events.push(evt)
          dataBuffer = ''
        }
      }
      // Other lines ignored
    }

    return { eventType: newEventType, events, remaining: dataBuffer }
  }

  function createEvent(eventType: string, data: string): SSEChatEvent | null {
    if (eventType === 'tool_call') {
      try {
        const toolEvent = JSON.parse(data)
        if (toolEvent.type === 'tool_call') {
          return {
            type: 'tool_call',
            data: {
              type: 'tool_call',
              name: toolEvent.name,
              status: toolEvent.status,
              result: toolEvent.result,
              arguments: toolEvent.arguments,
            },
          }
        }
      } catch {
        // If JSON parsing fails, treat as message text
        return { type: 'message', data }
      }
    } else if (eventType === 'message') {
      return { type: 'message', data }
    } else if (eventType === 'error') {
      return { type: 'error', data }
    }
    return null
  }

  function decodeUnicodeEscapes(raw: string): string {
    if (!raw) return ''
    return raw.replace(/\\u([0-9a-fA-F]{4})/g, (_, hex) =>
      String.fromCharCode(parseInt(hex, 16)),
    )
  }

  async function connect(requestBody: unknown): Promise<void> {
    if (connectionState.value === 'connecting' || connectionState.value === 'connected') {
      return
    }

    setState('connecting')
    error.value = null

    abortController = new AbortController()

    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(requestBody),
        signal: abortController.signal,
      })

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`)
      }

      if (!response.body) {
        throw new Error('Response body is null')
      }

      setState('connected')
      retryCount = 0

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let eventType = 'message'

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        const rawChunk = decoder.decode(value, { stream: true })
        buffer += rawChunk

        const result = parseSSEChunk(buffer, eventType)
        eventType = result.eventType

        for (const evt of result.events) {
          if (evt.type === 'message') {
            evt.data = decodeUnicodeEscapes(evt.data)
          }
          onEvent?.(evt)
        }

        // Keep remaining unprocessed data in buffer
        buffer = result.remaining
      }

      // Process any remaining data
      if (buffer) {
        const evt = createEvent(eventType, buffer)
        if (evt) {
          if (evt.type === 'message') {
            evt.data = decodeUnicodeEscapes(evt.data)
          }
          onEvent?.(evt)
        }
      }

      // Always emit done when stream ends, in case server didn't send [DONE]
      onEvent?.({ type: 'done' })

      setState('disconnected')
    } catch (err) {
      if ((err as Error).name === 'AbortError') {
        setState('disconnected')
        return
      }

      const e = err instanceof Error ? err : new Error(String(err))
      error.value = e
      onError?.(e)

      // Auto-reconnect with exponential backoff
      if (retryCount < maxRetries) {
        const delay = retryDelay * Math.pow(2, retryCount)
        setState('reconnecting')
        retryCount++

        retryTimer = setTimeout(() => {
          connect(requestBody)
        }, delay)
      } else {
        setState('disconnected')
      }
    }
  }

  function disconnect(): void {
    if (retryTimer) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
    retryCount = maxRetries // Prevent auto-reconnect
    abortController?.abort()
    abortController = null
    setState('disconnected')
  }

  function resetRetries(): void {
    retryCount = 0
    if (retryTimer) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
  }

  onUnmounted(() => {
    disconnect()
  })

  return {
    connectionState,
    error,
    connect,
    disconnect,
    resetRetries,
  }
}
