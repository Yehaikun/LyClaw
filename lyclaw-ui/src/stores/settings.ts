import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

const STORAGE_KEY = 'lyclaw-settings'

export interface SettingsState {
  theme: 'light' | 'dark'
  defaultModel: string
  language: string
  apiBaseUrl: string
  maxRetries: number
  retryDelay: number
  sendOnEnter: boolean
  compactMode: boolean
  developerMode: boolean
  sandboxLevel: string
  maxTokens: number
  temperature: number
  topP: number
  fontSize: number
  codeFontSize: number
  showLineNumbers: boolean
  autoScroll: boolean
  sidebarCollapsed: boolean
}

function loadPersistedSettings(): Partial<SettingsState> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      return JSON.parse(raw) as Partial<SettingsState>
    }
  } catch {
    console.warn('Failed to parse stored settings, using defaults.')
  }
  return {}
}

function persistSettings(state: SettingsState): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch {
    console.warn('Failed to persist settings to localStorage.')
  }
}

export const useSettingsStore = defineStore('settings', () => {
  const persisted = loadPersistedSettings()

  // ---- State ----
  const theme = ref<'light' | 'dark'>(persisted.theme ?? 'light')
  const defaultModel = ref<string>(
    persisted.defaultModel ?? 'deepseek-4-pro',
  )
  const language = ref<string>(persisted.language ?? 'zh')
  const apiBaseUrl = ref<string>(persisted.apiBaseUrl ?? '')
  const maxRetries = ref<number>(persisted.maxRetries ?? 5)
  const retryDelay = ref<number>(persisted.retryDelay ?? 2000)
  const sendOnEnter = ref<boolean>(persisted.sendOnEnter ?? true)
  const compactMode = ref<boolean>(persisted.compactMode ?? false)
  const developerMode = ref<boolean>(persisted.developerMode ?? false)
  const sandboxLevel = ref<string>(persisted.sandboxLevel ?? 'NONE')
  const maxTokens = ref<number>(persisted.maxTokens ?? 4096)
  const temperature = ref<number>(persisted.temperature ?? 0.7)
  const topP = ref<number>(persisted.topP ?? 1.0)
  const fontSize = ref<number>(persisted.fontSize ?? 16)
  const codeFontSize = ref<number>(persisted.codeFontSize ?? 14)
  const showLineNumbers = ref<boolean>(persisted.showLineNumbers ?? true)
  const autoScroll = ref<boolean>(persisted.autoScroll ?? true)
  const sidebarCollapsed = ref<boolean>(persisted.sidebarCollapsed ?? false)

  // ---- Persistence watchers ----
  const stateRefs = [
    theme,
    defaultModel,
    language,
    apiBaseUrl,
    maxRetries,
    retryDelay,
    sendOnEnter,
    compactMode,
    developerMode,
    sandboxLevel,
    maxTokens,
    temperature,
    topP,
    fontSize,
    codeFontSize,
    showLineNumbers,
    autoScroll,
    sidebarCollapsed,
  ]

  for (const refVal of stateRefs) {
    watch(
      refVal,
      () => {
        persistSettings({
          theme: theme.value,
          defaultModel: defaultModel.value,
          language: language.value,
          apiBaseUrl: apiBaseUrl.value,
          maxRetries: maxRetries.value,
          retryDelay: retryDelay.value,
          sendOnEnter: sendOnEnter.value,
          compactMode: compactMode.value,
          developerMode: developerMode.value,
          sandboxLevel: sandboxLevel.value,
          maxTokens: maxTokens.value,
          temperature: temperature.value,
          topP: topP.value,
          fontSize: fontSize.value,
          codeFontSize: codeFontSize.value,
          showLineNumbers: showLineNumbers.value,
          autoScroll: autoScroll.value,
          sidebarCollapsed: sidebarCollapsed.value,
        })
      },
      { deep: false },
    )
  }

  // ---- Actions ----

  /** Toggle between light and dark theme. */
  function toggleTheme(): void {
    theme.value = theme.value === 'light' ? 'dark' : 'light'
  }

  /** Toggle sidebar collapsed state. */
  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  /** Set the default model. */
  function setModel(model: string): void {
    defaultModel.value = model
  }

  /** Set the UI language. */
  function setLanguage(lang: string): void {
    language.value = lang
  }

  /** Reset all settings to defaults and clear persisted data. */
  function reset(): void {
    theme.value = 'light'
    defaultModel.value = 'deepseek-4-pro'
    language.value = 'zh'
    apiBaseUrl.value = ''
    maxRetries.value = 5
    retryDelay.value = 2000
    sendOnEnter.value = true
    compactMode.value = false
    developerMode.value = false
    sandboxLevel.value = 'NONE'
    maxTokens.value = 4096
    temperature.value = 0.7
    topP.value = 1.0
    fontSize.value = 16
    codeFontSize.value = 14
    showLineNumbers.value = true
    autoScroll.value = true
    sidebarCollapsed.value = false
    localStorage.removeItem(STORAGE_KEY)
  }

  return {
    // State
    theme,
    defaultModel,
    language,
    apiBaseUrl,
    maxRetries,
    retryDelay,
    sendOnEnter,
    compactMode,
    developerMode,
    sandboxLevel,
    maxTokens,
    temperature,
    topP,
    fontSize,
    codeFontSize,
    showLineNumbers,
    autoScroll,
    sidebarCollapsed,
    // Actions
    toggleTheme,
    toggleSidebar,
    setModel,
    setLanguage,
    reset,
  }
})
