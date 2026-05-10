export interface Session {
  id: string
  sessionId: string
  name?: string
  model?: string
  messages?: Array<{ role: string; content: string }>
  createdAt: string
  updatedAt: string
}

/** UI-friendly computed fields */
export function sessionDisplay(s: Session) {
  const lastMsg = s.messages?.length
    ? s.messages[s.messages.length - 1]
    : null
  return {
    title: s.name || '未命名对话',
    lastMessage: lastMsg?.content || '暂无消息',
    messageCount: s.messages?.length ?? 0,
  }
}

export interface SessionCreateResponse {
  sessionId: string
}
