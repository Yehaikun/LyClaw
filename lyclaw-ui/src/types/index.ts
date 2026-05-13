// ===== Enums =====

export enum MemoryLayerType {
  SENSORY = 'SENSORY',
  SHORT_TERM = 'SHORT_TERM',
  LONG_TERM = 'LONG_TERM',
  ENTITY = 'ENTITY',
}

export enum MemoryCategory {
  FACT = 'FACT',
  PREFERENCE = 'PREFERENCE',
  EVENT = 'EVENT',
  LESSON = 'LESSON',
  TASK = 'TASK',
  RELATION = 'RELATION',
  GOAL = 'GOAL',
}



export enum AgentCapability {
  TEXT_GEN = 'TEXT_GEN',
  TOOL_USE = 'TOOL_USE',
  CODE_EXEC = 'CODE_EXEC',
  RAG = 'RAG',
  COMPUTER_USE = 'COMPUTER_USE',
  PLANNING = 'PLANNING',
  REFLECTION = 'REFLECTION',
  MEMORY_MANAGEMENT = 'MEMORY_MANAGEMENT',
}

// ===== Temporal =====

export interface TemporalProps {
  createdAt: string // ISO instant
  expiresAt: string | null // ISO instant
  lastAccessedAt: string // ISO instant
  decayFactor: number
  strength: number
}

// ===== Model Types =====

export interface Message {
  role: string
  content: string
  model?: string
  usage?: Usage
  toolCalls?: ToolCall[]
  toolCallId?: string
}

export interface Usage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

export interface ToolCall {
  toolCallId: string
  name: string
  description?: string
  arguments: string
  result?: string
}

export interface ChatRequest {
  sessionId?: string
  messages: Array<{ role: string; content: string }>
  stream?: boolean
  systemPrompt?: string
  model?: string
  maxTokens?: number
  temperature?: number
  topP?: number
  tools?: ToolDefinition[]
  thinkingEnabled?: boolean
  thinkingBudget?: number
  toolChoice?: string
  stopSequences?: string[]
  extras?: Record<string, unknown>
}

export interface Session {
  id: string
  sessionId: string
  name: string
  model?: string
  messages: Message[]
  createdAt: string // ISO local datetime
  updatedAt: string // ISO local datetime
}

export interface ChatResult {
  content: string
  finishReason: string
  tokenUsage: string | null
  toolResults: ToolResult[] | null
  durationMs: number
}

export interface ToolDefinition {
  name: string
  displayName: string
  description: string
  parameters: Record<string, unknown>
  source: string
  serverName: string
  timeout: number
}

export interface ToolResult {
  toolName: string
  success: boolean
  output: string
  errorMessage: string | null
  durationMs: number
  metadata?: Record<string, unknown>
}

export interface ToolExecuteRequest {
  toolName: string
  args: Record<string, unknown>
  sandboxLevel?: string
  sessionId?: string
}

export interface SkillExecuteRequest {
  skillId: string
  sessionId?: string
  params?: Record<string, unknown>
}

export interface SkillResult {
  skillId: string
  success: boolean
  output: string
  error: string | null
  tokenUsage: number
  elapsedMs: number
}


// ===== Memory Types =====

export interface MemoryEntry {
  entryId: string
  userId: string
  sessionId: string
  layer: MemoryLayerType
  content: string
  summary: string | null
  embedding: number[] | null
  category: MemoryCategory
  importance: number
  accessCount: number
  temporal: TemporalProps
  tags: string[]
  metadata: Record<string, unknown>
}

export interface MemoryQuery {
  queryText?: string
  queryEmbedding?: number[]
  topK: number
  alpha: number
  beta: number
  gamma: number
  delta: number
  layerFilter?: MemoryLayerType[]
  categoryFilter?: MemoryCategory[]
  tagFilter?: string[]
  metadataFilter?: Record<string, unknown>
}

export interface MemoryQueryResult {
  entries: MemoryEntry[]
  totalHits: number
  queryTimeMs: number
  retrievalMethod: string
}

export interface MemoryStats {
  perceptionCount: number
  shortTermCount: number
  longTermCount: number
  entityCount: number
  totalTokens: number
  avgImportance: number
  lastConsolidationTime?: number
  lastJanitorRunTime?: number
}

export interface PerceptionData {
  role: string
  content: string
  timestamp: number
  toolCallIds: string[]
  metadata: Record<string, unknown>
}

// ===== Reflection Types =====







// ===== Plan / Task Types =====

export interface PlanRequest {
  sessionId?: string
  userIntent: string
  strategy?: string
  context?: Record<string, unknown>
}

export interface TaskNode {
  nodeId: string
  type: string
  description: string
  requiredTools: string[]
  dependencies: string[]
  timeoutMs: number
}

// ===== Protocol Types =====

export interface McpToolDescriptor {
  name: string
  description: string
  inputSchema: Record<string, unknown>
  serverName: string
}

export interface AgentEndpoint {
  url: string
  transportType: string
  primary: boolean
}

export interface AgentCard {
  agentId: string
  name: string
  description: string
  url: string
  version: string
  capabilities: AgentCapability[]
  endpoints: AgentEndpoint[]
  metadata: Record<string, string>
}

// ===== Infrastructure Types =====


export interface ServiceHealth {
  healthy: boolean
  status?: string
  uptime?: number
  latency?: number
  details?: Record<string, unknown>
}


