import type { Message } from '@/types'

export function mapRawToMessage(raw: Record<string, unknown>): Message {
  return {
    role: (raw.role as Message['role']) || 'user',
    content: (raw.content as string) || '',
    model: raw.model as string | undefined,
    toolCalls: raw.toolCalls as Message['toolCalls'],
    toolCallId: raw.toolCallId as string | undefined,
    usage: raw.usage as Message['usage'],
  }
}
