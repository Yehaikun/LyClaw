import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Session } from '@/types'
import { sessionDisplay } from '@/types/session'

export const useSessionStore = defineStore('session', () => {
  // State
  const sessions = ref<Session[]>([])
  const currentSessionId = ref<string>('')
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const searchQuery = ref('')

  // Computed
  const filteredSessions = computed(() => {
    if (!searchQuery.value.trim()) return sessions.value
    const q = searchQuery.value.toLowerCase()
    return sessions.value.filter((s) => {
      const d = sessionDisplay(s)
      return (
        d.title.toLowerCase().includes(q) ||
        d.lastMessage.toLowerCase().includes(q)
      )
    })
  })

  const currentSession = computed(() =>
    sessions.value.find((s) => s.id === currentSessionId.value) ?? null,
  )

  const sessionCount = computed(() => sessions.value.length)

  // Actions
  function initSession(): string {
    const id = crypto.randomUUID()
    currentSessionId.value = id
    return id
  }

  async function fetchSessions(): Promise<void> {
    isLoading.value = true
    error.value = null

    try {
      const response = await fetch('/api/sessions')
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }
      const data: Session[] = await response.json()
      sessions.value = data.sort(
        (a, b) =>
          new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime(),
      )
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err)
      error.value = msg
    } finally {
      isLoading.value = false
    }
  }

  async function createSession(): Promise<string> {
    const id = crypto.randomUUID()
    const newSession: Session = {
      id,
      sessionId: id,
      name: '新对话',
      messages: [],
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }

    sessions.value.unshift(newSession)
    currentSessionId.value = id

    try {
      const response = await fetch('/api/sessions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id }),
      })
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }
    } catch {
      // Session exists locally even if server fails
    }

    return id
  }

  async function deleteSession(id: string): Promise<void> {
    const idx = sessions.value.findIndex((s) => s.id === id)
    if (idx === -1) return

    sessions.value.splice(idx, 1)

    if (currentSessionId.value === id) {
      currentSessionId.value = sessions.value[0]?.id ?? ''
    }

    try {
      const response = await fetch(`/api/sessions/${id}`, {
        method: 'DELETE',
      })
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }
    } catch {
      // Best effort delete
    }
  }

  function selectSession(id: string): void {
    currentSessionId.value = id
  }

  function updateSessionTitle(id: string, title: string): void {
    const session = sessions.value.find((s) => s.id === id)
    if (session) {
      session.name = title
      session.updatedAt = new Date().toISOString()
    }
  }

  function setSearchQuery(query: string): void {
    searchQuery.value = query
  }

  return {
    // State
    sessions,
    currentSessionId,
    isLoading,
    error,
    searchQuery,

    // Computed
    filteredSessions,
    currentSession,
    sessionCount,

    // Actions
    initSession,
    fetchSessions,
    createSession,
    deleteSession,
    selectSession,
    updateSessionTitle,
    setSearchQuery,
  }
})
