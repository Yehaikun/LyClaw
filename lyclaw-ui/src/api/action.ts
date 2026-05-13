/**
 * Action服务API封装，提供工具执行、技能调用、沙箱健康检查等接口。
 *
 * LyClaw的Action服务是整个系统的"手脚"，负责实际执行各种操作：
 * - 工具执行（executeTool）：调用LLM选择的工具并返回执行结果
 * - 技能执行（executeSkill）：调用预定义的技能组合完成复杂任务
 * - 工具列表（listTools）：获取系统所有可用工具的定义、参数和描述
 * - 技能列表（listSkills）：获取系统所有可用技能的元数据
 * - 沙箱健康（getSandboxHealth）：检查代码执行沙箱的运行状态
 * - 工具统计（getToolStats）：获取工具调用次数、成功率等统计数据
 *
 * 所有函数均基于client.ts中的get/post基础函数构建，自动继承超时控制和错误处理。
 */
import { get, post } from './client'
import type {
  ToolExecuteRequest,
  ToolResult,
  SkillExecuteRequest,
  SkillResult,
  ToolDefinition,
} from '../types'

/**
 * 执行指定工具并返回结果。
 *
 * 将工具名称和参数发送至Action服务的/execute-tool端点，
 * 服务端根据工具名称路由到对应的Tool实现类，执行完毕后返回
 * ToolResult包含成功标志、输出内容、错误信息和执行耗时。
 *
 * @param req 工具执行请求，包含toolName（工具名）、args（参数键值对）和可选的sandboxLevel
 * @returns ToolResult包含success、output、errorMessage和durationMs
 */
export function executeTool(req: ToolExecuteRequest): Promise<ToolResult> {
  return post<ToolResult>('/api/action/execute-tool', req)
}

/**
 * 执行指定技能并返回结果。
 *
 * 技能是比工具更高层次的抽象，通常由多个工具调用组合而成。
 * 例如"代码审查"技能可能依次调用文件读取、静态分析和LLM生成三个工具。
 *
 * @param req 技能执行请求，包含skillId和可选参数
 * @returns SkillResult包含success、output、error、tokenUsage和elapsedMs
 */
export function executeSkill(req: SkillExecuteRequest): Promise<SkillResult> {
  return post<SkillResult>('/api/action/execute-skill', req)
}

/**
 * 获取系统所有可用工具的列表。
 *
 * 返回的ToolDefinition数组包含每个工具的名称、显示名称、描述、
 * 参数定义、来源（Built-in/MCP/A2A）和超时设置。
 * ToolStore在应用启动时调用此函数获取工具注册表。
 *
 * @returns 工具定义数组
 */
export function listTools(): Promise<ToolDefinition[]> {
  return get<ToolDefinition[]>('/api/action/tools')
}

/**
 * 获取系统所有可用技能的列表。
 *
 * 返回技能元数据数组，每个技能包含skillId、名称和描述信息。
 * 技能列表通常比工具列表更稳定，因为它们定义在配置或代码中。
 *
 * @returns 技能元数据数组
 */
export function listSkills(): Promise<Record<string, unknown>[]> {
  return get<Record<string, unknown>[]>('/api/action/skills')
}

/**
 * 检查代码执行沙箱的健康状态。
 *
 * 沙箱是用于安全执行LLM生成代码的隔离环境。
 * 如果沙箱不可用，代码执行类的工具将无法正常工作。
 *
 * @returns 包含healthy布尔标志的健康状态对象
 */
export function getSandboxHealth(): Promise<{ healthy: boolean }> {
  return get<{ healthy: boolean }>('/api/action/sandbox/health')
}

/**
 * 获取工具调用的统计信息。
 *
 * 返回各工具的调用次数、成功率、平均耗时等统计数据，
 * 用于Dashboard页面展示系统运行概况。
 *
 * @returns 包含各项统计指标的对象
 */
export function getToolStats(): Promise<Record<string, unknown>> {
  return get<Record<string, unknown>>('/api/action/tools/stats')
}
