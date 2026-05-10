import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import type { Theme, Language } from '@/types'

const STORAGE_KEY = 'lyclaw-settings'

interface StoredSettings {
  theme: Theme
  defaultModel: string
  language: Language
  apiBaseUrl: string
}

function loadSettings(): StoredSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      return JSON.parse(raw) as StoredSettings
    }
  } catch {
    // Ignore parse errors, use defaults
  }
  return {
    theme: 'light',
    defaultModel: 'gpt-4',
    language: 'zh',
    apiBaseUrl: '',
  }
}

export const useSettingsStore = defineStore('settings', () => {
  const saved = loadSettings()

  // State
  const theme = ref<Theme>(saved.theme)
  const defaultModel = ref(saved.defaultModel)
  const language = ref<Language>(saved.language)
  const apiBaseUrl = ref(saved.apiBaseUrl)

  // Apply theme immediately
  applyTheme(theme.value)

  function applyTheme(t: Theme): void {
    document.documentElement.setAttribute('data-theme', t)
  }

  function persist(): void {
    const data: StoredSettings = {
      theme: theme.value,
      defaultModel: defaultModel.value,
      language: language.value,
      apiBaseUrl: apiBaseUrl.value,
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(data))
  }

  function toggleTheme(): void {
    theme.value = theme.value === 'light' ? 'dark' : 'light'
    applyTheme(theme.value)
    persist()
  }

  function setTheme(t: Theme): void {
    theme.value = t
    applyTheme(t)
    persist()
  }

  function setDefaultModel(model: string): void {
    defaultModel.value = model
    persist()
  }

  function setLanguage(lang: Language): void {
    language.value = lang
    persist()
  }

  function setApiBaseUrl(url: string): void {
    apiBaseUrl.value = url
    persist()
  }

  // Auto-persist on changes
  watch([theme, defaultModel, language, apiBaseUrl], () => {
    persist()
  }, { deep: true })

  // Watch system color scheme preference
  const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
  mediaQuery.addEventListener('change', (e) => {
    // Only auto-switch if user hasn't explicitly set a preference
    if (!localStorage.getItem(STORAGE_KEY)) {
      theme.value = e.matches ? 'dark' : 'light'
      applyTheme(theme.value)
    }
  })

  return {
    // State
    theme,
    defaultModel,
    language,
    apiBaseUrl,

    // Actions
    toggleTheme,
    setTheme,
    setDefaultModel,
    setLanguage,
    setApiBaseUrl,
  }
})
