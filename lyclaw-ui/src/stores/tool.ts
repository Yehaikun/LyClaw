/**
 * 工具与技能状态管理Store（Pinia），管理工具注册表、技能列表、沙箱健康状态和工具执行。
 *
 * LyClaw的Action服务承载了所有可被LLM调用的工具（Tools）和预定义技能（Skills）。
 * 本Store作为前端工具管理的核心状态管理器，负责：
 *
 * 1. 工具注册表管理（tools）：
 *    - fetchTools从服务端获取所有可用工具的定义列表
 *    - 工具包含name、displayName、description、parameters、source、timeout等字段
 *    - tools列表供ToolView渲染工具卡片和工具详情面板
 *
 * 2. 技能列表管理（skills）：
 *    - fetchSkills从服务端获取所有可用技能
 *    - 技能映射为SkillResult格式用于统一展示
 *    - 支持技能的手动执行和结果展示
 *
 * 3. 沙箱健康监控（sandboxHealth）：
 *    - checkSandboxHealth检查代码执行沙箱是否可用
 *    - 沙箱是代码执行类工具的前提条件
 *
 * 4. 工具手动执行（executeTool）：
 *    - 允许用户在Tools页面手动输入参数并执行工具
 *    - 执行结果以skillResult格式保存并展示
 *
 * 5. 技能手动执行（executeSkill）：
 *    - 允许用户在Tools页面点击执行按钮触发技能
 *    - 执行结果内联展示在技能卡片下方
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type {
  ToolDefinition,
  SkillResult,
  ServiceHealth,
  ToolExecuteRequest,
  SkillExecuteRequest,
} from '@/types'
import {
  listTools,
  listSkills,
  getSandboxHealth,
  executeTool,
  executeSkill,
} from '@/api/action'

export const useToolStore = defineStore('tool', () => {
  // ====================================================================
  // 状态（State）
  // ====================================================================

  /** 工具注册表：所有可用工具的定义列表 */
  const tools = ref<ToolDefinition[]>([])
  /** 技能结果列表：执行过的技能及其结果 */
  const skills = ref<SkillResult[]>([])
  /** 沙箱健康状态 */
  const sandboxHealth = ref<ServiceHealth | null>(null)
  /** 是否正在加载工具列表 */
  const isLoadingTools = ref<boolean>(false)
  /** 是否正在加载技能列表 */
  const isLoadingSkills = ref<boolean>(false)

  // ====================================================================
  // 操作方法（Actions）
  // ====================================================================

  /**
   * 从服务端获取所有可用工具的定义列表。
   *
   * 工具定义包含名称、显示名称、描述、参数schema、来源和超时设置。
   * 获取成功后更新tools数组，Tools页面自动刷新工具卡片。
   */
  async function fetchTools(): Promise<void> {
    isLoadingTools.value = true
    try {
      tools.value = await listTools()
    } catch (err) {
      console.error('Failed to fetch tools:', err)
    } finally {
      isLoadingTools.value = false
    }
  }

  /**
   * 从服务端获取所有可用技能的列表。
   *
   * 原始技能数据通过map转换为统一的SkillResult格式，
   * 确保与工具执行结果使用相同的数据结构进行展示。
   * 转换时保留原始数据的所有字段，缺失字段使用默认值填充。
   */
  async function fetchSkills(): Promise<void> {
    isLoadingSkills.value = true
    try {
      const rawSkills = await listSkills()
      // 将原始技能数据映射为SkillResult形状以统一展示格式
      skills.value = rawSkills.map((s) => ({
        skillId: (s.skillId as string) ?? (s.id as string) ?? '',
        success: true,
        output: '',
        error: null,
        tokenUsage: 0,
        elapsedMs: 0,
        ...s,
      })) as SkillResult[]
    } catch (err) {
      console.error('Failed to fetch skills:', err)
    } finally {
      isLoadingSkills.value = false
    }
  }

  /**
   * 检查代码执行沙箱的健康状态。
   *
   * 沙箱是隔离执行LLM生成代码的安全环境。
   * 如果沙箱不可用，所有code_executor类工具将无法正常工作。
   * 检查失败时设置healthy为false。
   */
  async function checkSandboxHealth(): Promise<void> {
    try {
      sandboxHealth.value = await getSandboxHealth()
    } catch (err) {
      console.error('Failed to check sandbox health:', err)
      sandboxHealth.value = { healthy: false }
    }
  }

  /**
   * 手动执行指定工具并存储结果。
   *
   * 构造ToolExecuteRequest发送到服务端，执行成功后将结果
   * 追加到skills列表中以SkillResult格式展示。
   * 执行失败也记录到skills列表，包含错误信息。
   *
   * @param name 工具名称
   * @param args 工具参数字典
   */
  async function executeToolAction(
    name: string,
    args: Record<string, unknown>,
  ): Promise<void> {
    const req: ToolExecuteRequest = {
      toolName: name,
      args,
      sandboxLevel: 'NONE',
    }
    try {
      const result = await executeTool(req)
      // 将工具执行结果以skill格式存储用于统一展示
      skills.value.push({
        skillId: `tool-${name}-${Date.now()}`,
        success: result.success,
        output: result.output,
        error: result.errorMessage,
        tokenUsage: 0,
        elapsedMs: result.durationMs,
      })
    } catch (err) {
      console.error(`Failed to execute tool "${name}":`, err)
      skills.value.push({
        skillId: `tool-${name}-${Date.now()}`,
        success: false,
        output: '',
        error: (err as Error).message,
        tokenUsage: 0,
        elapsedMs: 0,
      })
    }
  }

  /**
   * 手动执行指定技能并存储结果。
   *
   * 成功执行后将结果追加到skills列表，
   * 执行失败也记录，包含错误信息。
   *
   * @param skillId 技能唯一标识
   * @param params 可选的技能参数
   */
  async function executeSkillAction(
    skillId: string,
    params?: Record<string, unknown>,
  ): Promise<void> {
    const req: SkillExecuteRequest = {
      skillId,
      params,
    }
    try {
      const result = await executeSkill(req)
      skills.value.push(result)
    } catch (err) {
      console.error(`Failed to execute skill "${skillId}":`, err)
      skills.value.push({
        skillId,
        success: false,
        output: '',
        error: (err as Error).message,
        tokenUsage: 0,
        elapsedMs: 0,
      })
    }
  }

  return {
    // 状态
    tools,
    skills,
    sandboxHealth,
    isLoadingTools,
    isLoadingSkills,
    // 操作方法
    fetchTools,
    fetchSkills,
    checkSandboxHealth,
    executeTool: executeToolAction,
    executeSkill: executeSkillAction,
  }
})
