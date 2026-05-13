/**
 * Plan服务API封装，提供任务规划、计划修订、任务分解和计划验证等接口。
 *
 * LyClaw的Plan服务负责将用户的复杂意图分解为可执行的任务图（DAG）。
 * 它支持多种分解策略：顺序分解（Sequential）、有向无环图（DAG）、
 * 思维链（CoT）、ReAct循环、层级分解（Hierarchical）等。
 *
 * 本模块封装的API包括：
 * - generatePlan：根据用户意图生成执行计划
 * - revisePlan：根据反馈修订已有计划
 * - decomposeTask：将单个任务递归分解为更细粒度的子任务
 * - validatePlan：验证计划的完整性、一致性和可行性
 * - buildGraph：构建任务依赖图（DAG），计算关键路径和并行度
 * - listStrategies：获取所有可用的分解策略及其描述
 * - getProgress：查询特定计划的执行进度
 */
import { get, post } from './client'
import type { PlanRequest } from '../types'

/**
 * 根据用户意图生成任务执行计划。
 *
 * 这是Plan服务的核心端点。接收用户意图描述和可选的分解策略，
 * 返回包含任务节点列表、依赖关系、关键路径、预估时间和最大并行度的计划对象。
 * 计划以DAG形式组织，每个节点包含类型（EXECUTE/CHECK/DECISION/MERGE）、
 * 描述、所需工具、依赖节点和超时设置。
 *
 * @param req 包含userIntent（用户意图）和可选strategy（分解策略）的请求
 * @returns 包含nodes、criticalPath、estimatedTimeMs等字段的计划对象
 */
export function generatePlan(req: PlanRequest): Promise<Record<string, unknown>> {
  return post<Record<string, unknown>>('/api/plan/plan', req)
}

/**
 * 修订已有计划：根据执行反馈或用户变更调整计划内容。
 *
 * 当计划执行过程中遇到障碍（工具不可用、超时、结果不符合预期）时，
 * 通过此端点提交修订请求，服务端重新评估并生成调整后的计划。
 *
 * @param body 包含原计划ID和修订指令的对象
 * @returns 修订后的计划对象
 */
export function revisePlan(
  body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  return post<Record<string, unknown>>('/api/plan/revise', body)
}

/**
 * 将复杂任务递归分解为更细粒度的子任务树。
 *
 * 适用于单个任务节点过于庞大或模糊的情况。
 * 分解过程可以持续进行直到所有叶子节点都是可直接执行的原子任务。
 *
 * @param body 包含任务描述和分解深度参数的对象
 * @returns 分解后的子任务树结构
 */
export function decomposeTask(
  body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  return post<Record<string, unknown>>('/api/plan/decompose', body)
}

/**
 * 验证计划的完整性和可行性。
 *
 * 检查项目包括：所有依赖节点是否存在、是否形成循环依赖、
 * 所需工具是否可用、超时设置是否合理、资源冲突检测等。
 *
 * @param body 包含待验证计划的对象
 * @returns 验证结果，包含通过/失败标志和具体问题列表
 */
export function validatePlan(
  body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  return post<Record<string, unknown>>('/api/plan/validate', body)
}

/**
 * 构建任务依赖图并计算图论指标。
 *
 * 基于任务节点列表和依赖关系构建完整的DAG，计算以下指标：
 * - 拓扑排序层级（每层节点可并行执行）
 * - 关键路径（决定总执行时间的最长路径）
 * - 最大并行度（同一层级的最多节点数）
 *
 * @param body 包含任务节点列表和依赖关系定义的对象
 * @returns 包含图结构和分析指标的对象
 */
export function buildGraph(
  body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  return post<Record<string, unknown>>('/api/plan/graph', body)
}

/**
 * 获取所有可用的任务分解策略列表。
 *
 * 每种策略适用于不同类型的任务：
 * - SEQUENTIAL：适合步骤明确的线性任务
 * - DAG：适合存在复杂依赖关系的任务网络
 * - CoT：适合需要逐步推理的分析任务
 * - REACT：适合需要推理-行动交替的交互式任务
 * - HIERARCHICAL：适合可递归分解的复杂任务
 *
 * @returns 策略对象数组，每个包含name和description
 */
export function listStrategies(): Promise<Record<string, unknown>[]> {
  return get<Record<string, unknown>[]>('/api/plan/strategies')
}

/**
 * 查询指定计划的执行进度。
 *
 * 返回计划中每个节点的执行状态（待执行/执行中/已完成/失败）、
 * 当前执行到的步骤、总体完成百分比和预估剩余时间。
 *
 * @param planId 计划唯一标识
 * @returns 包含进度百分比、当前步骤和节点状态的对象
 */
export function getProgress(
  planId: string,
): Promise<Record<string, unknown>> {
  return get<Record<string, unknown>>(`/api/plan/progress/${planId}`)
}
