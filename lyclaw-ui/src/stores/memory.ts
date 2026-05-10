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

export interface MemoryLayer {
  name: string
  count: number
  color: string
}

export const useMemoryStore = defineStore('memory', () => {
  // ---- State ----
  const stats = ref<MemoryStats | null>(null)
  const queryResults = ref<MemoryEntry[]>([])
  const isRetrieving = ref<boolean>(false)

  // ---- Derived: layers for stats display ----
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

  // ---- Actions ----

  /** Fetch memory statistics from the API. */
  async function fetchStats(): Promise<void> {
    try {
      stats.value = await getMemoryStats()
    } catch (err) {
      console.error('Failed to fetch memory stats:', err)
    }
  }

  /** Retrieve memory entries matching the given query. */
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

  /** Ingest new perception data into memory. */
  async function ingestMemoryAction(data: PerceptionData): Promise<void> {
    try {
      await ingestMemory(data, '', undefined)
    } catch (err) {
      console.error('Failed to ingest memory:', err)
    }
  }

  return {
    // State
    stats,
    queryResults,
    isRetrieving,
    layers,
    // Actions
    fetchStats,
    retrieveMemory: retrieveMemoryAction,
    ingestMemory: ingestMemoryAction,
  }
})
