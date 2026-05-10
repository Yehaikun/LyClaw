export type Theme = 'light' | 'dark'
export type Language = 'zh' | 'en'
export type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'reconnecting'

export interface SettingsState {
  theme: Theme
  defaultModel: string
  language: Language
  apiBaseUrl: string
  maxRetries: number
  retryDelay: number
}
