export interface ProviderInfo {
  name: string
  type: string
  enabled: boolean
}

export interface ModelConfig {
  provider: string
  model: string
  parameters: ModelParameters
}

export interface ModelParameters {
  temperature: number
  maxTokens: number
  topP: number
  frequencyPenalty: number
  presencePenalty: number
}

export interface ModelOption {
  label: string
  value: string
  provider: string
}

export interface APIEndpoint {
  name: string
  url: string
  key: string
  isActive: boolean
}
