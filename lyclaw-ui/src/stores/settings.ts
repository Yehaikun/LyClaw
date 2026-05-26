/**
 * 应用设置状态管理Store（Pinia），管理主题、字体、模型参数、开发者选项等全局配置。
 *
 * 本Store是前端所有可持久化设置的唯一数据源，涵盖以下设置域：
 *
 * 1. 外观设置（Appearance）：
 *    - theme：明暗主题切换（light/dark）
 *    - fontSize：正文字体大小（14/16/18px）
 *    - codeFontSize：代码块字体大小（12/14/16px）
 *    - showLineNumbers：代码块是否显示行号
 *    - compactMode：紧凑模式，减小间距和留白
 *    - sidebarCollapsed：侧栏折叠状态
 *
 * 2. 模型设置（Model）：
 *    - defaultModel：默认使用的LLM模型
 *    - maxTokens：单次生成的最大token数
 *    - temperature：生成随机性（0-2，越低越确定，越高越有创意）
 *    - topP：核采样概率阈值（0-1）
 *
 * 3. API设置（API）：
 *    - apiBaseUrl：API基础URL，留空使用同源地址
 *    - maxRetries：请求失败最大重试次数
 *    - retryDelay：重试间隔毫秒数
 *    - sandboxLevel：代码执行沙箱安全级别
 *
 * 4. 聊天设置（Chat）：
 *    - sendOnEnter：回车键直接发送（否则Shift+Enter换行、Enter发送）
 *    - autoScroll：新消息到达时自动滚动到底部
 *    - developerMode：开发者模式，启用后显示额外的调试信息
 *
 * 持久化机制：
 * - 所有设置项通过localStorage持久化，key为"lyclaw-settings"
 * - 每个ref都使用watch监听变化，变化时自动调用persistSettings写入localStorage
 * - 应用启动时通过loadPersistedSettings恢复上次保存的设置值
 * - reset函数清除localStorage并恢复所有设置为默认值
 */
import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

const STORAGE_KEY = 'lyclaw-settings'

/**
 * 设置状态的完整类型定义。
 * 所有字段都可选，因为持久化的设置可能只包含部分字段。
 */
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

/**
 * 从localStorage加载持久化的设置值。
 *
 * 安全解析JSON，任何解析失败都静默返回空对象，
 * 后续各ref会使用默认值填充缺失的字段。
 * 这样即使localStorage数据损坏也不会导致应用崩溃。
 *
 * @returns 部分设置对象，只包含成功解析的字段
 */
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

/**
 * 将完整设置状态持久化到localStorage。
 *
 * 使用JSON序列化整个SettingsState对象。
 * 存储失败（如localStorage已满）时静默警告，不影响用户继续使用。
 *
 * @param state 完整的设置状态对象
 */
function persistSettings(state: SettingsState): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch {
    console.warn('Failed to persist settings to localStorage.')
  }
}

export const useSettingsStore = defineStore('settings', () => {
  const persisted = loadPersistedSettings()

  // ====================================================================
  // 状态（State）—— 每个设置项一个ref，优先使用持久化值，其次使用默认值
  // ====================================================================

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
  const reflectionMode = ref<boolean>(false)
  const sandboxLevel = ref<string>(persisted.sandboxLevel ?? 'NONE')
  const maxTokens = ref<number>(persisted.maxTokens ?? 4096)
  const temperature = ref<number>(persisted.temperature ?? 0.7)
  const topP = ref<number>(persisted.topP ?? 1.0)
  const fontSize = ref<number>(persisted.fontSize ?? 16)
  const codeFontSize = ref<number>(persisted.codeFontSize ?? 14)
  const showLineNumbers = ref<boolean>(persisted.showLineNumbers ?? true)
  const autoScroll = ref<boolean>(persisted.autoScroll ?? true)
  const sidebarCollapsed = ref<boolean>(persisted.sidebarCollapsed ?? false)

  // ====================================================================
  // 持久化监听器（Persistence Watchers）
  // 为每个设置ref建立watch，任何变化立即触发persistSettings写入localStorage
  // ====================================================================

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
    reflectionMode,
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

  // ====================================================================
  // 操作方法（Actions）
  // ====================================================================

  /**
   * 切换明暗主题。
   *
   * 在light和dark之间切换theme值。
   * 主题变化通过CSS变量系统驱动全局样式变更，无需刷新页面。
   */
  function toggleTheme(): void {
    theme.value = theme.value === 'light' ? 'dark' : 'light'
  }

  /**
   * 切换侧栏折叠状态。
   *
   * 侧栏折叠后宽度变为0，主内容区相应扩展。
   * 通过CSS transition实现平滑的折叠动画。
   */
  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  /**
   * 设置默认模型。
   *
   * 新创建的会话将默认使用此模型。
   * 更改后不影响当前活跃会话的模型选择。
   *
   * @param model 模型标识符（如deepseek-4-pro）
   */
  function setModel(model: string): void {
    defaultModel.value = model
  }

  /**
   * 设置UI界面语言。
   *
   * 当前支持的语言代码：zh（中文）、en（英文）。
   * 注意：此设置目前预留，完整的多语言支持尚未实现。
   *
   * @param lang 语言代码
   */
  function setLanguage(lang: string): void {
    language.value = lang
  }

  /**
   * 重置所有设置为默认值并清除localStorage中的持久化数据。
   *
   * 将所有设置ref恢复到定义时的默认值，
   * 然后删除localStorage中的lyclaw-settings键。
   * 此操作不可逆，用户需重新配置个性化设置。
   */
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
    // 状态
    theme,
    defaultModel,
    language,
    apiBaseUrl,
    maxRetries,
    retryDelay,
    sendOnEnter,
    compactMode,
    developerMode,
    reflectionMode,
    sandboxLevel,
    maxTokens,
    temperature,
    topP,
    fontSize,
    codeFontSize,
    showLineNumbers,
    autoScroll,
    sidebarCollapsed,
    // 操作方法
    toggleTheme,
    toggleSidebar,
    setModel,
    setLanguage,
    reset,
  }
})
