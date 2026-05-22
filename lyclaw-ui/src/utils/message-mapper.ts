import type { Message, ToolCall } from '@/types'

/**
 * 将后端 JSONL 原始行转换为前端 Message 对象。
 * 仅做字段映射，不处理跨消息合并（合并逻辑在 processRawMessages 中）。
 */
export function mapRawToMessage(raw: Record<string, unknown>): Message {
  return {
    role: (raw.role as Message['role']) || 'user',
    content: (raw.content as string) || '',
    model: raw.model as string | undefined,
    toolCalls: raw.toolCalls as Message['toolCalls'],
    toolCallId: raw.toolCallId as string | undefined,
    usage: raw.usage as Message['usage'],
    thinking: raw.thinking as string | undefined,
  }
}

/**
 * 处理从后端加载的原始消息列表：
 * 1. 过滤掉 session_created 条目
 * 2. 将 tool_result 条目的内容合并到对应 assistant 消息的 toolCalls[].result 中
 * 3. 过滤掉已合并的 tool_result 条目
 *
 * JSONL 存储结构：
 *   assistant: {role:"assistant", toolCalls:[{toolCallId, result:null}]}
 *   tool_result: {role:"tool", toolCallId, content:"output"}
 * 加载后合并为：
 *   assistant: {role:"assistant", toolCalls:[{toolCallId, result:"output"}]}
 */
export function processRawMessages(rawLines: Record<string, unknown>[]): Message[] {
  // 第一步：过滤 session_created，映射为 Message
  const messages: Message[] = []
  for (const raw of rawLines) {
    if (raw.type === 'session_created') continue
    messages.push(mapRawToMessage(raw))
  }

  // 第二步：建立 toolCallId → (msgIndex, tcIndex) 索引
  const toolIndex = new Map<string, { msgIdx: number; tcIdx: number }>()
  for (let i = 0; i < messages.length; i++) {
    const tcs = messages[i].toolCalls
    if (tcs && tcs.length > 0) {
      for (let j = 0; j < tcs.length; j++) {
        const tc = tcs[j] as ToolCall
        if (tc.toolCallId) {
          toolIndex.set(tc.toolCallId, { msgIdx: i, tcIdx: j })
        }
      }
    }
  }

  // 第三步：合并 tool 消息的结果到对应 toolCall，标记待移除
  const toRemove = new Set<number>()
  for (let i = 0; i < messages.length; i++) {
    const msg = messages[i]
    if (msg.role === 'tool' && msg.toolCallId) {
      const match = toolIndex.get(msg.toolCallId)
      if (match) {
        const parentMsg = messages[match.msgIdx]
        const tc = parentMsg.toolCalls![match.tcIdx] as ToolCall
        tc.result = msg.content
      }
      toRemove.add(i)
    }
  }

  // 第四步：过滤掉已合并的 tool 消息
  return messages.filter((_, i) => !toRemove.has(i))
}
