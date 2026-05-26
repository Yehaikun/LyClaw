/**
 * 聊天状态管理Store（Pinia），管理对话消息、流式输出、错误处理和会话关联。
 *
 * 这是整个聊天功能的核心状态管理器，负责以下关键职责：
 *
 * 1. 消息管理：
 *    - messages数组保存所有对话消息（user/assistant/tool角色）
 *    - currentStreamingText保存正在流式接收但尚未固化的临时文本
 *    - isStreaming标志控制发送按钮显示为停止按钮还是发送按钮
 *
 * 2. 流式输出处理：
 *    - sendMessage函数优先使用SSE流式模式（postChatStream）
 *    - 流式过程中每个chunk追加到currentStreamingText，Vue响应式驱动UI实时更新
 *    - 流正常结束时将currentStreamingText固化为assistant消息
 *    - 流异常中断时保留已接收的部分内容，可选降级为非流式请求
 *
 * 3. 错误处理：
 *    - error字段保存当前错误消息，errorTraceId保存追踪标识
 *    - 支持从ApiError中提取traceId，便于用户反馈问题时提供追踪信息
 *    - 非中止类错误触发非流式降级重试
 *
 * 4. 模型切换：
 *    - currentModel保存当前选用的LLM模型标识
 *    - setModel函数根据模型名前缀自动推断提供商（deepseek/anthropic/openai）
 *
 * 5. 会话管理：
 *    - currentSessionId关联当前活跃的聊天会话
 *    - 发送消息前若无会话则自动创建新会话
 *
 * 6. 重试机制：
 *    - retry函数回退到最近一次用户消息并重新发送
 *    - 通过splice移除上次用户消息之后的所有消息，再调用sendMessage
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Message, ChatRequest, ChatResult, ToolCall } from '@/types'
import { postChat, postChatStream } from '@/api/chat'
import { useSessionStore } from '@/stores/session'
import { post, ApiError } from '@/api/client'

interface PendingApproval {
  toolCallId: string
  toolName: string
  arguments: string
  message: string
}

export interface AgentActivity {
  agentId: string
  status: 'idle' | 'running' | 'completed' | 'failed'
  task: string
  startedAt: number
  completedAt?: number
  output?: string
  error?: string
  parentAgentId?: string
}

export interface SubagentMessage {
  agentId: string
  content: string
  role: 'assistant' | 'tool'
  status: 'running' | 'completed' | 'failed'
  toolCalls?: ToolCall[]
}

export const useChatStore = defineStore('chat', () => {
  // ====================================================================
  // 状态（State）
  // ====================================================================

  /** 对话消息列表，包含user、assistant和tool三种角色的消息 */
  const messages = ref<Message[]>([])

  /** 活跃 Agent 活动跟踪：并行运行的 Agent 状态 */
  const activeAgents = ref<Map<string, AgentActivity>>(new Map())

  /** 子 Agent 输出消息：按 agentId 分组的独立输出 */
  const agentOutputs = ref<Map<string, SubagentMessage[]>>(new Map())

  /** 是否正在等待子 Agent 结果（显示等待指示器） */
  const waitingForSubagent = ref<boolean>(false)
  /** 流式输出过程中实时累积的文本，流结束后清空并固化为assistant消息 */
  const currentStreamingText = ref<string>('')
  /** 是否正在进行流式输出，用于控制UI状态（按钮切换、动画显示等） */
  const isStreaming = ref<boolean>(false)
  /** 当前错误消息，为null表示无错误 */
  const error = ref<string | null>(null)
  /** 错误关联的分布式追踪ID，用于后端日志关联定位问题 */
  const errorTraceId = ref<string | undefined>(undefined)
  /** 当前选用的LLM模型标识（如deepseek-4-pro、claude-opus-4-7等） */
  const currentModel = ref<string>('deepseek-4-pro')
  /** 当前模型所属的提供商名称（deepseek/anthropic/openai） */
  const currentProvider = ref<string>('deepseek')
  /** 当前活跃会话的唯一标识，用于关联所有后续消息 */
  const currentSessionId = ref<string | null>(null)
  /** 工具调用状态文字：后端推送 status 事件时更新，流式文本到达或流结束时清空 */
  const toolStatus = ref<string>('')
  /** 流式进行中的工具调用列表：tool_call 事件驱动，用于展示加载动画卡片 */
  const liveToolCalls = ref<ToolCall[]>([])

  /** 等待用户审批的工具调用信息，非null时前端显示确认对话框 */
  const pendingApproval = ref<PendingApproval | null>(null)

  /** 模型推理/思考阶段的流式文本（Phase 2 thinking/reasoning SSE 事件） */
  const thinkingText = ref<string>('')

  /** 子代理委派事件列表（Phase 2 subagent_spawned/subagent_ended SSE 事件） */
  const subagentEvents = ref<Array<{ event: string; data: string }>>([])
  const reflectionProgress = ref<any>(null)

  // ====================================================================
  // 计算属性（Getters）
  // ====================================================================

  /** 消息总数，用于判断是否显示欢迎页或空状态 */
  const messageCount = computed<number>(() => messages.value.length)

  /** 最后一条消息，用于获取最新的对话上下文 */
  const lastMessage = computed<Message | null>(
    () => messages.value[messages.value.length - 1] ?? null,
  )

  /** 最近一条用户消息，用于重试功能：找到最后一次用户输入并重新发送 */
  const lastUserMessage = computed<Message | null>(() => {
    for (let i = messages.value.length - 1; i >= 0; i--) {
      if (messages.value[i].role === 'user') {
        return messages.value[i]
      }
    }
    return null
  })

  /** 模型是否正在输出推理/思考内容（Phase 2 thinking SSE 事件） */
  const isThinking = computed<boolean>(() => thinkingText.value.length > 0)

  // ====================================================================
  // 操作方法（Actions）
  // ====================================================================

  /**
   * 发送用户消息并获取助手回复。
   *
   * 完整的发送流程分为以下阶段：
   * 1. 验证输入：忽略空白消息
   * 2. 追加用户消息：将用户消息添加到messages数组
   * 3. 保障会话：若无活跃会话则自动创建新会话，失败则终止发送
   * 4. 构建请求：收集所有历史消息、设置sessionId和stream标志
   * 5. 启动流式连接：调用postChatStream建立SSE连接
   * 6. 流式处理：每个chunk追加到currentStreamingText，Vue响应式自动更新UI
   * 7. 流完成：将currentStreamingText固化为assistant消息
   * 8. 错误处理：保存部分内容、记录错误信息、可选降级为非流式
   *
   * @param text 用户输入的文本内容
   * @param sessionId 可选的会话ID，不传则使用当前活跃会话
   */
  async function sendMessage(text: string, sessionId?: string | null): Promise<void> {
    if (!text.trim()) return

    const userMsg: Message = { role: 'user', content: text }
    messages.value.push(userMsg)
    error.value = null

    // 会话由后端管理：首次消息不传sessionId，后端创建并通过session_created事件告知前端
    const targetSessionId = sessionId ?? currentSessionId.value
    const activeSessionId: string | undefined = targetSessionId || undefined

    const request: ChatRequest = {
      sessionId: activeSessionId,
      messages: messages.value.map((m) => ({ role: m.role, content: m.content })),
      stream: true,
    }

    isStreaming.value = true
    currentStreamingText.value = ''
    toolStatus.value = ''
    liveToolCalls.value = []
    pendingApproval.value = null
    thinkingText.value = ''
    subagentEvents.value = []
    activeAgents.value = new Map()
    agentOutputs.value = new Map()
    waitingForSubagent.value = false

    try {
      const agentId = useSessionStore().currentAgentId
      await postChatStream(
        agentId,
        request,
        // 流式数据块回调：每收到一段文本就追加到currentStreamingText
        (chunk: string) => {
          // 首个文本块到达 → 工具调用阶段结束，清空状态文字
          if (toolStatus.value) toolStatus.value = ''
          currentStreamingText.value += chunk
        },
        // 流正常完成回调：将累积的流式文本固化为assistant消息
        () => {
          const hasContent = currentStreamingText.value || thinkingText.value
          if (hasContent) {
            const assistantMsg: Message = {
              role: 'assistant',
              content: currentStreamingText.value,
              model: currentModel.value,
              thinking: thinkingText.value || undefined,
            }
            messages.value.push(assistantMsg)
          }
          currentStreamingText.value = ''
          thinkingText.value = ''
          subagentEvents.value = []
          toolStatus.value = ''
          liveToolCalls.value = []
          pendingApproval.value = null
          isStreaming.value = false
        },
        // 错误回调：保存已接收的部分内容，记录错误信息
        (err: Error) => {
          // 即使出错，已接收的部分内容也作为assistant消息保留
          const hasContent = currentStreamingText.value || thinkingText.value
          if (hasContent) {
            const partialMsg: Message = {
              role: 'assistant',
              content: currentStreamingText.value,
              model: currentModel.value,
              thinking: thinkingText.value || undefined,
            }
            messages.value.push(partialMsg)
          }
          currentStreamingText.value = ''
          thinkingText.value = ''
          // 提取ApiError中的traceId用于错误追踪
          if (err instanceof ApiError) {
            errorTraceId.value = (err as ApiError).traceId
          } else {
            errorTraceId.value = undefined
          }
          // 非中止类错误降级为非流式重试，中止/超时不重试因为已保留部分内容
          if (err.name !== 'AbortError' && !(err instanceof ApiError && (err as ApiError).status === 0)) {
            error.value = err.message
            sendMessageNonStreaming(request)
          } else {
            error.value = null  // 已保存部分内容，不显示错误
            errorTraceId.value = undefined
          }
        },
        // 工具调用状态回调：后端推送status事件时更新toolStatus供视图展示
        (statusText: string) => {
          toolStatus.value = statusText
        },
        // tool_call 事件回调：后端推送工具调用执行状态
        (data: string) => {
          try {
            const event = JSON.parse(data)
            if (event.status === 'executing') {
              // 若 AI 在调工具之前先发了文本（如"好的，我来查..."），
              // 立即将该文本固化为一条独立消息，不再阻塞在 streaming 状态。
              if (currentStreamingText.value) {
                messages.value.push({
                  role: 'assistant',
                  content: currentStreamingText.value,
                  model: currentModel.value,
                })
                currentStreamingText.value = ''
              }
              liveToolCalls.value.push({
                toolCallId: event.toolCallId || '',
                name: event.name || '',
                arguments: event.arguments || '',
              })
              toolStatus.value = event.message || `正在执行 ${event.name}...`
            } else if (event.status === 'done') {
              // 工具执行完成 → 从 liveToolCalls 移除，固化为独立持久消息
              liveToolCalls.value = liveToolCalls.value.filter(
                (tc) => tc.toolCallId !== event.toolCallId,
              )
              if (liveToolCalls.value.length === 0) {
                toolStatus.value = ''
              }
              messages.value.push({
                role: 'assistant',
                content: '',
                model: currentModel.value,
                toolCalls: [{
                  toolCallId: event.toolCallId || '',
                  name: event.name || '',
                  arguments: event.arguments || '',
                  description: event.message || '',
                  result: JSON.stringify({
                    success: event.success !== false,
                    output: event.result || '',
                  }),
                }],
              })
            }
            // 检测 delegate_to_agent 工具调用 → 更新 Agent 活动面板
            if (event.name === 'delegate_to_agent' && event.status === 'executing') {
              try {
                const args = JSON.parse(event.arguments || '{}')
                if (args.agentId) {
                  const act: AgentActivity = {
                    agentId: args.agentId,
                    status: 'running',
                    task: (args.task || '').substring(0, 100),
                    startedAt: Date.now(),
                  }
                  activeAgents.value.set(args.agentId, act)
                  activeAgents.value = new Map(activeAgents.value)
                  waitingForSubagent.value = true
                }
              } catch { /* ignore */ }
            }
            if (event.name === 'delegate_to_agent' && event.status === 'done') {
              try {
                const args = JSON.parse(event.arguments || '{}')
                if (args.agentId) {
                  const agentId = args.agentId
                  const existing = activeAgents.value.get(agentId)
                  if (existing) {
                    existing.status = 'completed'
                    existing.completedAt = Date.now()
                    existing.output = event.result || '完成'
                    activeAgents.value = new Map(activeAgents.value)
                  }
                  // 子 Agent 输出
                  if (event.result) {
                    const output: SubagentMessage = {
                      agentId,
                      content: event.result,
                      role: 'assistant',
                      status: 'completed',
                    }
                    if (!agentOutputs.value.has(agentId)) {
                      agentOutputs.value.set(agentId, [])
                    }
                    agentOutputs.value.get(agentId)!.push(output)
                    agentOutputs.value = new Map(agentOutputs.value)
                    waitingForSubagent.value = false
                  }
                }
              } catch { /* ignore */ }
            }
          } catch { /* ignore malformed JSON */ }
        },
        // tool_approval 事件回调：非只读工具需要用户确认，弹出审批对话框
        (data: string) => {
          try {
            const event = JSON.parse(data)
            pendingApproval.value = {
              toolCallId: event.toolCallId || '',
              toolName: event.toolName || '',
              arguments: event.arguments || '',
              message: event.message || `AI 请求执行 ${event.toolName || '未知工具'}`,
            }
          } catch { /* ignore malformed JSON */ }
        },
        // thinking 事件回调：Phase 2 模型推理/思考内容流式推送
        (text: string) => {
          setThinking(text)
        },
        // 通用事件回调：捕获所有 SSE 事件，包括多 Agent 协作事件
        (event: string, data: string) => {
          if (event === 'session_created') {
            try {
              const info = JSON.parse(data)
              if (info.sessionId) {
                currentSessionId.value = info.sessionId
                const ss = useSessionStore()
                ss.currentSessionId = info.sessionId
                if (info.isNew && !ss.sessions.find(s => s.sessionId === info.sessionId)) {
                  ss.sessions.unshift({
                    sessionId: info.sessionId,
                    name: 'Chat ' + new Date().toLocaleDateString(),
                    agentId: info.agentId || ss.currentAgentId,
                    createdAt: new Date().toISOString(),
                    updatedAt: new Date().toISOString(),
                    messageCount: 0,
                    toolCallCount: 0,
                    totalTokens: 0,
                    compactionCount: 0,
                    firstMsgPreview: '',
                  } as any)
                }
              }
            } catch { /* ignore */ }
          }
          // 多 Agent 路由与协作事件处理
          handleMultiAgentEvent(event, data)
          addSubagentEvent(event, data)
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

  /**
   * 非流式聊天回退：当流式连接失败时，使用传统请求-响应模式获取完整回复。
   *
   * 此函数作为sendMessage的降级路径，将同一请求以非流式方式重新发送。
   * 响应包含完整的content、tokenUsage统计和toolResults工具调用记录。
   *
   * @param request 与流式请求相同的ChatRequest对象，stream被强制设为false
   */
  async function sendMessageNonStreaming(request: ChatRequest): Promise<void> {
    try {
      const result: ChatResult = await postChat(useSessionStore().currentAgentId, {
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

  /**
   * 停止当前的流式生成。
   *
   * 将isStreaming设为false，这会触发SSE连接的AbortController取消。
   * 已接收的部分文本保留在currentStreamingText中，
   * ChatView组件会将其显示为部分回复。
   */
  function stopGeneration(): void {
    isStreaming.value = false
    pendingApproval.value = null
  }

  /**
   * 设置推理/思考流式文本（Phase 2 thinking SSE 事件回调）。
   *
   * @param text 累积的思考文本内容
   */
  function setThinking(text: string): void {
    thinkingText.value += text
  }

  /** 清空推理/思考文本，在流结束时或新一轮对话开始时调用。 */
  function clearThinking(): void {
    thinkingText.value = ''
  }

  /**
   * 添加子代理委派事件记录（Phase 2 subagent_spawned/subagent_ended SSE 事件）。
   * 每收到一个子代理事件就追加到数组中，供视图层展示子代理的委派链路。
   *
   * @param event 事件类型（如 subagent_spawned、subagent_ended）
   * @param data 事件携带的JSON数据
   */
  function handleMultiAgentEvent(event: string, data: string): void {
    try {
      const info = JSON.parse(data)
      switch (event) {
        case 'routing_start':
          waitingForSubagent.value = true
          break
        case 'routing_decision':
          waitingForSubagent.value = false
          if (info.targetAgentId) {
            const act: AgentActivity = {
              agentId: info.targetAgentId,
              status: 'running',
              task: info.reason || '委派任务',
              startedAt: Date.now(),
            }
            activeAgents.value.set(info.targetAgentId, act)
            activeAgents.value = new Map(activeAgents.value)
          }
          break
        case 'routing_fallback':
          waitingForSubagent.value = false
          break
        case 'sub_task_start':
          if (info.assignedAgent || info.description) {
            const agentId = info.assignedAgent || info.agentId || 'unknown'
            const act: AgentActivity = {
              agentId,
              status: 'running',
              task: info.description || '子任务',
              startedAt: Date.now(),
              parentAgentId: info.parentAgentId,
            }
            activeAgents.value.set(agentId, act)
            activeAgents.value = new Map(activeAgents.value)
            waitingForSubagent.value = true
          }
          break
        case 'sub_task_complete':
          if (info.assignedAgent || info.agentId) {
            const agentId = info.assignedAgent || info.agentId
            const existing = activeAgents.value.get(agentId)
            if (existing) {
              existing.status = 'completed'
              existing.completedAt = Date.now()
              activeAgents.value = new Map(activeAgents.value)
            }
            // 添加子 Agent 输出到 agentOutputs
            if (info.result || info.output) {
              const output: SubagentMessage = {
                agentId,
                content: info.result || info.output || '',
                role: 'assistant',
                status: 'completed',
              }
              if (!agentOutputs.value.has(agentId)) {
                agentOutputs.value.set(agentId, [])
              }
              agentOutputs.value.get(agentId)!.push(output)
              agentOutputs.value = new Map(agentOutputs.value)
            }
          }
          break
        case 'sub_task_fail':
          if (info.assignedAgent || info.agentId) {
            const agentId = info.assignedAgent || info.agentId
            const existing = activeAgents.value.get(agentId)
            if (existing) {
              existing.status = 'failed'
              existing.error = info.error || '执行失败'
              existing.completedAt = Date.now()
              activeAgents.value = new Map(activeAgents.value)
            }
          }
          break
        case 'aggregation_complete':
          waitingForSubagent.value = false
          break
        case 'collaboration_start':
          waitingForSubagent.value = true
          break
        default:
          // 其他事件忽略
          break
      }
    } catch { /* ignore malformed JSON */ }
  }

  function addSubagentEvent(event: string, data: string): void {
    subagentEvents.value.push({ event, data })
  }

  /**
   * 清空所有聊天消息和错误状态。
   *
   * 重置messages、currentStreamingText、error和errorTraceId。
   * 通常在用户点击"New Chat"或切换到新会话时调用。
   */
  function clearChat(): void {
    messages.value = []
    currentStreamingText.value = ''
    thinkingText.value = ''
    subagentEvents.value = []
    error.value = null
    errorTraceId.value = undefined
    pendingApproval.value = null
    activeAgents.value = new Map()
    agentOutputs.value = new Map()
    waitingForSubagent.value = false
  }

  /**
   * 重试最后一条用户消息。
   *
   * 通过以下步骤实现重试：
   * 1. 找到最后一条user角色的消息及其在数组中的索引
   * 2. 使用splice删除从该索引开始的所有消息（包括该用户消息本身）
   * 3. 重新调用sendMessage发送该用户消息的内容
   * 这确保重试时携带完整的会话上下文（之前的历史消息）。
   */
  async function retry(): Promise<void> {
    const lastUser = lastUserMessage.value
    if (!lastUser) return

    // 找到最后一条用户消息的索引位置
    const userIdx = messages.value.lastIndexOf(lastUser)
    // 移除该用户消息及其之后的所有内容
    messages.value.splice(userIdx)
    // 重新发送
    await sendMessage(lastUser.content, currentSessionId.value ?? undefined)
  }

  /**
   * 设置当前使用的LLM模型。
   *
   * 同时根据模型名称前缀自动推断提供商：
   * - deepseek开头 → deepseek提供商
   * - claude开头 → anthropic提供商
   * - gpt开头 → openai提供商
   *
   * @param model 模型标识（如deepseek-4-pro）
   * @param provider 可选的提供商名称，不传则自动推断
   */
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

  /**
   * 更新当前会话ID，用于关联后续所有消息到指定会话。
   *
   * @param id 会话唯一标识
   */
  function setSessionId(id: string | null): void {
    currentSessionId.value = id
  }

  function setMessages(msgs: Message[]): void {
    messages.value = msgs
  }

  /** 将消息插入到列表开头（用于向上翻页加载更早的历史消息） */
  function prependMessages(msgs: Message[]): void {
    messages.value = [...msgs, ...messages.value]
  }

  /**
   * 添加工具调用的结果消息到对话中。
   *
   * 当LLM调用工具并返回结果时，将工具结果以tool角色的消息
   * 追加到messages数组，使后续请求能够包含完整的工具调用上下文。
   *
   * @param toolCall 包含toolCallId、name和result的ToolCall对象
   */
  function addToolCallMessage(toolCall: ToolCall): void {
    const msg: Message = {
      role: 'tool',
      content: toolCall.result ?? '',
      toolCallId: toolCall.toolCallId,
    }
    messages.value.push(msg)
  }

  /**
   * 响应工具执行审批请求：用户点击允许/拒绝后，POST到后端完成CompletableFuture。
   *
   * @param approved true=允许执行，false=拒绝
   */
  async function respondToApproval(approved: boolean): Promise<void> {
    const approval = pendingApproval.value
    if (!approval) return
    try {
      await post('/api/approval/respond', {
        toolCallId: approval.toolCallId,
        approved,
      })
    } catch {
      // 网络错误不阻塞，后端有超时自动拒绝兜底
    }
    pendingApproval.value = null
  }

  // ====================================================================
  // 内部辅助函数（Internal Helpers）
  // ====================================================================

  /**
   * 解析token使用统计字符串为结构化对象。
   *
   * 服务端返回的tokenUsage是JSON字符串格式，包含promptTokens（输入token数）、
   * completionTokens（输出token数）和totalTokens（总计）。
   * 兼容驼峰命名和下划线命名两种格式。
   *
   * @param usageStr JSON格式的token使用统计字符串
   * @returns 包含promptTokens、completionTokens、totalTokens的结构化对象
   */
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
    // 状态
    messages,
    currentStreamingText,
    isStreaming,
    error,
    errorTraceId,
    currentModel,
    currentProvider,
    currentSessionId,
    toolStatus,
    liveToolCalls,
    pendingApproval,
    thinkingText,
    subagentEvents,
    activeAgents,
    agentOutputs,
    waitingForSubagent,
    reflectionProgress,
    // 计算属性
    messageCount,
    lastMessage,
    lastUserMessage,
    isThinking,
    // 操作方法
    sendMessage,
    stopGeneration,
    clearChat,
    retry,
    setModel,
    setSessionId,
    setMessages,
    prependMessages,
    addToolCallMessage,
    respondToApproval,
    setThinking,
    clearThinking,
    addSubagentEvent,
  }
})
