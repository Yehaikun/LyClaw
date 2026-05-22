/**
 * LyClaw前端全局类型定义，涵盖枚举、模型层、记忆层、规划层、协议层和基础设施层的所有数据结构。
 *
 * 本文件是整个前端项目的类型基石，所有API请求/响应、Store状态、组件Props
 * 均依赖此处定义的类型和接口。按照功能域划分为以下板块：
 *
 * 1. 枚举定义（Enums）：记忆层类型、记忆类别、代理能力等常量枚举
 * 2. 时间属性（Temporal）：记忆条目的时间戳、过期时间和衰减因子
 * 3. 模型层类型（Model Types）：消息、会话、聊天请求/响应、工具调用和Token统计
 * 4. 记忆层类型（Memory Types）：记忆条目、查询参数、检索结果和统计信息
 * 5. 规划层类型（Plan/Task Types）：任务节点、计划请求和依赖图结构
 * 6. 协议层类型（Protocol Types）：MCP工具描述、A2A代理卡片和服务端点
 * 7. 基础设施类型（Infrastructure Types）：服务健康状态
 *
 * 设计原则：
 * - 所有接口字段尽量使用可选属性（?），兼容不同版本的服务端响应
 * - ISO时间格式统一使用字符串存储（ISO instant或ISO local datetime）
 * - 工具调用参数使用Record<string, unknown>保持灵活性
 * - 枚举值使用全大写SCREAMING_SNAKE_CASE，与Java后端保持一致
 */

// ====================================================================
// 枚举定义（Enums）
// ====================================================================

/** 记忆层类型：LyClaw四层记忆架构的层级标识 */
export enum MemoryLayerType {
  /** 感知记忆：原始交互记录的临时缓存 */
  SENSORY = 'SENSORY',
  /** 短期记忆：经过摘要压缩的近期重要信息 */
  SHORT_TERM = 'SHORT_TERM',
  /** 长期记忆：持久化的结构化知识，支持语义检索 */
  LONG_TERM = 'LONG_TERM',
  /** 实体记忆：关于具体实体（用户、项目、任务）的结构化信息 */
  ENTITY = 'ENTITY',
}

/** 记忆类别：记忆条目的内容分类 */
export enum MemoryCategory {
  FACT = 'FACT',
  PREFERENCE = 'PREFERENCE',
  EVENT = 'EVENT',
  LESSON = 'LESSON',
  TASK = 'TASK',
  RELATION = 'RELATION',
  GOAL = 'GOAL',
}


/** 代理能力枚举：A2A协议中代理可声明具备的能力类型 */
export enum AgentCapability {
  /** 文本生成与对话能力 */
  TEXT_GEN = 'TEXT_GEN',
  /** 工具调用与编排能力 */
  TOOL_USE = 'TOOL_USE',
  /** 代码执行与沙箱能力 */
  CODE_EXEC = 'CODE_EXEC',
  /** 检索增强生成能力 */
  RAG = 'RAG',
  /** 计算机远程操作能力 */
  COMPUTER_USE = 'COMPUTER_USE',
  /** 任务规划与分解能力 */
  PLANNING = 'PLANNING',
  /** 反思与自评估能力 */
  REFLECTION = 'REFLECTION',
  /** 记忆存储与管理能力 */
  MEMORY_MANAGEMENT = 'MEMORY_MANAGEMENT',
}

// ====================================================================
// 时间属性（Temporal）
// ====================================================================

/** 时间相关属性：每个记忆条目都携带时间戳和衰减参数 */
export interface TemporalProps {
  /** 创建时间（ISO instant格式） */
  createdAt: string
  /** 过期时间（ISO instant格式），null表示永不过期 */
  expiresAt: string | null
  /** 最近一次访问时间（ISO instant格式） */
  lastAccessedAt: string
  /** 衰减因子：控制记忆随时间的衰减速度，值越大衰减越快 */
  decayFactor: number
  /** 记忆强度：综合重要性、访问频率和新鲜度的加权值 */
  strength: number
}

// ====================================================================
// 模型层类型（Model Types）
// ====================================================================

/** 聊天消息：对话框中的单条消息 */
export interface Message {
  /** 消息角色：user（用户）、assistant（助手）、tool（工具结果） */
  role: string
  /** 消息文本内容 */
  content: string
  /** 生成此消息的模型名称（仅assistant消息有此字段） */
  model?: string
  /** Token使用统计（仅assistant消息有此字段） */
  usage?: Usage
  /** 关联的工具调用列表（仅包含工具调用的assistant消息有此字段） */
  toolCalls?: ToolCall[]
  /** 工具调用ID（仅tool角色消息有此字段，用于关联assistant的工具调用请求） */
  toolCallId?: string
}

/** Token使用统计：记录每次LLM调用的输入/输出Token消耗 */
export interface Usage {
  /** 输入Token数（提示词+历史消息） */
  promptTokens: number
  /** 输出Token数（生成的回复） */
  completionTokens: number
  /** 总Token数（输入+输出） */
  totalTokens: number
}

/** 工具调用记录：LLM发起的单次工具调用 */
export interface ToolCall {
  /** 工具调用唯一标识，用于将tool消息关联到对应的调用请求 */
  toolCallId: string
  /** 工具名称 */
  name: string
  /** 工具调用的自然语言描述（可选） */
  description?: string
  /** 传递给工具的参数（JSON字符串格式） */
  arguments: string
  /** 工具执行结果（JSON字符串格式），undefined表示尚未执行 */
  result?: string
}

/** 聊天请求：发送给服务端的完整请求体 */
export interface ChatRequest {
  /** 会话ID，用于关联同一会话的所有消息 */
  sessionId?: string
  /** 消息历史列表 */
  messages: Array<{ role: string; content: string }>
  /** 是否使用流式响应 */
  stream?: boolean
  /** 系统提示词，用于设定助手的行为和角色 */
  systemPrompt?: string
  /** 指定使用的模型名称 */
  model?: string
  /** 最大生成Token数 */
  maxTokens?: number
  /** 生成温度（0-2），控制随机性 */
  temperature?: number
  /** 核采样概率阈值（0-1） */
  topP?: number
  /** 可用工具定义列表（LLM可以从中选择调用） */
  tools?: ToolDefinition[]
  /** 是否启用深度思考模式 */
  thinkingEnabled?: boolean
  /** 思考预算（Token数） */
  thinkingBudget?: number
  /** 工具选择策略："auto"自动选择、"none"不调用、"required"必须调用 */
  toolChoice?: string
  /** 停止序列：遇到这些字符串时停止生成 */
  stopSequences?: string[]
  /** 扩展属性：用于传递模型特定的额外参数 */
  extras?: Record<string, unknown>
  /** 目标 Agent ID（Phase 3 agent routing） */
  agentId?: string
  /** 思考级别: off, minimal, low, medium, high, xhigh, adaptive, max（Phase 2） */
  thinkingLevel?: string
  /** 推理级别（Phase 2 reasoning control） */
  reasoningLevel?: string
  /** 详细度级别（Phase 2 verbose control） */
  verboseLevel?: string
}

/** 聊天会话：消息的容器，拥有唯一ID和元数据 */
export interface Session {
  /** 内部ID */
  id?: string
  /** 会话唯一标识（UUID格式） */
  sessionId: string
  /** 会话名称（可被用户重命名） */
  name: string
  /** 所属Agent ID */
  agentId?: string
  /** 创建会话时使用的模型 */
  model?: string
  /** 会话中包含的所有消息 */
  messages: Message[]
  /** 创建时间（ISO local datetime格式） */
  createdAt: string
  /** 最后更新时间（ISO local datetime格式） */
  updatedAt: string
  /** 消息数量 */
  messageCount?: number
  /** 工具调用次数 */
  toolCallCount?: number
  /** 总Token消耗 */
  totalTokens?: number
  /** 压缩次数 */
  compactionCount?: number
  /** 首条消息预览 */
  firstMsgPreview?: string
  /** JSONL文件路径 */
  filePath?: string
  /** 父会话ID */
  parentSessionId?: string | null
  /** 父Agent ID */
  parentAgentId?: string | null
}

/** 聊天结果：非流式聊天请求的完整响应 */
export interface ChatResult {
  /** 助手生成的完整回复文本 */
  content: string
  /** 生成结束原因（如stop、length、tool_calls等） */
  finishReason: string
  /** Token使用统计（JSON字符串格式） */
  tokenUsage: string | null
  /** 工具调用结果列表（LLM在生成过程中调用了工具时填充） */
  toolResults: ToolResult[] | null
  /** 总耗时（毫秒） */
  durationMs: number
}

/** 工具定义：工具的元数据描述，供LLM了解可用工具及如何调用 */
export interface ToolDefinition {
  /** 工具内部名称（唯一标识） */
  name: string
  /** 工具显示名称（前端展示用） */
  displayName: string
  /** 工具功能描述（LLM据此判断何时调用此工具） */
  description: string
  /** 工具参数定义（JSON Schema格式） */
  parameters: Record<string, unknown>
  /** 工具来源：Built-in（内置）、MCP（MCP协议）、A2A（代理间） */
  source: string
  /** 提供此工具的服务器名称 */
  serverName: string
  /** 工具执行超时时间（毫秒） */
  timeout: number
}

/** 工具执行结果：单次工具调用的返回结果 */
export interface ToolResult {
  /** 工具名称 */
  toolName: string
  /** 是否执行成功 */
  success: boolean
  /** 执行输出内容 */
  output: string
  /** 错误信息，成功时为null */
  errorMessage: string | null
  /** 执行耗时（毫秒） */
  durationMs: number
  /** 额外的元数据（可选） */
  metadata?: Record<string, unknown>
}

/** 工具执行请求：手动触发工具执行的请求体 */
export interface ToolExecuteRequest {
  /** 要执行的工具名称 */
  toolName: string
  /** 工具参数字典 */
  args: Record<string, unknown>
  /** 沙箱安全级别 */
  sandboxLevel?: string
  /** 关联的会话ID */
  sessionId?: string
}

/** 技能执行请求：触发预定义技能的请求体 */
export interface SkillExecuteRequest {
  /** 技能唯一标识 */
  skillId: string
  /** 关联的会话ID */
  sessionId?: string
  /** 技能参数（可选） */
  params?: Record<string, unknown>
}

/** 技能执行结果 */
export interface SkillResult {
  /** 技能唯一标识 */
  skillId: string
  /** 是否执行成功 */
  success: boolean
  /** 执行输出内容 */
  output: string
  /** 错误信息，成功时为null */
  error: string | null
  /** Token消耗量 */
  tokenUsage: number
  /** 执行耗时（毫秒） */
  elapsedMs: number
}


// ====================================================================
// 记忆层类型（Memory Types）
// ====================================================================

/** 记忆条目：记忆系统中的单条记忆记录 */
export interface MemoryEntry {
  /** 条目唯一标识 */
  entryId: string
  /** 所属用户ID */
  userId: string
  /** 关联的会话ID */
  sessionId: string
  /** 所在记忆层级 */
  layer: MemoryLayerType
  /** 记忆原文内容 */
  content: string
  /** 记忆摘要（可能由LLM生成），null表示无摘要 */
  summary: string | null
  /** 文本嵌入向量，用于向量相似度检索，null表示未生成 */
  embedding: number[] | null
  /** 记忆类别 */
  category: MemoryCategory
  /** 重要性评分（0-1），越高越重要 */
  importance: number
  /** 访问次数计数 */
  accessCount: number
  /** 时间相关属性 */
  temporal: TemporalProps
  /** 标签列表 */
  tags: string[]
  /** 扩展元数据 */
  metadata: Record<string, unknown>
}

/** 记忆查询：检索记忆时使用的查询参数 */
export interface MemoryQuery {
  /** 查询文本（用于语义搜索） */
  queryText?: string
  /** 查询向量（用于向量相似度搜索） */
  queryEmbedding?: number[]
  /** 返回的最大条目数 */
  topK: number
  /** 语义相似度权重（alpha） */
  alpha: number
  /** 时效性权重（beta） */
  beta: number
  /** 重要性权重（gamma） */
  gamma: number
  /** 访问频率权重（delta） */
  delta: number
  /** 层级过滤：只检索指定层级的记忆 */
  layerFilter?: MemoryLayerType[]
  /** 类别过滤：只检索指定类别的记忆 */
  categoryFilter?: MemoryCategory[]
  /** 标签过滤：只检索包含指定标签的记忆 */
  tagFilter?: string[]
  /** 元数据过滤：只检索匹配元数据条件的记忆 */
  metadataFilter?: Record<string, unknown>
}

/** 记忆查询结果：检索操作的返回结果 */
export interface MemoryQueryResult {
  /** 匹配的记忆条目列表 */
  entries: MemoryEntry[]
  /** 总命中数（可能大于返回的条目数） */
  totalHits: number
  /** 查询耗时（毫秒） */
  queryTimeMs: number
  /** 使用的检索方法（semantic/vector/hybrid） */
  retrievalMethod: string
}

/** 记忆统计信息：各层记忆的汇总统计数据 */
export interface MemoryStats {
  /** 感知记忆条目数 */
  perceptionCount: number
  /** 短期记忆条目数 */
  shortTermCount: number
  /** 长期记忆条目数 */
  longTermCount: number
  /** 实体记忆条目数 */
  entityCount: number
  /** 总Token消耗 */
  totalTokens: number
  /** 平均重要性评分 */
  avgImportance: number
  /** 最近一次记忆整合的时间戳 */
  lastConsolidationTime?: number
  /** 最近一次记忆清理的时间戳 */
  lastJanitorRunTime?: number
}

/** 感知数据：录入记忆系统的原始交互数据 */
export interface PerceptionData {
  /** 消息角色 */
  role: string
  /** 消息内容 */
  content: string
  /** 时间戳（毫秒） */
  timestamp: number
  /** 关联的工具调用ID列表 */
  toolCallIds: string[]
  /** 扩展元数据 */
  metadata: Record<string, unknown>
}

// ====================================================================
// 规划层类型（Plan / Task Types）
// ====================================================================

/** 计划请求：生成执行计划的请求体 */
export interface PlanRequest {
  /** 关联的会话ID */
  sessionId?: string
  /** 用户意图描述文本 */
  userIntent: string
  /** 分解策略名称（如DAG、COT、REACT等） */
  strategy?: string
  /** 上下文信息 */
  context?: Record<string, unknown>
}

/** 任务节点：DAG执行计划中的单个任务 */
export interface TaskNode {
  /** 节点唯一标识 */
  nodeId: string
  /** 节点类型：EXECUTE（执行）、CHECK（校验）、DECISION（决策）、MERGE（合并） */
  type: string
  /** 节点任务描述 */
  description: string
  /** 执行此节点需要的工具列表 */
  requiredTools: string[]
  /** 依赖的前置节点ID列表 */
  dependencies: string[]
  /** 执行超时时间（毫秒） */
  timeoutMs: number
}

// ====================================================================
// 协议层类型（Protocol Types）
// ====================================================================

/** MCP工具描述符：MCP服务器提供的工具元数据 */
export interface McpToolDescriptor {
  /** 工具名称 */
  name: string
  /** 工具描述 */
  description: string
  /** 工具输入参数schema（JSON Schema格式） */
  inputSchema: Record<string, unknown>
  /** 提供此工具的MCP服务器名称 */
  serverName: string
}

/** 代理服务端点：A2A协议中代理暴露的API端点 */
export interface AgentEndpoint {
  /** 端点URL */
  url: string
  /** 传输协议类型（http/grpc等） */
  transportType: string
  /** 是否为主要端点 */
  primary: boolean
}

/** 代理能力卡片：A2A协议中代理的自描述文档 */
export interface AgentCard {
  /** 代理唯一标识 */
  agentId: string
  /** 代理名称 */
  name: string
  /** 代理功能描述 */
  description: string
  /** 代理服务URL */
  url: string
  /** 代理版本号 */
  version: string
  /** 代理具备的能力列表 */
  capabilities: AgentCapability[]
  /** 代理暴露的服务端点列表 */
  endpoints: AgentEndpoint[]
  /** 扩展元数据 */
  metadata: Record<string, string>
}

// ====================================================================
// 基础设施类型（Infrastructure Types）
// ====================================================================

/** 服务健康状态：健康检查端点的标准响应 */
export interface ServiceHealth {
  /** 服务是否健康 */
  healthy: boolean
  /** 详细状态描述 */
  status?: string
  /** 运行时长（秒） */
  uptime?: number
  /** 响应延迟（毫秒） */
  latency?: number
  /** 额外的详情信息 */
  details?: Record<string, unknown>
}
