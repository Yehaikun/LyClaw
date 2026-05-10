export interface Message {
  id: string
  role: 'user' | 'assistant' | 'system' | 'tool_call'
  content: string
  name?: string
  status?: 'executing' | 'done' | 'error'
  result?: string
  arguments?: Record<string, unknown>
  createdAt: string
}

export interface ToolCallEvent {
  type: 'tool_call'
  name: string
  status: 'executing' | 'done' | 'error'
  result?: string
  arguments?: Record<string, unknown>
}

export interface ChatRequest {
  sessionId: string
  messages: { role: string; content: string }[]
  stream: boolean
  toolChoice?: string
}

export type SSEChatEventType = 'message' | 'tool_call' | 'error' | 'done'

export interface SSEMessageEvent {
  type: 'message'
  data: string
}

export interface SSEToolCallEvent {
  type: 'tool_call'
  data: ToolCallEvent
}

export interface SSEErrorEvent {
  type: 'error'
  data: string
}

export interface SSEDoneEvent {
  type: 'done'
}

export type SSEChatEvent = SSEMessageEvent | SSEToolCallEvent | SSEErrorEvent | SSEDoneEvent

export interface StreamMessage {
  content: string
  toolCalls: ToolCallEvent[]
  isStreaming: boolean
}
