/**
 * Memory服务API封装，提供记忆检索、记忆摄取、记忆整合和统计查询等接口。
 *
 * LyClaw的记忆系统采用四层架构：感知记忆（SENSORY）、短期记忆（SHORT_TERM）、
 * 长期记忆（LONG_TERM）和实体记忆（ENTITY）。每一层有不同的生命周期和检索策略：
 * - 感知记忆：原始交互记录，保留最近对话的完整上下文
 * - 短期记忆：经过摘要压缩的近期交互，保留关键信息
 * - 长期记忆：经过整合和去重的持久化知识，支持语义检索
 * - 实体记忆：关于具体实体（用户、项目、任务）的结构化信息
 *
 * 本模块封装的API包括：
 * - retrieveMemory：根据查询条件检索记忆条目，支持多维度过滤
 * - ingestMemory：将新的感知数据录入记忆系统
 * - consolidateMemory：触发记忆整合，将短期记忆转化为长期记忆
 * - getMemoryStats：获取各层记忆的统计信息
 */
import { get, post } from './client'
import type {
  MemoryQuery,
  MemoryQueryResult,
  MemoryStats,
  PerceptionData,
} from '../types'

/**
 * 检索记忆：根据查询条件从记忆系统中搜索匹配的记忆条目。
 *
 * 支持多种检索参数：查询文本（语义搜索）、查询向量（向量搜索）、
 * topK（返回数量）、四维权重（alpha语义/beta时效/gamma重要性/delta访问频率）、
 * 以及层级过滤、类别过滤、标签过滤和元数据过滤。
 *
 * @param query 包含查询文本、向量、权重和过滤条件的MemoryQuery对象
 * @returns MemoryQueryResult包含匹配的记忆条目列表和检索耗时
 */
export function retrieveMemory(
  query: MemoryQuery,
): Promise<MemoryQueryResult> {
  return post<MemoryQueryResult>('/api/memory/retrieve', query)
}

/**
 * 摄取记忆：将新的交互数据录入记忆系统。
 *
 * 每次对话结束后，系统将用户消息和助手回复作为PerceptionData
 * 发送到记忆服务。记忆服务根据内容重要性、时效性等因素决定
 * 将其存储在哪一层以及保留多长时间。
 *
 * @param data 感知数据，包含角色、内容、时间戳和工具调用ID
 * @param sessionId 关联的会话ID，用于记忆追溯
 * @param userId 可选的用户ID，用于多用户场景下的记忆隔离
 * @returns 摄取确认结果
 */
export function ingestMemory(
  data: PerceptionData,
  sessionId: string,
  userId?: string,
): Promise<Record<string, unknown>> {
  const params = new URLSearchParams({ sessionId })
  if (userId) {
    params.set('userId', userId)
  }
  return post<Record<string, unknown>>(
    `/api/memory/ingest?${params.toString()}`,
    data,
  )
}

/**
 * 整合记忆：触发短期记忆向长期记忆的转化过程。
 *
 * 记忆整合是一个后台批处理过程，它会分析短期记忆中的模式，
 * 合并重复或相似的信息，提取关键知识点，并将其提升为长期记忆。
 * 同时会更新已有长期记忆的访问计数和重要性评分。
 *
 * @param userId 用户ID，用于确定整合范围
 * @param sessionId 会话ID，用于关联记忆来源
 * @returns 整合结果，包含整合前后的记忆数量变化
 */
export function consolidateMemory(
  userId: string,
  sessionId: string,
): Promise<Record<string, unknown>> {
  const params = new URLSearchParams({ userId, sessionId })
  return post<Record<string, unknown>>(
    `/api/memory/consolidate?${params.toString()}`,
  )
}

/**
 * 获取记忆系统的统计信息。
 *
 * 返回各记忆层的条目数量、总Token数、平均重要性评分、
 * 最近一次整合时间和最近一次清理时间等指标。
 * 这些数据用于Memory页面的统计面板展示。
 *
 * @returns MemoryStats包含各层统计数据和系统运行指标
 */
export function getMemoryStats(): Promise<MemoryStats> {
  return get<MemoryStats>('/api/memory/stats')
}
