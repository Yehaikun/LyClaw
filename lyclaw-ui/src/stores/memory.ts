/**
 * 记忆系统状态管理Store（Pinia），管理四层记忆架构的统计数据、检索结果和记忆摄取。
 *
 * LyClaw记忆系统模拟人类记忆的多层结构，包含四个层次：
 * 1. 感知记忆（SENSORY）：原始交互记录的短期缓存，容量最大但保留时间最短
 * 2. 短期记忆（SHORT_TERM）：经过摘要压缩的近期重要信息，访问速度快
 * 3. 长期记忆（LONG_TERM）：持久化的结构化知识，支持语义检索，可跨会话保留
 * 4. 实体记忆（ENTITY）：关于具体实体（用户、项目、任务）的结构化信息
 *
 * 本Store负责：
 * - 获取并展示各层记忆的统计数据（条目数、Token数、平均重要性等）
 * - 执行记忆检索（支持文本语义搜索和多维度过滤）
 * - 手动摄取感知数据到记忆系统
 * - 将统计数据转化为可视化层所需的数据格式
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type {
  MemoryStats,
  MemoryEntry,
  MemoryQuery,
  PerceptionData,
} from '@/types'
import {
  getMemoryStats,
  retrieveMemory,
  ingestMemory,
} from '@/api/memory'

/** 记忆层可视化数据，包含层名、条目数和展示颜色 */
export interface MemoryLayer {
  name: string
  count: number
  color: string
}

export const useMemoryStore = defineStore('memory', () => {
  // ====================================================================
  // 状态（State）
  // ====================================================================

  /** 记忆系统统计数据，包含各层条目数、总Token数等指标 */
  const stats = ref<MemoryStats | null>(null)
  /** 最近一次检索的结果列表 */
  const queryResults = ref<MemoryEntry[]>([])
  /** 是否正在执行检索操作 */
  const isRetrieving = ref<boolean>(false)

  // ====================================================================
  // 计算属性：将统计数据转化为UI展示所需的层信息
  // ====================================================================

  /**
   * 从stats计算各记忆层的展示数据。
   *
   * 若stats为null（未加载），返回各层计数为0的默认数据。
   * 每层分配固定的展示颜色以便在统计面板中区分。
   * - 感知记忆：蓝绿色 #5db8a6
   * - 短期记忆：琥珀色 #e8a55a
   * - 长期记忆：暖橙色 #cc785c
   * - 实体记忆：灰绿色 #8e8b82
   */
  const layers = computed<MemoryLayer[]>(() => {
    if (!stats.value) {
      return [
        { name: '感知记忆', count: 0, color: '#5db8a6' },
        { name: '短期记忆', count: 0, color: '#e8a55a' },
        { name: '长期记忆', count: 0, color: '#cc785c' },
        { name: '实体记忆', count: 0, color: '#8e8b82' },
      ]
    }
    return [
      {
        name: '感知记忆',
        count: stats.value.perceptionCount,
        color: '#5db8a6',
      },
      {
        name: '短期记忆',
        count: stats.value.shortTermCount,
        color: '#e8a55a',
      },
      {
        name: '长期记忆',
        count: stats.value.longTermCount,
        color: '#cc785c',
      },
      {
        name: '实体记忆',
        count: stats.value.entityCount,
        color: '#8e8b82',
      },
    ]
  })

  // ====================================================================
  // 操作方法（Actions）
  // ====================================================================

  /**
   * 从服务端获取记忆系统的统计数据。
   *
   * 调用GET /api/memory/stats获取各层统计数据。
   * 获取成功后stats更新，layers计算属性自动重新计算。
   * 获取失败时静默处理，控制台输出错误信息。
   */
  async function fetchStats(): Promise<void> {
    try {
      stats.value = await getMemoryStats()
    } catch (err) {
      console.error('Failed to fetch memory stats:', err)
    }
  }

  /**
   * 执行记忆检索操作。
   *
   * 根据MemoryQuery中指定的查询文本、权重参数和多维度过滤条件，
   * 调用服务端检索API获取匹配的记忆条目。
   * 检索期间isRetrieving为true，UI显示加载状态。
   *
   * @param query 包含查询文本、topK、权重和过滤条件的MemoryQuery对象
   */
  async function retrieveMemoryAction(query: MemoryQuery): Promise<void> {
    isRetrieving.value = true
    try {
      const result = await retrieveMemory(query)
      queryResults.value = result.entries
    } catch (err) {
      console.error('Failed to retrieve memory:', err)
      queryResults.value = []
    } finally {
      isRetrieving.value = false
    }
  }

  /**
   * 手动将感知数据摄取到记忆系统。
   *
   * 允许用户通过Memory页面的"手动记录"面板直接向记忆系统写入数据。
   * 实际使用中，记忆的自动摄取由后端在每次对话完成时触发。
   *
   * @param data 包含角色、内容、时间戳和工具调用ID的PerceptionData对象
   */
  async function ingestMemoryAction(data: PerceptionData): Promise<void> {
    try {
      await ingestMemory(data, '', undefined)
    } catch (err) {
      console.error('Failed to ingest memory:', err)
    }
  }

  return {
    // 状态
    stats,
    queryResults,
    isRetrieving,
    layers,
    // 操作方法
    fetchStats,
    retrieveMemory: retrieveMemoryAction,
    ingestMemory: ingestMemoryAction,
  }
})
