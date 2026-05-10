import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Session } from '@/types'
import {
  createSession as apiCreateSession,
  getSession,
  deleteSession as apiDeleteSession,
} from '@/api/chat'

export const useSessionStore = defineStore('session', () => {
  // ---- State ----
  const sessions = ref<Session[]>([])
  const currentSessionId = ref<string | null>(null)
  const isLoading = ref<boolean>(false)
  const searchQuery = ref<string>('')

  // ---- Getters ----

  const filteredSessions = computed<Session[]>(() => {
    if (!searchQuery.value.trim()) {
      return sessions.value
    }
    const q = searchQuery.value.toLowerCase()
    return sessions.value.filter(
      (s) =>
        s.name.toLowerCase().includes(q) ||
        s.sessionId.toLowerCase().includes(q),
    )
  })

  const currentSession = computed<Session | null>(() => {
    if (!currentSessionId.value) return null
    return (
      sessions.value.find((s) => s.sessionId === currentSessionId.value) ?? null
    )
  })

  // ---- Actions ----

  /** Create a new session via the API and add it to the local list. */
  async function createSession(): Promise<Session> {
    const session = await apiCreateSession()
    sessions.value.push(session)
    currentSessionId.value = session.sessionId
    return session
  }

  /** Fetch all sessions from the API. Falls back to in-memory sessions if endpoint unavailable. */
  async function fetchSessions(): Promise<void> {
    isLoading.value = true
    try {
      const response = await fetch('/api/sessions')
      if (response.ok) {
        const data: Session[] = await response.json()
        sessions.value = data
      }
      // 405 or other non-ok: keep existing in-memory sessions
    } catch (err) {
      // Network error or endpoint not available — keep existing sessions
      console.warn('GET /api/sessions unavailable, using in-memory sessions')
    } finally {
      isLoading.value = false
    }
  }

  /** Delete a session by ID. */
  async function deleteSession(id: string): Promise<void> {
    await apiDeleteSession(id)
    sessions.value = sessions.value.filter((s) => s.sessionId !== id)
    if (currentSessionId.value === id) {
      currentSessionId.value = null
    }
  }

  /** Select a session as active. */
  function selectSession(id: string): void {
    currentSessionId.value = id
  }

  /** Rename a session and persist the change. */
  async function renameSession(id: string, name: string): Promise<void> {
    const session = sessions.value.find((s) => s.sessionId === id)
    if (session) {
      session.name = name
    }
    try {
      await fetch(`/api/sessions/${id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      })
    } catch (err) {
      console.error('Failed to rename session:', err)
    }
  }

  return {
    // State
    sessions,
    currentSessionId,
    isLoading,
    searchQuery,
    // Getters
    filteredSessions,
    currentSession,
    // Actions
    createSession,
    fetchSessions,
    deleteSession,
    selectSession,
    renameSession,
  }
})
