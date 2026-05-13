/**
 * 聊天与会话API封装，提供与后端LyClaw聊天服务的所有交互接口。
 *
 * 本模块是前端聊天功能的核心API层，封装了三种聊天交互模式：
 * 1. 流式聊天（postChatStream）：通过SSE协议接收LLM逐token输出，实时渲染到界面
 * 2. 非流式聊天（postChat）：一次性请求-响应模式，用于流式失败时的降级回退
 * 3. 会话管理（createSession/deleteSession）：创建和删除聊天会话
 *
 * 设计考虑：
 * - 流式和非流式共享相同的ChatRequest数据结构，仅stream字段不同
 * - 会话由后端生成唯一sessionId，前端通过此ID关联所有后续消息
 * - 所有函数均返回Promise，调用方通过async/await或.then()处理异步结果
 */
import { post, del, postSSE } from './client'
import type { ChatRequest, ChatResult, Session } from '../types'

/**
 * 发起流式聊天请求，通过SSE协议实时接收LLM生成的文本块。
 *
 * 这是前端聊天功能的主要通信方式。函数内部调用postSSE建立长连接，
 * 服务端通过事件流逐步推送生成的token，前端逐个渲染到消息气泡中。
 * 回调函数设计允许调用方在三个关键生命周期节点做出响应：
 * - onChunk：每收到一个文本片段时触发，用于更新流式显示的文本
 * - onDone：流正常结束时触发，用于将临时消息固化为正式消息
 * - onError：发生错误时触发，可能包含已部分接收的文本
 *
 * @param req 聊天请求对象，包含sessionId、消息历史和stream标志
 * @param onChunk 接收到文本块时的回调
 * @param onDone 流式输出完成时的回调
 * @param onError 发生错误时的回调
 */
export function postChatStream(
  req: ChatRequest,
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (err: Error) => void,
): Promise<void> {
  return postSSE('/api/chat/stream', req, onChunk, onDone, onError)
}

/**
 * 发起非流式聊天请求，一次性获取LLM的完整回复。
 *
 * 作为流式聊天的降级回退方案，当流式连接失败（如网络不支持SSE或超时）时，
 * 聊天Store会自动调用此函数获取完整响应。响应包含完整的content、
 * finishReason（结束原因）、tokenUsage（token消耗统计）和
 * toolResults（工具调用结果列表）。
 *
 * @param req 聊天请求对象，stream字段应为false
 * @returns ChatResult包含完整回复内容和元数据
 */
export function postChat(req: ChatRequest): Promise<ChatResult> {
  return post<ChatResult>('/api/chat', req)
}

/**
 * 创建新的聊天会话。
 *
 * 会话是消息的容器，每个会话拥有唯一的sessionId。创建会话时可
 * 选择性地传入初始ChatRequest来设置会话的初始上下文。
 * 返回的Session对象包含sessionId、name、创建时间等元数据。
 *
 * @param req 可选的初始聊天请求，用于设置会话初始状态
 * @returns 新创建的Session对象
 */
export function createSession(req?: ChatRequest): Promise<Session> {
  return post<Session>('/api/sessions', req || undefined)
}

/**
 * 删除指定ID的聊天会话及其所有关联消息。
 *
 * 删除操作不可逆，会同时清除服务端存储的会话数据和消息历史。
 * 成功删除后返回包含deleted确认标志和sessionId的确认对象。
 *
 * @param sessionId 要删除的会话唯一标识
 * @returns 包含deleted标志和sessionId的确认对象
 */
export function deleteSession(
  sessionId: string,
): Promise<{ deleted: boolean; sessionId: string }> {
  return del<{ deleted: boolean; sessionId: string }>(
    `/api/sessions/${sessionId}`,
  )
}
