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
  // ---- State ----
  const tools = ref<ToolDefinition[]>([])
  const skills = ref<SkillResult[]>([])
  const sandboxHealth = ref<ServiceHealth | null>(null)
  const isLoadingTools = ref<boolean>(false)
  const isLoadingSkills = ref<boolean>(false)

  // ---- Actions ----

  /** Fetch the list of available tools from the API. */
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

  /** Fetch the list of available skills from the API. */
  async function fetchSkills(): Promise<void> {
    isLoadingSkills.value = true
    try {
      const rawSkills = await listSkills()
      // Map raw skill records to SkillResult shape for display
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

  /** Check sandbox health status. */
  async function checkSandboxHealth(): Promise<void> {
    try {
      sandboxHealth.value = await getSandboxHealth()
    } catch (err) {
      console.error('Failed to check sandbox health:', err)
      sandboxHealth.value = { healthy: false }
    }
  }

  /** Execute a tool with the given name and arguments. */
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
      // Store the result as a skill-like output for display
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

  /** Execute a skill with the given ID and parameters. */
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
    // State
    tools,
    skills,
    sandboxHealth,
    isLoadingTools,
    isLoadingSkills,
    // Actions
    fetchTools,
    fetchSkills,
    checkSandboxHealth,
    executeTool: executeToolAction,
    executeSkill: executeSkillAction,
  }
})
