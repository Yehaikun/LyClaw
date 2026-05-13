import { post, del, postSSE } from './client'
import type { ChatRequest, ChatResult, Session } from '../types'

export function postChatStream(
  req: ChatRequest,
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (err: Error) => void,
): Promise<void> {
  return postSSE('/api/chat/stream', req, onChunk, onDone, onError)
}

export function postChat(req: ChatRequest): Promise<ChatResult> {
  return post<ChatResult>('/api/chat', req)
}

export function createSession(req?: ChatRequest): Promise<Session> {
  return post<Session>('/api/sessions', req || undefined)
}

export function deleteSession(
  sessionId: string,
): Promise<{ deleted: boolean; sessionId: string }> {
  return del<{ deleted: boolean; sessionId: string }>(
    `/api/sessions/${sessionId}`,
  )
}
